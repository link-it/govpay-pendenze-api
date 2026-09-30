package it.govpay.pendenze.pendenza;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.pendenze.api.model.Pagination;
import it.govpay.pendenze.api.model.PendenzaIndex;
import it.govpay.pendenze.api.model.Pendenze;
import it.govpay.pendenze.api.rest.PendenzeApi;
import it.govpay.pendenze.criteri.CriteriOrdinamento;
import it.govpay.pendenze.criteri.CursorCodec;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.posizionedebitoria.PosizioneDebitoriaMapper;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.web.QueryParamUtils;
import it.govpay.pendenze.web.SelezioneCampi;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link PendenzeApi}: per ora {@link #findPendenze} e {@link #getPendenza}, le
 * altre operazioni restano sul default generato (501, vedi Javadoc dell'interfaccia) fino al
 * loro sviluppo.
 */
@RestController
public class PendenzaController implements PendenzeApi {

    /** Ordinamento fisso in modalita' cursore, coerente con govpay-console-api. */
    static final String ORDINAMENTO_FISSO_CURSORE = "dataCreazione desc, id desc";

    private final PosizioneDebitoriaMapper mapper;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final AclAuthorizer aclAuthorizer;

    public PendenzaController(PosizioneDebitoriaMapper mapper, PosizioneDebitoriaService posizioneDebitoriaService,
            ObjectMapper objectMapper, HttpServletRequest currentRequest,
            CurrentApplicazioneService currentApplicazioneService, AclAuthorizer aclAuthorizer) {
        this.mapper = mapper;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
        this.currentApplicazioneService = currentApplicazioneService;
        this.aclAuthorizer = aclAuthorizer;
    }

    /**
     * {@code @Transactional} qui, non solo sul servizio (stesso motivo di
     * {@code PosizioneDebitoriaController#getPosizioneDebitoria}): {@code mapper.toPendenzaDto}
     * attraversa {@code Pendenza.voci}/{@code opzionePagamento}/
     * {@code opzionePagamento.posizioneDebitoria.soggettiDebitori} (tutte LAZY), lette dopo che
     * la transazione di sola lettura del servizio sarebbe altrimenti gia' chiusa.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<it.govpay.pendenze.api.model.Pendenza> getPendenza(String idA2A, String idPendenza) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        Pendenza pendenza = posizioneDebitoriaService.trovaPendenzaPerIdentificativo(idA2A, idPendenza)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna pendenza con idPendenza [" + idPendenza
                        + "] per idA2A [" + idA2A + "]"));
        return ResponseEntity.ok(mapper.toPendenzaDto(pendenza));
    }

    /**
     * Nessun campo ordinabile per default (vedi Javadoc di {@link CriteriOrdinamento}):
     * questo endpoint ne espone tre, ragionevoli per un elenco di pendenze e tutti colonne
     * dirette di {@code versamenti} — nessuna indicazione esplicita nello YAML su quali
     * offrire. Ignorati in modalita' cursore, dove l'ordinamento e' fisso (vedi
     * {@link #ORDINAMENTO_FISSO_CURSORE}).
     */
    private static final Map<String, String> CAMPI_ORDINABILI = Map.of(
            "dataCreazione", "dataCreazione",
            "dataScadenzaAvviso", "dataScadenzaAvviso",
            "importo", "importo");

    /**
     * {@code @Transactional} qui, non solo sul servizio (stesso motivo di
     * {@code PosizioneDebitoriaController#getPosizioneDebitoria}/{@code findPosizioniDebitorie}):
     * {@code mapper.toPendenzaIndexDto} attraversa {@code Pendenza.opzionePagamento} (LAZY)
     * fino a {@code OpzionePagamento.posizioneDebitoria} e {@code soggettiDebitori} (LAZY),
     * tutte lette dopo che la transazione di sola lettura del servizio sarebbe altrimenti gia'
     * chiusa.
     *
     * <p>Due modalita' di paginazione mutuamente esclusive — vedi Javadoc di
     * {@code PosizioneDebitoriaController#findPosizioniDebitorie}, stessa logica qui.
     * {@code total=false} non evita un {@code COUNT} costoso su questo endpoint (IUV/NAV sono
     * univoci per dominio, M13: il risultato e' comunque limitato), ma la modalita' e' offerta
     * per uniformita' con {@code findPosizioniDebitorie} e con lo standard condiviso con
     * govpay-console-api.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Pendenze> findPendenze(String idA2A, String numeroAvviso, String idDominio, Integer page,
            String cursor, Integer limit, String sort, String fields, Boolean total) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        boolean modalitaCursore = cursor != null;
        if (modalitaCursore) {
            rifiutaSeIncompatibiliConCursore(sort, total);
        }
        Long idDominioRisolto = idDominio == null ? null : mapper.risolviIdDominio(idDominio);

        Pendenze dto = new Pendenze();
        List<PendenzaIndex> risultati;

        // Le pendenze prive di opzionePagamento (v2/migrazione) sono gia' escluse dalla query
        // di PosizioneDebitoriaService: un filtro qui, DOPO che il servizio ha gia'
        // paginato/contato, lascerebbe pagination/nextCursor disallineati dai risultati
        // restituiti — la guardia in PosizioneDebitoriaMapper#toPendenzaIndexDto resta solo
        // come difesa esplicita, non dovrebbe mai scattare.
        if (modalitaCursore) {
            CursorCodec.Cursore decodificato = cursor.isBlank() ? null : CursorCodec.decode(cursor);
            PaginaSenzaConteggio<Pendenza> pagina = posizioneDebitoriaService.cercaPendenzeDaCursore(idA2A,
                    numeroAvviso, idDominioRisolto, decodificato == null ? null : decodificato.dataCreazione(),
                    decodificato == null ? null : decodificato.id(), limit);
            risultati = pagina.risultati().stream().map(mapper::toPendenzaIndexDto).toList();
            if (pagina.haAltriRisultati() && !pagina.risultati().isEmpty()) {
                Pendenza ultima = pagina.risultati().get(pagina.risultati().size() - 1);
                dto.setNextCursor(CursorCodec.encode(ultima.getDataCreazione(), ultima.getId()));
            }
        } else {
            int paginaEffettiva = QueryParamUtils.isExplicit(currentRequest, "page") ? page : 1;
            Sort ordinamento = CriteriOrdinamento.parse(sort, CAMPI_ORDINABILI);
            Pageable pageable = OffsetPageRequest.of((long) (paginaEffettiva - 1) * limit, limit, ordinamento);

            Pagination pagination = new Pagination(paginaEffettiva, limit, false);
            if (Boolean.TRUE.equals(total)) {
                PaginaRisultati<Pendenza> pagina = posizioneDebitoriaService.cercaPendenze(idA2A, numeroAvviso,
                        idDominioRisolto, pageable);
                risultati = pagina.risultati().stream().map(mapper::toPendenzaIndexDto).toList();
                pagination.setHasNextPage(pagina.haAltriRisultati());
                pagination.setTotalResults(Math.toIntExact(pagina.numeroRisultatiTotali()));
                pagination.setTotalPages(
                        (int) Math.ceil(pagina.numeroRisultatiTotali() / (double) limit));
            } else {
                PaginaSenzaConteggio<Pendenza> pagina = posizioneDebitoriaService.cercaPendenzeSenzaConteggio(idA2A,
                        numeroAvviso, idDominioRisolto, pageable);
                risultati = pagina.risultati().stream().map(mapper::toPendenzaIndexDto).toList();
                pagination.setHasNextPage(pagina.haAltriRisultati());
            }
            dto.setPagination(pagination);
        }

        dto.setResults(SelezioneCampi.applica(risultati, fields, objectMapper));
        return ResponseEntity.ok(dto);
    }

    /**
     * Vedi Javadoc di {@code PosizioneDebitoriaController#rifiutaSeIncompatibiliConCursore}:
     * stessa regola, duplicata qui perche' i due controller non condividono una superclasse
     * (entrambi implementano interfacce generate diverse).
     */
    private void rifiutaSeIncompatibiliConCursore(String sort, Boolean total) {
        if (QueryParamUtils.isExplicit(currentRequest, "page")) {
            throw new ValidazioneNonSuperataException(
                    "parametri 'page' e 'cursor' mutuamente esclusivi: usare solo uno dei due "
                            + "(cursor per paginazione keyset, page per paginazione a offset)");
        }
        if (sort != null && !sort.isBlank()) {
            throw new ValidazioneNonSuperataException(
                    "in modalita' cursore (?cursor=...) l'ordinamento e' fisso (" + ORDINAMENTO_FISSO_CURSORE
                            + "): non specificare 'sort'");
        }
        if (Boolean.TRUE.equals(total)) {
            throw new ValidazioneNonSuperataException(
                    "in modalita' cursore (?cursor=...) il conteggio totale non e' disponibile: "
                            + "'total=true' non e' compatibile. Usa la presenza di 'nextCursor' in "
                            + "risposta per sapere se ci sono altre pagine");
        }
    }
}
