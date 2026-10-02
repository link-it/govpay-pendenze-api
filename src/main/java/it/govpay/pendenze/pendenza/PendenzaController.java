package it.govpay.pendenze.pendenza;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.common.metrics.ExternalCallMetricsRecorder;
import it.govpay.pendenze.api.model.Avviso;
import it.govpay.pendenze.api.model.LinguaSecondaria;
import it.govpay.pendenze.api.model.Pagination;
import it.govpay.pendenze.api.model.PendenzaIndex;
import it.govpay.pendenze.api.model.Pendenze;
import it.govpay.pendenze.api.model.RicevutaIndex;
import it.govpay.pendenze.api.model.Ricevute;
import it.govpay.pendenze.api.rest.PendenzeApi;
import it.govpay.pendenze.avviso.AvvisoMapper;
import it.govpay.pendenze.avviso.AvvisoMbtException;
import it.govpay.pendenze.avviso.AvvisoPdfPayloadMapper;
import it.govpay.pendenze.avviso.StampeClient;
import it.govpay.pendenze.criteri.CriteriOrdinamento;
import it.govpay.pendenze.criteri.CursorCodec;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.posizionedebitoria.PosizioneDebitoriaMapper;
import it.govpay.pendenze.repository.RicevutaElenco;
import it.govpay.pendenze.ricevuta.RicevutaMapper;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.service.RicevutaRendicontazioneService;
import it.govpay.pendenze.web.NotAcceptableMediaTypeException;
import it.govpay.pendenze.web.QueryParamUtils;
import it.govpay.pendenze.web.SelezioneCampi;
import it.govpay.stampe.client.model.PaymentNotice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final AvvisoMapper avvisoMapper;
    private final AvvisoPdfPayloadMapper avvisoPdfPayloadMapper;
    private final StampeClient stampeClient;
    private final ExternalCallMetricsRecorder externalCallMetricsRecorder;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final RicevutaRendicontazioneService ricevutaRendicontazioneService;
    private final RicevutaMapper ricevutaMapper;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;
    private final HttpServletResponse currentResponse;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final AclAuthorizer aclAuthorizer;

    public PendenzaController(PosizioneDebitoriaMapper mapper, AvvisoMapper avvisoMapper,
            AvvisoPdfPayloadMapper avvisoPdfPayloadMapper, StampeClient stampeClient,
            ExternalCallMetricsRecorder externalCallMetricsRecorder,
            PosizioneDebitoriaService posizioneDebitoriaService,
            RicevutaRendicontazioneService ricevutaRendicontazioneService, RicevutaMapper ricevutaMapper,
            ObjectMapper objectMapper, HttpServletRequest currentRequest, HttpServletResponse currentResponse,
            CurrentApplicazioneService currentApplicazioneService, AclAuthorizer aclAuthorizer) {
        this.mapper = mapper;
        this.avvisoMapper = avvisoMapper;
        this.avvisoPdfPayloadMapper = avvisoPdfPayloadMapper;
        this.stampeClient = stampeClient;
        this.externalCallMetricsRecorder = externalCallMetricsRecorder;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.ricevutaRendicontazioneService = ricevutaRendicontazioneService;
        this.ricevutaMapper = ricevutaMapper;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
        this.currentResponse = currentResponse;
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
     * Negoziazione del content-type fatta a mano (non tramite il meccanismo automatico di
     * Spring MVC, dichiarato solo a fini documentali dal {@code produces} generato): questo
     * endpoint alterna JSON e PDF sullo stesso path, cosa che un {@code ResponseEntity<Avviso>}
     * a tipizzazione fissa non potrebbe esprimere per il ramo PDF (streaming di byte grezzi) —
     * stesso pattern di {@code AvvisiController}/{@code AvvisoService.chooseContentType} in v2
     * e govpay-console-api. {@code 406} per un Accept che non e' compatibile ne' con JSON ne'
     * con PDF.
     *
     * <p>Ramo PDF: {@code isPendenzaMbt} blocca con 422 le pendenze con Marca da Bollo
     * Telematica (l'avviso PDF non le si applica, vedi Javadoc di
     * {@link AvvisoPdfPayloadMapper#isPendenzaMbt}); altrimenti costruisce il payload e fa
     * streaming diretto della risposta di govpay-stampe sulla {@link HttpServletResponse},
     * senza passare per gli {@code HttpMessageConverter} (che non saprebbero gestire un body
     * PDF qui) — la {@code return null} segnala al chiamante ({@code getStampaPendenza} stesso)
     * che risposta e header sono gia' stati scritti.</p>
     *
     * <p>{@code causaleTradotta} va in {@code second_language.title} dello schema di
     * govpay-stampe-api (quel campo e' l'oggetto del pagamento, non un'etichetta fissa del
     * documento — vedi Javadoc di {@code AvvisoPdfPayloadMapper.buildSecondLanguage}),
     * {@code informativaImporto} nel campo {@code informativa_importo} su
     * {@code PaymentNotice}, {@code informativaImportoTradotta} in
     * {@code second_language.informativa_importo}: tutti e tre hanno effetto solo sul PDF, sul
     * ramo JSON non esiste un campo corrispondente in {@link Avviso}, quindi restano rifiutati
     * solo li'.</p>
     *
     * <p>{@code causaleTradotta} e' invece obbligatorio (400, non piu' un 502 dovuto al rifiuto
     * di govpay-stampe) quando {@code linguaSecondaria} e' specificato: {@code second_language}
     * e' {@code bilinguism}/{@code language}/{@code title} richiesti nello schema di
     * govpay-stampe — un avviso bilingue senza causale tradotta sarebbe un documento bilingue
     * incompleto (l'oggetto del pagamento resterebbe non tradotto), non un caso da accettare
     * silenziosamente.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Avviso> getStampaPendenza(String idA2A, String idPendenza,
            LinguaSecondaria linguaSecondaria, String causaleTradotta, String informativaImporto,
            String informativaImportoTradotta) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);

        if (MediaType.APPLICATION_JSON.equals(risolviContentType())) {
            rifiutaPersonalizzazioniPdfSuJson(causaleTradotta, informativaImporto, informativaImportoTradotta);
            Pendenza pendenza = trovaPendenza(idA2A, idPendenza);
            return ResponseEntity.ok(avvisoMapper.toAvviso(pendenza));
        }
        Pendenza pendenza = trovaPendenza(idA2A, idPendenza);
        streamAvvisoPdf(pendenza, linguaSecondaria, causaleTradotta, informativaImporto, informativaImportoTradotta);
        return ResponseEntity.ok().build();
    }

    private Pendenza trovaPendenza(String idA2A, String idPendenza) {
        return posizioneDebitoriaService.trovaPendenzaPerIdentificativo(idA2A, idPendenza)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna pendenza con idPendenza [" + idPendenza
                        + "] per idA2A [" + idA2A + "]"));
    }

    private void streamAvvisoPdf(Pendenza pendenza, LinguaSecondaria linguaSecondaria, String causaleTradotta,
            String informativaImporto, String informativaImportoTradotta) {
        rifiutaBilingueSenzaCausaleTradotta(linguaSecondaria, causaleTradotta);
        if (AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)) {
            throw new AvvisoMbtException(
                    "Avviso PDF non disponibile per pendenze con Marca da Bollo Telematica.");
        }
        PaymentNotice payload = avvisoPdfPayloadMapper.toPaymentNotice(pendenza, linguaSecondaria, causaleTradotta,
                informativaImporto, informativaImportoTradotta);
        currentResponse.setContentType(MediaType.APPLICATION_PDF_VALUE);
        currentResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + pendenza.getNumeroAvviso() + ".pdf\"");
        try {
            var output = currentResponse.getOutputStream();
            externalCallMetricsRecorder.record("stampe", "payment_notice",
                    () -> stampeClient.streamPaymentNotice(payload, output));
            currentResponse.flushBuffer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final List<MediaType> TIPI_SUPPORTATI = List.of(MediaType.APPLICATION_JSON,
            MediaType.APPLICATION_PDF);

    /**
     * Risolve il content-type da produrre rispettando la qualita' (q) dell'Accept (RFC 7231
     * §5.3.2). {@code MediaType.isCompatibleWith} da solo confronta solo tipo/sottotipo e
     * ignora {@code q}: un Accept come {@code "application/pdf, application/json;q=0"}
     * esplicitamente esclude JSON (q=0, non solo "meno preferito").
     *
     * <p>Scartare subito le entry con q=0 non basta: con un Accept come
     * {@code "application/json;q=0, [wildcard generico];q=1"} un filtro upfront scarterebbe
     * solo la entry JSON, lasciando il wildcard (q=1) "resuscitare" JSON — esattamente
     * l'esclusione specifica che si voleva rispettare. Serve invece, per ciascun formato
     * supportato, la qualita' della corrispondenza PIU' SPECIFICA nell'Accept (un match
     * esatto, es. {@code application/json}, prevale su un match di sottotipo wildcard, es.
     * {@code application/*}, che a sua volta prevale sul wildcard generico) — solo dopo si
     * sceglie il formato con la qualita' cosi' determinata piu' alta tra quelli supportati.
     * Nessun Accept (assente/vuoto) → JSON, stesso default di prima.</p>
     */
    private MediaType risolviContentType() {
        String accept = currentRequest.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            return MediaType.APPLICATION_JSON;
        }
        List<MediaType> richiesti = MediaType.parseMediaTypes(accept);
        return TIPI_SUPPORTATI.stream()
                .filter(tipo -> qualitaPiuSpecifica(tipo, richiesti) > 0)
                .max(Comparator.comparingDouble(tipo -> qualitaPiuSpecifica(tipo, richiesti)))
                .orElseThrow(() -> new NotAcceptableMediaTypeException(
                        "Accept header non compatibile: i content-type supportati da questo endpoint sono "
                                + "application/json e application/pdf."));
    }

    /**
     * Qualita' della corrispondenza piu' specifica in {@code richiesti} per {@code tipo}: tra
     * le entry compatibili (match esatto, sottotipo wildcard o generico), vince quella con la
     * specificita' maggiore — un'esclusione (q=0) su un match specifico non va "resuscitata"
     * da un wildcard meno specifico con q maggiore. {@code 0.0} (nessuna corrispondenza) se
     * {@code tipo} non e' richiesto affatto.
     */
    private static double qualitaPiuSpecifica(MediaType tipo, List<MediaType> richiesti) {
        return richiesti.stream()
                .filter(mt -> mt.isCompatibleWith(tipo))
                .max(Comparator.comparingInt(PendenzaController::specificita))
                .map(MediaType::getQualityValue)
                .orElse(0.0);
    }

    /** Match esatto (2) prevale su sottotipo wildcard, es. {@code application/*} (1), su {@code *}/{@code *} (0). */
    private static int specificita(MediaType mt) {
        if (!mt.isWildcardType() && !mt.isWildcardSubtype()) {
            return 2;
        }
        return mt.isWildcardType() ? 0 : 1;
    }

    /**
     * {@code causaleTradotta} e' obbligatorio quando si richiede l'avviso bilingue: senza, il
     * {@code second_language.title} inviato a govpay-stampe risulterebbe {@code null}, campo
     * richiesto dal suo schema ({@code bilinguism}/{@code language}/{@code title}) — senza
     * questo controllo a monte, il 400 di govpay-stampe arriverebbe al chiamante come 502
     * generico invece di un 400 chiaro e azionabile.
     */
    private void rifiutaBilingueSenzaCausaleTradotta(LinguaSecondaria linguaSecondaria, String causaleTradotta) {
        boolean bilingue = linguaSecondaria != null && linguaSecondaria != LinguaSecondaria.FALSE;
        if (bilingue && causaleTradotta == null) {
            throw new ValidazioneNonSuperataException(
                    "il parametro 'causaleTradotta' e' obbligatorio quando 'linguaSecondaria' e' specificato: "
                            + "un avviso bilingue senza causale tradotta non e' un documento valido.");
        }
    }

    private void rifiutaPersonalizzazioniPdfSuJson(String causaleTradotta, String informativaImporto,
            String informativaImportoTradotta) {
        if (causaleTradotta != null || informativaImporto != null || informativaImportoTradotta != null) {
            throw new ValidazioneNonSuperataException(
                    "i parametri 'causaleTradotta'/'informativaImporto'/'informativaImportoTradotta' non hanno "
                            + "effetto sulla variante application/json di questo endpoint: si applicano solo al "
                            + "PDF.");
        }
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

    /** Unico campo ordinabile per le ricevute: {@code RicevutaIndex} ha solo iur/tipo/data. */
    private static final Map<String, String> CAMPI_ORDINABILI_RICEVUTE = Map.of("data", "dataMsgRicevuta");

    /**
     * Stesso pattern page/cursor/limit/sort/fields/total di {@link #findPendenze} (vedi il suo
     * Javadoc per le due modalita' di paginazione), qui su {@link RicevutaRendicontazioneService}
     * invece che su {@link PosizioneDebitoriaService}.
     *
     * <p>{@code total=false} qui non evita davvero un {@code COUNT}:
     * {@link RicevutaRendicontazioneService#cercaRicevute} non ha una variante "senza
     * conteggio" — le ricevute di una pendenza sono tipicamente pochissime (di norma una, al
     * piu' poche in caso di solleciti/rinegoziazioni), il beneficio di evitare il
     * {@code COUNT} sarebbe trascurabile, stesso principio gia' applicato a
     * {@link #findPendenze} per IUV/NAV. Il parametro resta comunque offerto per uniformita'
     * con lo standard di paginazione condiviso: senza {@code total=true} semplicemente non si
     * popolano {@code totalResults}/{@code totalPages} nella risposta.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Ricevute> findRicevutePendenza(String idA2A, String idPendenza, Integer page,
            String cursor, Integer limit, String sort, String fields, Boolean total) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        boolean modalitaCursore = cursor != null;
        if (modalitaCursore) {
            rifiutaSeIncompatibiliConCursore(sort, total);
        }
        Pendenza pendenza = trovaPendenza(idA2A, idPendenza);

        Ricevute dto = new Ricevute();
        List<RicevutaIndex> risultati;

        if (modalitaCursore) {
            CursorCodec.Cursore decodificato = cursor.isBlank() ? null : CursorCodec.decode(cursor);
            PaginaSenzaConteggio<RicevutaElenco> pagina = ricevutaRendicontazioneService.cercaRicevuteDaCursore(
                    pendenza.getId(), decodificato == null ? null : decodificato.dataCreazione(),
                    decodificato == null ? null : decodificato.id(), limit);
            risultati = pagina.risultati().stream().map(ricevutaMapper::toRicevutaIndexDto).toList();
            if (pagina.haAltriRisultati() && !pagina.risultati().isEmpty()) {
                RicevutaElenco ultima = pagina.risultati().get(pagina.risultati().size() - 1);
                dto.setNextCursor(CursorCodec.encode(ultima.getDataMsgRicevuta(), ultima.getId()));
            }
        } else {
            int paginaEffettiva = QueryParamUtils.isExplicit(currentRequest, "page") ? page : 1;
            Sort ordinamento = CriteriOrdinamento.parse(sort, CAMPI_ORDINABILI_RICEVUTE);
            Pageable pageable = OffsetPageRequest.of((long) (paginaEffettiva - 1) * limit, limit, ordinamento);

            Pagination pagination = new Pagination(paginaEffettiva, limit, false);
            PaginaRisultati<RicevutaElenco> pagina = ricevutaRendicontazioneService.cercaRicevute(pendenza.getId(),
                    pageable);
            risultati = pagina.risultati().stream().map(ricevutaMapper::toRicevutaIndexDto).toList();
            pagination.setHasNextPage(pagina.haAltriRisultati());
            if (Boolean.TRUE.equals(total)) {
                pagination.setTotalResults(Math.toIntExact(pagina.numeroRisultatiTotali()));
                pagination.setTotalPages((int) Math.ceil(pagina.numeroRisultatiTotali() / (double) limit));
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
