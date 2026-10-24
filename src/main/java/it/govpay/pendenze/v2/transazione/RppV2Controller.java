package it.govpay.pendenze.v2.transazione;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.metrics.ExternalCallMetricsRecorder;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.ricevuta.RicevutaMapper;
import it.govpay.pendenze.ricevuta.RicevutaPdfPayloadMapper;
import it.govpay.pendenze.avviso.StampeClient;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.v2.api.model.Rpp;
import it.govpay.pendenze.v2.api.rest.TransazioniApi;
import it.govpay.pendenze.v2.ricevuta.RppContenutoMapper;
import it.govpay.pendenze.v2.ricevuta.RppMapper;
import it.govpay.stampe.client.model.Receipt;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link TransazioniApi}: per ora {@link #getRpp}/{@link #getRpt}/{@link #getRt},
 * {@link #findRpp} resta sul default generato (501) — stesso approccio incrementale di
 * {@code it.govpay.pendenze.v2.pendenza.PendenzaV2Controller}.
 *
 * <p>A differenza di {@code PendenzaV2Controller}, qui la chiave della richiesta
 * ({@code idDominio}+{@code iuv}+{@code ccp}) e' esattamente il vincolo fisico della tabella
 * {@code rpt} ({@code unique_rpt_id_transazione}): {@link RptRepository#findByCodDominioAndIuvAndIur}
 * risolve la RPT direttamente, senza passare da {@code DominioRepository} (il
 * {@code idDominio} del percorso e' gia' la colonna fisica {@code cod_dominio}, non una FK
 * numerica da risolvere — vedi Javadoc di classe di {@link Rpt}).</p>
 */
@RestController
@RequestMapping("${govpay.pendenze.api.v2.base-path:}")
public class RppV2Controller implements TransazioniApi {

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

    public RppV2Controller(RptRepository rptRepository, PendenzaRepository pendenzaRepository,
            ApplicazioneRepository applicazioneRepository, RppMapper rppMapper,
            RppContenutoMapper rppContenutoMapper, RicevutaMapper ricevutaMapper,
            RicevutaPdfPayloadMapper ricevutaPdfPayloadMapper, StampeClient stampeClient,
            ExternalCallMetricsRecorder externalCallMetricsRecorder, AclAuthorizer aclAuthorizer,
            CurrentApplicazioneService currentApplicazioneService, ObjectMapper objectMapper,
            HttpServletRequest currentRequest, HttpServletResponse currentResponse) {
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
