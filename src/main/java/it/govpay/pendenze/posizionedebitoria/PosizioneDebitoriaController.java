package it.govpay.pendenze.posizionedebitoria;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import it.govpay.pendenze.api.model.NuovaOpzionePagamento;
import it.govpay.pendenze.api.model.NuovaPosizioneDebitoria;
import it.govpay.pendenze.api.model.OpzionePagamento;
import it.govpay.pendenze.api.model.PatchOp;
import it.govpay.pendenze.api.model.Pagination;
import it.govpay.pendenze.api.model.PosizioneDebitoriaIndex;
import it.govpay.pendenze.api.model.PosizioniDebitorie;
import it.govpay.pendenze.api.rest.PosizioniDebitorieApi;
import it.govpay.pendenze.criteri.CriteriOrdinamento;
import it.govpay.pendenze.criteri.CursorCodec;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.web.QueryParamUtils;
import it.govpay.pendenze.web.SelezioneCampi;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link PosizioniDebitorieApi}: per ora {@link #addPosizioneDebitoria},
 * {@link #getPosizioneDebitoria}, {@link #updatePosizioneDebitoria},
 * {@link #findPosizioniDebitorie}, {@link #addOpzionePagamento} e
 * {@link #updateOpzionePagamento}, le altre operazioni restano sul default generato (501,
 * vedi Javadoc dell'interfaccia) fino al loro sviluppo.
 */
@RestController
public class PosizioneDebitoriaController implements PosizioniDebitorieApi {

    /** Ordinamento fisso in modalita' cursore, coerente con govpay-console-api. */
    static final String ORDINAMENTO_FISSO_CURSORE = "dataCreazione desc, id desc";

    private final PosizioneDebitoriaMapper mapper;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final AclAuthorizer aclAuthorizer;

    public PosizioneDebitoriaController(PosizioneDebitoriaMapper mapper,
            PosizioneDebitoriaService posizioneDebitoriaService, ObjectMapper objectMapper,
            HttpServletRequest currentRequest, CurrentApplicazioneService currentApplicazioneService,
            AclAuthorizer aclAuthorizer) {
        this.mapper = mapper;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
        this.currentApplicazioneService = currentApplicazioneService;
        this.aclAuthorizer = aclAuthorizer;
    }

    @Override
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> addPosizioneDebitoria(String idA2A,
            NuovaPosizioneDebitoria nuovaPosizioneDebitoria) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (nuovaPosizioneDebitoria == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }

        it.govpay.pendenze.entity.PosizioneDebitoria posizione = mapper.toEntity(idA2A, nuovaPosizioneDebitoria);
        it.govpay.pendenze.entity.PosizioneDebitoria creata = posizioneDebitoriaService.crea(posizione);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(PosizioniDebitorieApi.PATH_GET_POSIZIONE_DEBITORIA)
                .buildAndExpand(idA2A, creata.getIdPosizioneDebitoria())
                .toUri();

        return ResponseEntity.created(location).body(mapper.toDto(creata));
    }

    /**
     * {@code @Transactional} qui, non solo su {@code PosizioneDebitoriaService.trovaPerIdentificativo}:
     * quel metodo apre e chiude la propria transazione prima di
     * tornare al controller — con {@code open-in-view=false} la sessione Hibernate e' gia'
     * chiusa quando {@code mapper.toDto(...)} legge le collezioni LAZY dell'aggregato
     * ({@code soggettiDebitori}/{@code opzioniPagamento}/{@code pendenze}), sollevando
     * {@code LazyInitializationException} — stessa classe di bug gia' vista su
     * {@code TipoVersamentoDominio.tipoVersamento}, ma non risolvibile con un semplice
     * {@code join fetch} sul repository: {@code soggettiDebitori} e {@code opzioniPagamento}
     * sono entrambe {@code List} (bag), e Hibernate non ammette il fetch join di piu' di una
     * bag nella stessa query ({@code MultipleBagFetchException}). Estendere qui il confine
     * transazionale, cosi' che copra anche la mappatura, e' la soluzione piu' semplice che non
     * tocca la forma delle collezioni della libreria.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> getPosizioneDebitoria(String idA2A,
            String idPosizioneDebitoria) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        it.govpay.pendenze.entity.PosizioneDebitoria posizione = posizioneDebitoriaService
                .trovaPerIdentificativo(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        return ResponseEntity.ok(mapper.toDto(posizione));
    }

    /**
     * Applica un JSON Patch (RFC 6902, sottoinsieme add/remove/replace) alla posizione
     * debitoria — vedi Javadoc di {@link PosizioneDebitoriaMapper#applicaPatch} per i path
     * supportati e di {@link PosizioneDebitoriaService#aggiorna} per la rivalidazione e la
     * marcatura ACA applicate dopo il patch. Le opzioni di pagamento non si toccano qui
     * (vedi descrizione dell'operazione nello YAML).
     */
    @Override
    @Transactional
    public ResponseEntity<Void> updatePosizioneDebitoria(String idA2A, String idPosizioneDebitoria,
            List<PatchOp> patchOp) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (patchOp == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        posizioneDebitoriaService.aggiorna(idA2A, idPosizioneDebitoria,
                posizione -> mapper.applicaPatch(posizione, patchOp));
        return ResponseEntity.ok().build();
    }

    /**
     * Aggiunge una nuova opzione di pagamento a una posizione debitoria esistente — vedi
     * Javadoc di {@link PosizioneDebitoriaService#aggiungiOpzionePagamento} per l'ordine non
     * banale con cui l'opzione viene costruita/validata prima di essere collegata
     * all'aggregato gestito. {@code @Transactional} per lo stesso motivo di
     * {@link #getPosizioneDebitoria}: {@link PosizioneDebitoriaMapper#toOpzionePagamentoDto}
     * legge {@code entity.getPendenze()} sull'opzione appena creata — non un problema di per
     * se' (e' un oggetto costruito in memoria da questo stesso mapper, non un proxy LAZY
     * caricato dal DB), ma l'aggiunta e' avvenuta dentro la transazione del servizio: estendere
     * il confine qui evita comunque ogni rischio se la forma dell'aggregato cambiasse in
     * futuro. Nessun header {@code Location}: lo YAML non espone un GET per la singola
     * opzione di pagamento.
     */
    @Override
    @Transactional
    public ResponseEntity<OpzionePagamento> addOpzionePagamento(String idA2A, String idPosizioneDebitoria,
            NuovaOpzionePagamento nuovaOpzionePagamento) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (nuovaOpzionePagamento == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        it.govpay.pendenze.entity.OpzionePagamento creata = posizioneDebitoriaService.aggiungiOpzionePagamento(
                idA2A, idPosizioneDebitoria,
                posizione -> mapper.toOpzionePagamento(idA2A, posizione.getIdDominio(), nuovaOpzionePagamento));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(mapper.toOpzionePagamentoDto(creata));
    }

    /**
     * Annulla un'opzione di pagamento — l'unica transizione raggiungibile da questo endpoint
     * (vedi Javadoc di {@link PosizioneDebitoriaMapper#validaPatchAnnullamento}: l'attivazione
     * e' innescata da un pagamento reale, mai da un PATCH del chiamante). L'appartenenza di
     * {@code idOpzionePagamento} a QUESTA posizione/applicazione e' verificata dal servizio
     * (vedi Javadoc di {@code PosizioneDebitoriaService#annulla(String, String, UUID)}), non
     * qui: {@code idOpzionePagamento} da solo non e' altrimenti legato a nessun controllo di
     * appartenenza.
     */
    @Override
    @Transactional
    public ResponseEntity<Void> updateOpzionePagamento(String idA2A, String idPosizioneDebitoria,
            java.util.UUID idOpzionePagamento, List<PatchOp> patchOp) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        mapper.validaPatchAnnullamento(patchOp);
        posizioneDebitoriaService.annulla(idA2A, idPosizioneDebitoria, idOpzionePagamento);
        return ResponseEntity.ok().build();
    }

    /**
     * Nessun campo ordinabile per default (vedi Javadoc di {@link CriteriOrdinamento}):
     * questo endpoint ne espone solo tre, ragionevoli per un elenco di posizioni e tutti
     * colonne dirette di {@code documenti} — nessuna indicazione esplicita nello YAML su
     * quali offrire. Ignorati in modalita' cursore, dove l'ordinamento e' fisso (vedi
     * {@link #ORDINAMENTO_FISSO_CURSORE}).
     */
    private static final Map<String, String> CAMPI_ORDINABILI = Map.of(
            "dataCreazione", "dataCreazione",
            "dataPubblicazione", "dataPubblicazione",
            "idPosizioneDebitoria", "idPosizioneDebitoria");

    /**
     * {@code @Transactional} qui per lo stesso motivo di {@link #getPosizioneDebitoria}:
     * {@code toIndexDto} legge {@code soggettiDebitori}, collezione LAZY.
     *
     * <p>Due modalita' di paginazione mutuamente esclusive (standard condiviso con
     * govpay-console-api): a offset ({@code page}/{@code limit}, ordinamento libero via
     * {@code sort}, conteggio opzionale via {@code total}) o a cursore ({@code cursor},
     * ordinamento fisso {@link #ORDINAMENTO_FISSO_CURSORE}, nessun conteggio). La modalita'
     * cursore e' attiva se {@code cursor} e' presente nella richiesta, anche vuoto (prima
     * pagina cursore); in tal caso {@code page}/{@code sort} espliciti e {@code total=true}
     * sono rifiutati con 400 ({@link #rifiutaSeIncompatibiliConCursore}).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<PosizioniDebitorie> findPosizioniDebitorie(String idA2A, String idDebitore, Integer page,
            String cursor, Integer limit, String sort, String fields, Boolean total) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        boolean modalitaCursore = cursor != null;
        if (modalitaCursore) {
            rifiutaSeIncompatibiliConCursore(sort, total);
        }

        PosizioniDebitorie dto = new PosizioniDebitorie();
        List<PosizioneDebitoriaIndex> risultati;

        if (modalitaCursore) {
            CursorCodec.Cursore decodificato = cursor.isBlank() ? null : CursorCodec.decode(cursor);
            PaginaSenzaConteggio<PosizioneDebitoria> pagina = posizioneDebitoriaService.cercaPerDebitoreDaCursore(
                    idA2A, idDebitore, decodificato == null ? null : decodificato.dataCreazione(),
                    decodificato == null ? null : decodificato.id(), limit);
            risultati = pagina.risultati().stream().map(mapper::toIndexDto).toList();
            if (pagina.haAltriRisultati() && !pagina.risultati().isEmpty()) {
                PosizioneDebitoria ultima = pagina.risultati().get(pagina.risultati().size() - 1);
                dto.setNextCursor(CursorCodec.encode(ultima.getDataCreazione(), ultima.getId()));
            }
        } else {
            int paginaEffettiva = QueryParamUtils.isExplicit(currentRequest, "page") ? page : 1;
            Sort ordinamento = CriteriOrdinamento.parse(sort, CAMPI_ORDINABILI);
            Pageable pageable = OffsetPageRequest.of((long) (paginaEffettiva - 1) * limit, limit, ordinamento);

            Pagination pagination = new Pagination(paginaEffettiva, limit, false);
            if (Boolean.TRUE.equals(total)) {
                PaginaRisultati<PosizioneDebitoria> pagina = posizioneDebitoriaService.cercaPerDebitore(idA2A,
                        idDebitore, pageable);
                risultati = pagina.risultati().stream().map(mapper::toIndexDto).toList();
                pagination.setHasNextPage(pagina.haAltriRisultati());
                pagination.setTotalResults(Math.toIntExact(pagina.numeroRisultatiTotali()));
                pagination.setTotalPages(
                        (int) Math.ceil(pagina.numeroRisultatiTotali() / (double) limit));
            } else {
                PaginaSenzaConteggio<PosizioneDebitoria> pagina = posizioneDebitoriaService
                        .cercaPerDebitoreSenzaConteggio(idA2A, idDebitore, pageable);
                risultati = pagina.risultati().stream().map(mapper::toIndexDto).toList();
                pagination.setHasNextPage(pagina.haAltriRisultati());
            }
            dto.setPagination(pagination);
        }

        dto.setResults(SelezioneCampi.applica(risultati, fields, objectMapper));
        return ResponseEntity.ok(dto);
    }

    /**
     * In modalita' cursore sono incompatibili {@code page}/{@code sort} espliciti e
     * {@code total=true} (stessa regola di govpay-console-api,
     * {@code ListQueryValidator#rejectCursorIncompatible}). {@code page} richiede
     * l'ispezione della query string grezza ({@link QueryParamUtils#isExplicit}) perche' lo
     * YAML gli assegna un default (1): l'argomento del metodo generato vale sempre 1 se
     * assente, indistinguibile da un {@code ?page=1} esplicito. {@code sort}/{@code total}
     * non hanno questo problema ({@code sort} non ha default, {@code total} ha default
     * {@code false}: solo un valore esplicito {@code true} e' possibile).
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
