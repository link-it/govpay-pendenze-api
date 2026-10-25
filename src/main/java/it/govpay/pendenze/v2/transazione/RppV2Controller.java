package it.govpay.pendenze.v2.transazione;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.metrics.ExternalCallMetricsRecorder;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.pendenze.criteri.FiltriRpt;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptConPendenza;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.ricevuta.RicevutaMapper;
import it.govpay.pendenze.ricevuta.RicevutaPdfPayloadMapper;
import it.govpay.pendenze.avviso.StampeClient;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.RicevutaRendicontazioneService;
import it.govpay.pendenze.v2.api.model.EsitoRpp;
import it.govpay.pendenze.v2.api.model.Rpp;
import it.govpay.pendenze.v2.api.model.RppIndex;
import it.govpay.pendenze.v2.api.model.Rpps;
import it.govpay.pendenze.v2.api.rest.TransazioniApi;
import it.govpay.pendenze.v2.ricevuta.RppContenutoMapper;
import it.govpay.pendenze.v2.ricevuta.RppMapper;
import it.govpay.pendenze.web.SelezioneCampi;
import it.govpay.stampe.client.model.Receipt;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link TransazioniApi} per intero: la ricerca {@link #findRpp} e le letture
 * puntuali {@link #getRpp}/{@link #getRpt}/{@link #getRt}.
 *
 * <p>A differenza di {@code PendenzaV2Controller}, qui la chiave della richiesta
 * ({@code idDominio}+{@code iuv}+{@code ccp}) e' esattamente il vincolo fisico della tabella
 * {@code rpt} ({@code unique_rpt_id_transazione}): {@link RptRepository#findByCodDominioAndIuvAndIur}
 * risolve la RPT direttamente, senza passare da {@code DominioRepository} (il
 * {@code idDominio} del percorso e' gia' la colonna fisica {@code cod_dominio}, non una FK
 * numerica da risolvere — vedi Javadoc di classe di {@link Rpt}). Lo stesso vale per il filtro
 * {@code idDominio} di {@link #findRpp}.</p>
 */
@RestController
@RequestMapping("${govpay.pendenze.api.v2.base-path:}")
public class RppV2Controller implements TransazioniApi {

    /** Unico ordinamento di default del legacy ({@code ListaRptDTO.addDefaultSort}). */
    private static final String ORDINAMENTO_DI_DEFAULT = "dataMsgRichiesta";

    /**
     * I 2 campi ordinabili del legacy per questo endpoint ({@code ListaRptDTO}, costruttore:
     * {@code dataRichiesta} → {@code DATA_MSG_RICHIESTA}, {@code stato} → {@code STATO}): nome
     * esterno (quello nell'URL, formato {@code +campo}/{@code -campo}) → proprieta' di
     * {@link Rpt}. Solo questi due, con questi nomi: un client del legacy che ordina per
     * {@code dataRichiesta} deve continuare a funzionare, e qualunque altro nome era gia' un
     * 400 nel legacy.
     */
    private static final Map<String, String> CAMPI_ORDINABILI = Map.of(
            "dataRichiesta", "dataMsgRichiesta",
            "stato", "stato");

    private final RicevutaRendicontazioneService ricevutaRendicontazioneService;
    private final RptRepository rptRepository;
    private final PendenzaRepository pendenzaRepository;
    private final ApplicazioneRepository applicazioneRepository;
    private final RppMapper rppMapper;
    private final RppContenutoMapper rppContenutoMapper;
    private final RicevutaMapper ricevutaMapper;
    private final RicevutaPdfPayloadMapper ricevutaPdfPayloadMapper;
    private final StampeClient stampeClient;
    private final ExternalCallMetricsRecorder externalCallMetricsRecorder;
    private final AclAuthorizer aclAuthorizer;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;
    private final HttpServletResponse currentResponse;

    public RppV2Controller(RicevutaRendicontazioneService ricevutaRendicontazioneService,
            RptRepository rptRepository, PendenzaRepository pendenzaRepository,
            ApplicazioneRepository applicazioneRepository, RppMapper rppMapper,
            RppContenutoMapper rppContenutoMapper, RicevutaMapper ricevutaMapper,
            RicevutaPdfPayloadMapper ricevutaPdfPayloadMapper, StampeClient stampeClient,
            ExternalCallMetricsRecorder externalCallMetricsRecorder, AclAuthorizer aclAuthorizer,
            CurrentApplicazioneService currentApplicazioneService, ObjectMapper objectMapper,
            HttpServletRequest currentRequest, HttpServletResponse currentResponse) {
        this.ricevutaRendicontazioneService = ricevutaRendicontazioneService;
        this.rptRepository = rptRepository;
        this.pendenzaRepository = pendenzaRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.rppMapper = rppMapper;
        this.rppContenutoMapper = rppContenutoMapper;
        this.ricevutaMapper = ricevutaMapper;
        this.ricevutaPdfPayloadMapper = ricevutaPdfPayloadMapper;
        this.stampeClient = stampeClient;
        this.externalCallMetricsRecorder = externalCallMetricsRecorder;
        this.aclAuthorizer = aclAuthorizer;
        this.currentApplicazioneService = currentApplicazioneService;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
        this.currentResponse = currentResponse;
    }

    /**
     * Porting di {@code RppController.rppGET}/{@code RptDAO.listaRpt} del legacy (filtro
     * {@code it.govpay.bd.viste.filters.RptFilter} sulla vista {@code v_rpt_versamenti}) —
     * fedele al comportamento reale, bug compresi (decisione del committente, vedi
     * [[feedback_wrapper_v2_fedelta_legacy]]). Stesso scheletro di paginazione di
     * {@code PendenzaV2Controller#findPendenze}.
     *
     * <p><b>{@code idA2A} e' sempre forzato</b> all'applicazione autenticata corrente, IGNORANDO
     * il valore eventualmente passato come query param: il legacy lo copia nel filtro e poi lo
     * sovrascrive incondizionatamente con l'applicazione del chiamante ("se sei una applicazione
     * allora vedi i pagamenti che hai caricato" — e l'endpoint ammette solo utenze di tipo
     * applicazione), quindi non e' mai stato un filtro aggiuntivo. Stesso trattamento di
     * {@code findPendenze}.</p>
     *
     * <p><b>{@code idPagamento} e' accettato ma non filtra nulla</b> — bug del legacy replicato:
     * il controller lo copia in {@code ListaRptDTO.idPagamento}, ma {@code RptDAO.listaRpt} non
     * lo passa mai al filtro SQL ({@code RptFilter} non ha nemmeno un campo per riceverlo).
     * {@code maxRisultati} (conteggio "capped") e' accettato senza equivalente v3-nativo: il
     * conteggio, quando richiesto, e' sempre esatto.</p>
     *
     * <p>Filtri (operatori del legacy, vedi Javadoc di {@link RptRepository#cercaFiltrate}):
     * {@code idDominio} uguaglianza diretta su {@code Rpt.codDominio} (gia' il codice, nessuna
     * risoluzione verso {@code DominioRepository}: un dominio inesistente da' semplicemente zero
     * risultati); {@code iuv} e {@code ccp} (colonna fisica {@code ccp}, {@code Rpt.iur})
     * case-insensitive "contiene", NON match esatto — entrambi {@code ilike ANYWHERE} nel
     * legacy; {@code idPendenza} esatto; {@code idDebitore} case-insensitive esatto su
     * {@code srcDebitoreIdentificativo}; {@code esitoPagamento} tradotto da
     * {@link #codificaEsito}; {@code dataRptDa}/{@code dataRptA} su {@code dataMsgRichiesta},
     * {@code dataRtDa}/{@code dataRtA} su {@code dataMsgRicevuta}, estremi inclusi.</p>
     *
     * <p>Una RPT/RT non interpretabile durante la costruzione di {@link RppIndex}
     * ({@code RicevutaNonLeggibileException} da {@link RppMapper#toRppIndex}) si propaga al
     * gestore generico di {@code ProblemExceptionHandler} (500), come per {@link #getRpp}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Rpps> findRpp(Integer pagina, Integer risultatiPerPagina, String campi, String ordinamento,
            String idDominio, String iuv, String ccp, String idA2A, String idPendenza, String idDebitore,
            EsitoRpp esitoPagamento, String idPagamento, OffsetDateTime dataRptDa, OffsetDateTime dataRptA,
            OffsetDateTime dataRtDa, OffsetDateTime dataRtA, Boolean metadatiPaginazione, Boolean maxRisultati) {
        aclAuthorizer.richiedeLettura();
        String idA2ACorrente = currentApplicazioneService.get().getCodApplicazione();

        int paginaEffettiva = pagina != null ? pagina : 1;
        int limiteEffettivo = risultatiPerPagina != null ? risultatiPerPagina : 25;
        boolean conMetadati = !Boolean.FALSE.equals(metadatiPaginazione);

        FiltriRpt filtri = new FiltriRpt(idDominio, iuv, ccp, idPendenza, idDebitore, codificaEsito(esitoPagamento),
                dataRptDa, dataRptA, dataRtDa, dataRtA);

        Sort sort = parseOrdinamento(ordinamento);
        Pageable pageable = OffsetPageRequest.of((long) (paginaEffettiva - 1) * limiteEffettivo, limiteEffettivo,
                sort);

        Rpps dto = new Rpps();
        List<RppIndex> risultati;
        if (conMetadati) {
            PaginaRisultati<RptConPendenza> paginaRisultato = ricevutaRendicontazioneService
                    .cercaRptFiltrate(idA2ACorrente, filtri, pageable);
            risultati = paginaRisultato.risultati().stream().map(this::toRppIndex).toList();
            dto.setNumRisultati(Math.toIntExact(paginaRisultato.numeroRisultatiTotali()));
            dto.setNumPagine(
                    Math.max(1, (int) Math.ceil(paginaRisultato.numeroRisultatiTotali() / (double) limiteEffettivo)));
            if (paginaRisultato.haAltriRisultati()) {
                dto.setProssimiRisultati(urlProssimaPagina(paginaEffettiva + 1));
            }
        } else {
            PaginaSenzaConteggio<RptConPendenza> paginaRisultato = ricevutaRendicontazioneService
                    .cercaRptFiltrateSenzaConteggio(idA2ACorrente, filtri, pageable);
            risultati = paginaRisultato.risultati().stream().map(this::toRppIndex).toList();
            if (paginaRisultato.haAltriRisultati()) {
                dto.setProssimiRisultati(urlProssimaPagina(paginaEffettiva + 1));
            }
        }
        dto.setPagina(paginaEffettiva);
        dto.setRisultatiPerPagina(limiteEffettivo);
        dto.setRisultati(SelezioneCampi.applica(risultati, campi, objectMapper));
        return ResponseEntity.ok(dto);
    }

    private RppIndex toRppIndex(RptConPendenza coppia) {
        return rppMapper.toRppIndex(coppia.rpt(), coppia.pendenza());
    }

    /**
     * Traduce l'enum v2 {@link EsitoRpp} nel codice numerico persistito in
     * {@code Rpt.codEsitoPagamento} — stessa tabella del legacy ({@code RppController.rppGET}
     * → {@code it.govpay.model.Rpt.EsitoPagamento.getCodifica()}). Vive qui, non nella
     * libreria: per {@code govpay-common-pendenze} quel codice resta un intero non interpretato.
     *
     * @return {@code null} se {@code esito} e' assente dalla richiesta (nessun filtro)
     */
    private static Integer codificaEsito(EsitoRpp esito) {
        if (esito == null) {
            return null;
        }
        return switch (esito) {
            case ESEGUITO -> 0;
            case NON_ESEGUITO -> 1;
            case ESEGUITO_PARZIALE -> 2;
            case DECORRENZA -> 3;
            case DECORRENZA_PARZIALE -> 4;
            case IN_CORSO -> 100;
            case RIFIUTATO -> 101;
        };
    }

    /**
     * Stesso formato e stesso trattamento di {@code PendenzaV2Controller#parseOrdinamento}
     * ({@code +campo}/{@code -campo} separati da virgole, campo sconosciuto → 400), sui campi di
     * {@link #CAMPI_ORDINABILI}. Assente → {@code dataMsgRichiesta DESC} (stesso default del
     * legacy).
     */
    private Sort parseOrdinamento(String ordinamento) {
        if (ordinamento == null || ordinamento.isBlank()) {
            return Sort.by(Sort.Direction.DESC, ORDINAMENTO_DI_DEFAULT);
        }
        List<Sort.Order> ordini = new ArrayList<>();
        for (String token : ordinamento.split(",")) {
            String pulito = token.trim();
            if (pulito.isEmpty()) {
                continue;
            }
            Sort.Direction direzione = Sort.Direction.ASC;
            String nomeCampo = pulito;
            if (pulito.startsWith("-")) {
                direzione = Sort.Direction.DESC;
                nomeCampo = pulito.substring(1);
            } else if (pulito.startsWith("+")) {
                nomeCampo = pulito.substring(1);
            }
            String percorso = CAMPI_ORDINABILI.get(nomeCampo);
            if (percorso == null) {
                throw new ValidazioneNonSuperataException("campo di ordinamento [" + nomeCampo
                        + "] non riconosciuto: ammessi " + CAMPI_ORDINABILI.keySet());
            }
            ordini.add(new Sort.Order(direzione, percorso));
        }
        return ordini.isEmpty() ? Sort.by(Sort.Direction.DESC, ORDINAMENTO_DI_DEFAULT) : Sort.by(ordini);
    }

    /** Stesso calcolo di {@code PendenzaV2Controller#urlProssimaPagina}. */
    private String urlProssimaPagina(int nuovaPagina) {
        String queryString = currentRequest.getQueryString();
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(currentRequest.getRequestURI());
        if (queryString != null) {
            builder.query(queryString);
        }
        return builder.replaceQueryParam("pagina", nuovaPagina).build().toUriString();
    }

    /**
     * Risponde {@code 200}, non il {@code 201} dichiarato dallo YAML (sia il nostro che quello
     * legacy): verificato che l'implementazione legacy stessa risponde sempre
     * {@code Status.OK}, il {@code 201} non e' mai stato realmente emesso da nessun client reale
     * — bug di spec mai sanato, non un comportamento da replicare (vedi
     * {@code docs/proposta-modello-nativo-v3.md} per la decisione del committente: fedelta' al
     * comportamento reale della v2, non al testo dello spec quando diverge).
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Rpp> getRpp(String idDominio, String iuv, String ccp) {
        aclAuthorizer.richiedeLettura();
        Rpt rpt = trovaRpt(idDominio, iuv, ccp);
        Pendenza pendenza = trovaPendenza(rpt);
        verificaProprietario(pendenza);
        return ResponseEntity.ok(rppMapper.toRpp(rpt, pendenza));
    }

    /**
     * Scrive la risposta direttamente su {@link HttpServletResponse} (stesso pattern di
     * {@code PendenzaController#getStampaPendenza}, v3), invece di lasciare la negoziazione del
     * content-type alla produces-list generata ({@code application/xml}/{@code json}/
     * {@code problem+json}): il legacy non applica mai la negoziazione standard per questi due
     * endpoint, sceglie a mano in base all'header {@code Accept} con un default implicito a XML
     * (anche se {@code Accept} e' assente o non riconosciuto, mai un 406) — fedelta' richiesta
     * dal committente, vedi [[feedback_wrapper_v2_fedelta_legacy]].
     *
     * <p>{@code produces = MediaType.ALL_VALUE} qui sovrascrive deliberatamente la
     * produces-list generata dallo YAML ({@code xml}/{@code json}/{@code problem+json}): senza
     * questo, la negoziazione automatica di Spring rifiuterebbe con 406 un {@code Accept} che
     * non corrisponde a nessuno dei tre (es. {@code text/plain}) ancora prima di entrare nel
     * metodo — esattamente il 406 che il legacy non emette mai per questo endpoint.</p>
     */
    @Override
    @Transactional(readOnly = true)
    @RequestMapping(method = RequestMethod.GET, value = TransazioniApi.PATH_GET_RPT, produces = MediaType.ALL_VALUE)
    public ResponseEntity<Object> getRpt(String idDominio, String iuv, String ccp) {
        aclAuthorizer.richiedeLettura();
        Rpt rpt = trovaRpt(idDominio, iuv, ccp);
        Pendenza pendenza = trovaPendenza(rpt);
        verificaProprietario(pendenza);

        if (accettaJson(currentRequest.getHeader(HttpHeaders.ACCEPT))) {
            scriviJson(rppContenutoMapper.toRpt(rpt, pendenza));
        } else {
            scriviXml(rppContenutoMapper.toRptXml(rpt, pendenza));
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Stesso pattern di {@link #getRpt}, con un terzo content-type ({@code application/pdf},
     * verificato per primo — stesso ordine di priorita' del legacy) che riusa la catena di
     * stampa gia' costruita per {@code GET .../ricevute/{iur}/stampa} (v3): qui la RT si
     * identifica da {@code idDominio}+{@code iuv}+{@code ccp} invece che da
     * {@code idA2A}+{@code idPendenza}+{@code iur}, ma {@link RicevutaPdfPayloadMapper#toReceipt}
     * prende le entita' gia' risolte, non le chiavi — nessuna duplicazione verso
     * govpay-stampe-api.
     *
     * <p>404 unico se la RPT non esiste O se esiste ma non ha ancora una RT acquisita (stesso
     * trattamento del legacy, {@code RptDAO.leggiRt}: nessuna distinzione).</p>
     *
     * <p>{@code produces = MediaType.ALL_VALUE}: stesso motivo di {@link #getRpt}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    @RequestMapping(method = RequestMethod.GET, value = TransazioniApi.PATH_GET_RT, produces = MediaType.ALL_VALUE)
    public ResponseEntity<org.springframework.core.io.Resource> getRt(String idDominio, String iuv, String ccp) {
        aclAuthorizer.richiedeLettura();
        Rpt rpt = trovaRpt(idDominio, iuv, ccp);
        Pendenza pendenza = trovaPendenza(rpt);
        verificaProprietario(pendenza);
        if (rpt.getXmlRt() == null) {
            throw new RisorsaNonTrovataException("la ricevuta [idDominio:" + idDominio + ", iuv:" + iuv + ", ccp:"
                    + ccp + "] non e' ancora stata acquisita");
        }

        String accept = currentRequest.getHeader(HttpHeaders.ACCEPT);
        if (accettaPdf(accept)) {
            streamRtPdf(pendenza, rpt);
        } else if (accettaJson(accept)) {
            scriviJson(rppContenutoMapper.toRt(rpt, pendenza));
        } else {
            scriviXml(rppContenutoMapper.toRtXml(rpt, pendenza));
        }
        return ResponseEntity.ok().build();
    }

    private void streamRtPdf(Pendenza pendenza, Rpt rpt) {
        it.govpay.pendenze.api.model.Ricevuta ricevuta = ricevutaMapper.toRicevuta(rpt);
        Receipt payload = ricevutaPdfPayloadMapper.toReceipt(pendenza, rpt, ricevuta);
        currentResponse.setContentType(MediaType.APPLICATION_PDF_VALUE);
        currentResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + rpt.getIur() + ".pdf\"");
        try {
            var output = currentResponse.getOutputStream();
            externalCallMetricsRecorder.record("stampe", "receipt", () -> stampeClient.streamReceipt(payload, output));
            currentResponse.flushBuffer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void scriviJson(Object contenuto) {
        currentResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            objectMapper.writeValue(currentResponse.getOutputStream(), contenuto);
            currentResponse.flushBuffer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void scriviXml(byte[] xml) {
        currentResponse.setContentType(MediaType.APPLICATION_XML_VALUE);
        try {
            currentResponse.getOutputStream().write(xml);
            currentResponse.flushBuffer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Stesso controllo naive del legacy ({@code RppController}: {@code accept.toLowerCase()
     * .contains(...)}, non la negoziazione quality-aware usata altrove in v3-nativo) —
     * deliberato: replica il comportamento reale, non una versione "corretta".
     */
    private static boolean accettaJson(String accept) {
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json");
    }

    private static boolean accettaPdf(String accept) {
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("application/pdf");
    }

    private Rpt trovaRpt(String idDominio, String iuv, String ccp) {
        return rptRepository.findByCodDominioAndIuvAndIur(idDominio, iuv, ccp)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna richiesta di pagamento con idDominio ["
                        + idDominio + "], iuv [" + iuv + "] e ccp [" + ccp + "]"));
    }

    private Pendenza trovaPendenza(Rpt rpt) {
        return pendenzaRepository.findById(rpt.getIdVersamento())
                .orElseThrow(() -> new IllegalStateException("pendenza [id:" + rpt.getIdVersamento()
                        + "] della RPT [id:" + rpt.getId() + "] non trovata: incoerenza del dato, il vincolo di "
                        + "integrita' referenziale dovrebbe impedirlo"));
    }

    /**
     * Stesso controllo "filtro sull'applicazione" di
     * {@code PendenzaV2Controller#getPendenzaByAvviso} — fatto dopo la risoluzione (l'idA2A
     * proprietario non e' nella richiesta, solo idDominio+iuv+ccp).
     */
    private void verificaProprietario(Pendenza pendenza) {
        String codApplicazioneProprietaria = applicazioneRepository.findById(pendenza.getIdApplicazione())
                .map(ApplicazioneEntity::getCodApplicazione)
                .orElseThrow(() -> new IllegalStateException(
                        "applicazione [id:" + pendenza.getIdApplicazione() + "] non trovata in anagrafica"));
        currentApplicazioneService.verificaIdA2A(codApplicazioneProprietaria);
    }
}
