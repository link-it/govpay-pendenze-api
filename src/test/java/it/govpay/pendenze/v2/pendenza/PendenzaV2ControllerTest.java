package it.govpay.pendenze.v2.pendenza;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

import java.io.IOException;
import java.io.InputStream;

/**
 * Verifica {@code GET /v2/pendenze/{idA2A}/{idPendenza}} a livello di integrazione (contesto
 * Spring completo, DB H2 con lo schema reale) — stesso impianto di
 * {@code it.govpay.pendenze.pendenza.PendenzaControllerTest} (v3), duplicato invece di
 * condiviso per lo stesso motivo gia' documentato altrove nel progetto per classi di test di
 * controller analoghe (nessuna superclasse comune tra i due controller). I dati sono creati
 * tramite il vero endpoint di scrittura v3 ({@code POST /posizioni-debitorie/{idA2A}}): e'
 * la stessa entity {@code Pendenza}/tabella {@code versamenti} sotto entrambe le API, non
 * serve (e non esiste) un path di scrittura v2 per questo test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PendenzaV2ControllerTest.BasicAuthDiDefaultConfig.class)
class PendenzaV2ControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";

    @TestConfiguration
    static class BasicAuthDiDefaultConfig {
        @Bean
        MockMvcBuilderCustomizer basicAuthDiDefaultCustomizer() {
            return builder -> builder.defaultRequest(
                    MockMvcRequestBuilders.get("/").with(httpBasic(PRINCIPAL, PASSWORD)));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicazioneRepository applicazioneRepository;

    @Autowired
    private UtenzaRepository utenzaRepository;

    @Autowired
    private AclRepository aclRepository;

    @Autowired
    private GovpayPasswordEncoder passwordEncoder;

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private TipoVersamentoRepository tipoVersamentoRepository;

    @Autowired
    private TipoVersamentoDominioRepository tipoVersamentoDominioRepository;

    @Autowired
    private TipoTributoRepository tipoTributoRepository;

    @Autowired
    private TributoRepository tributoRepository;

    @Autowired
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    @Autowired
    private RptRepository rptRepository;

    @Autowired
    private PendenzaRepository pendenzaRepository;

    @Autowired
    private AllegatoRepository allegatoRepository;

    private TipoVersamentoEntity tipoVersamento;
    private TipoTributoEntity tipoTributo;

    @BeforeEach
    void creaAnagrafiche() {
        UtenzaEntity utenza = creaUtenza(PRINCIPAL, PASSWORD);

        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione("A2A-TEST")
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build());

        DominioEntity dominio = dominioRepository.save(DominioEntity.builder()
                .codDominio("12345678901")
                .ragioneSociale("Comune di Test")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        tipoVersamento = new TipoVersamentoEntity();
        tipoVersamento.setCodTipoVersamento("DIRITTI_SEGRETERIA");
        tipoVersamento.setDescrizione("Diritti di segreteria");
        tipoVersamento.setAbilitato(true);
        tipoVersamento.setPagaTerzi(false);
        tipoVersamento.setBoAbilitato(false);
        tipoVersamento.setPagAbilitato(false);
        tipoVersamento.setAvvMailPromAvvAbilitato(false);
        tipoVersamento.setAvvMailPromRicAbilitato(false);
        tipoVersamento.setAvvMailPromScadAbilitato(false);
        tipoVersamento.setAvvAppIoPromAvvAbilitato(false);
        tipoVersamento.setAvvAppIoPromRicAbilitato(false);
        tipoVersamento.setAvvAppIoPromScadAbilitato(false);
        tipoVersamentoRepository.save(tipoVersamento);

        tipoTributo = new TipoTributoEntity();
        tipoTributo.setCodTributo("DIRITTI_SEGRETERIA");
        tipoTributo.setDescrizione("Diritti di segreteria");
        tipoTributoRepository.save(tipoTributo);

        TipoVersamentoDominioEntity override = new TipoVersamentoDominioEntity();
        override.setTipoVersamento(tipoVersamento);
        override.setDominio(dominio);
        tipoVersamentoDominioRepository.save(override);

        TributoEntity tributo = new TributoEntity();
        tributo.setAbilitato(true);
        tributo.setDominio(dominio);
        tributo.setTipoTributo(tipoTributo);
        tributoRepository.save(tributo);
    }

    @AfterEach
    void pulisci() {
        allegatoRepository.deleteAll();
        rptRepository.deleteAll();
        posizioneDebitoriaRepository.deleteAll();
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        tributoRepository.deleteAll();
        tipoTributoRepository.deleteAll();
        dominioRepository.deleteAll();
        applicazioneRepository.deleteAll();
        aclRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    private UtenzaEntity creaUtenza(String principal, String password) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
        utenza.setAutorizzazioneDominiStar(false);
        utenza.setPassword(passwordEncoder.encode(password));
        utenza = utenzaRepository.save(utenza);

        AclEntity acl = new AclEntity();
        acl.setServizio("API Pendenze");
        acl.setDiritti("RW");
        acl.setIdUtenza(utenza.getId());
        aclRepository.save(acl);

        return utenza;
    }

    /**
     * Crea una seconda applicazione (utenza+ACL+{@code ApplicazioneEntity}), per i test che
     * verificano il filtro sull'applicazione proprietaria (vedi Javadoc di
     * {@code PendenzaV2Controller#getPendenzaByAvviso}).
     */
    private void creaAltraApplicazione(String principal, String password) {
        UtenzaEntity altraUtenza = creaUtenza(principal, password);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(altraUtenza.getId())
                .build());
    }

    /**
     * Sovrascrive per una singola richiesta le credenziali di default — vedi l'omologo in
     * {@code PosizioneDebitoriaControllerTest} per il motivo (bug del lead, 2026-09-29:
     * {@code .with(httpBasic(...))} da solo AGGIUNGE un secondo header {@code Authorization}
     * invece di sostituirlo).
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor comeApplicazione(
            String principal, String password) {
        return request -> {
            request.removeHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
            return httpBasic(principal, password).postProcessRequest(request);
        };
    }

    /** Stesso endpoint di scrittura v3 usato da {@code PendenzaControllerTest} — vedi Javadoc di classe. */
    private String creaPosizioneConPendenza(String idPosizioneDebitoria, String codDominio, String idPendenza)
            throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test wrapper v2",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y",
                                           "anagrafica": "Mario Rossi" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "%s",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-%s", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(idPosizioneDebitoria, codDominio, idPendenza, idPendenza);

        String risposta = mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(risposta, "$.opzioniPagamento[0].pendenze[0].numeroAvviso");
    }

    private static byte[] fixtureRt(String nome) throws IOException {
        try (InputStream in = PendenzaV2ControllerTest.class.getResourceAsStream("/rt/" + nome)) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("GET /v2/pendenze/{idA2A}/{idPendenza} con rpp popolato: rpt/rt serializzati nel formato nativo "
            + "(conversione V1 disabilitata di default) — non la rappresentazione interna di JsonNode "
            + "(nodeType/containerNode/array), il vero contenuto (receiptId e campi reali)")
    void getPendenzaConRppPopolatoSerializzaRtNelFormatoReale() throws Exception {
        creaPosizioneConPendenza("pos-v2-get-rpp-1", "12345678901", "pendenza-v2-get-rpp-1");

        Long idApplicazione = applicazioneRepository.findByCodApplicazione("A2A-TEST").orElseThrow().getId();
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, "pendenza-v2-get-rpp-1")
                .orElseThrow();

        // xml_rpt assente (nessuna riga in rendicontazioni): la RPT viene ricostruita dalla RT,
        // esercitando anche RicostruttoreRichiestaPagamento, non solo la lettura diretta.
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv("IUV900000003");
        rpt.setIur("RT900000003");
        rpt.setCodDominio("12345678901");
        rpt.setVersione("SANP_240");
        rpt.setStato("RT_ACCETTATA_PA");
        rpt.setDescrizioneStato("Pagamento eseguito con successo");
        rpt.setXmlRt(fixtureRt("rt-v2-ok.xml"));
        rptRepository.save(rpt);

        mockMvc.perform(get("/v2/pendenze/{idA2A}/{idPendenza}", "A2A-TEST", "pendenza-v2-get-rpp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpp.length()").value(1))
                .andExpect(jsonPath("$.rpp[0].stato").value("RT_ACCETTATA_PA"))
                .andExpect(jsonPath("$.rpp[0].dettaglioStato").value("Pagamento eseguito con successo"))
                // Contenuto reale (CtReceipt nativo): se la serializzazione fosse ancora rotta
                // (Jackson 3 che scrive le proprieta' interne di un JsonNode Jackson 2), questo
                // campo sarebbe assente e "nodeType"/"containerNode" comparirebbero al suo posto.
                .andExpect(jsonPath("$.rpp[0].rt.receiptId").value("RT900000003"))
                .andExpect(jsonPath("$.rpp[0].rt.creditorReferenceId").value("IUV900000003"))
                .andExpect(jsonPath("$.rpp[0].rt.nodeType").doesNotExist())
                // La RPT e' ricostruita dalla RT (xml_rpt assente): formato nativo CtPaymentPA.
                .andExpect(jsonPath("$.rpp[0].rpt.creditorReferenceId").value("IUV900000003"))
                .andExpect(jsonPath("$.rpp[0].rpt.nodeType").doesNotExist())
                .andExpect(jsonPath("$.rpp[0].pendenza.idPendenza").value("pendenza-v2-get-rpp-1"));
    }

    @Test
    @DisplayName("GET /v2/pendenze/{idA2A}/{idPendenza} restituisce il dettaglio nel formato v2, "
            + "comprensivo di dominio/soggettoPagatore/voci, rpp e allegati vuoti (nessuno creato)")
    void getPendenzaRestituisceIlDettaglioNelFormatoV2() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-v2-get-1", "12345678901", "pendenza-v2-get-1");

        mockMvc.perform(get("/v2/pendenze/{idA2A}/{idPendenza}", "A2A-TEST", "pendenza-v2-get-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idA2A").value("A2A-TEST"))
                .andExpect(jsonPath("$.idPendenza").value("pendenza-v2-get-1"))
                .andExpect(jsonPath("$.dominio.idDominio").value("12345678901"))
                .andExpect(jsonPath("$.stato").value("NON_ESEGUITA"))
                .andExpect(jsonPath("$.numeroAvviso").value(numeroAvviso))
                .andExpect(jsonPath("$.iuvAvviso").value(notNullValue()))
                .andExpect(jsonPath("$.tipo").value("dovuto"))
                .andExpect(jsonPath("$.soggettoPagatore.identificativo").value("FRRPLA90C41H501Y"))
                .andExpect(jsonPath("$.voci.length()").value(1))
                .andExpect(jsonPath("$.voci[0].idVocePendenza").value("voce-pendenza-v2-get-1"))
                .andExpect(jsonPath("$.voci[0].codEntrata").value("DIRITTI_SEGRETERIA"))
                .andExpect(jsonPath("$.rpp.length()").value(0))
                .andExpect(jsonPath("$.allegati.length()").value(0));
    }

    @Test
    @DisplayName("GET /v2/pendenze/{idA2A}/{idPendenza} restituisce 404 se la pendenza non esiste")
    void getPendenzaRestituisce404SeNonEsiste() throws Exception {
        mockMvc.perform(get("/v2/pendenze/{idA2A}/{idPendenza}", "A2A-TEST", "pendenza-v2-inesistente"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /v2/pendenze/byAvviso/{idDominio}/{numeroAvviso} trova la pendenza senza idA2A nella richiesta")
    void getPendenzaByAvvisoRestituisceIlDettaglio() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-v2-avviso-1", "12345678901", "pendenza-v2-avviso-1");

        mockMvc.perform(get("/v2/pendenze/byAvviso/{idDominio}/{numeroAvviso}", "12345678901", numeroAvviso))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idA2A").value("A2A-TEST"))
                .andExpect(jsonPath("$.idPendenza").value("pendenza-v2-avviso-1"))
                .andExpect(jsonPath("$.numeroAvviso").value(numeroAvviso));
    }

    @Test
    @DisplayName("GET /v2/pendenze/byAvviso/{idDominio}/{numeroAvviso} restituisce 409, non 500, se il numeroAvviso "
            + "corrisponde a piu' di una pendenza (caso reale: dati migrati da v2 dove le rate di un piano "
            + "rateale con avviso cumulativo condividono legittimamente lo stesso NAV, nessun vincolo DB lo "
            + "impedisce) — scelta esplicita in deroga alla fedelta' al legacy, che ha lo stesso problema mai "
            + "gestito")
    void getPendenzaByAvvisoRestituisce409SeNumeroAvvisoCorrispondeAPiuDiUnaPendenza() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-v2-avviso-condiviso-1", "12345678901",
                "pendenza-v2-avviso-condiviso-1");

        // Seconda pendenza "migrata" (senza opzionePagamento, come una riga v2 reale) con lo
        // stesso idDominio+numeroAvviso della prima — persistita direttamente, bypassando le
        // validazioni del servizio: e' esattamente cosi' che questa situazione puo' esistere in
        // produzione (mai attraverso il percorso di scrittura v3-nativo, che la rifiuta).
        Long idApplicazione = applicazioneRepository.findByCodApplicazione("A2A-TEST").orElseThrow().getId();
        Long idDominio = dominioRepository.findByCodDominio("12345678901").orElseThrow().getId();
        Long idTipoPendenza = tipoVersamentoDominioRepository
                .findByDominioIdAndTipoVersamentoId(idDominio, tipoVersamento.getId()).orElseThrow().getId();
        Pendenza pendenzaMigrata = new Pendenza();
        pendenzaMigrata.setIdApplicazione(idApplicazione);
        pendenzaMigrata.setIdDominio(idDominio);
        pendenzaMigrata.setIdPendenza("pendenza-v2-avv-cond-migrata");
        pendenzaMigrata.setIdTipoPendenza(idTipoPendenza);
        pendenzaMigrata.setIdTipoVersamento(tipoVersamento.getId());
        pendenzaMigrata.setImporto(10.00);
        pendenzaMigrata.setNumeroAvviso(numeroAvviso);
        pendenzaMigrata.setDataCreazione(java.time.OffsetDateTime.now());
        pendenzaMigrata.setDataUltimoAggiornamento(java.time.OffsetDateTime.now());
        pendenzaRepository.save(pendenzaMigrata);

        mockMvc.perform(get("/v2/pendenze/byAvviso/{idDominio}/{numeroAvviso}", "12345678901", numeroAvviso))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET /v2/pendenze/byAvviso/{idDominio}/{numeroAvviso} restituisce 404 se numeroAvviso non esiste")
    void getPendenzaByAvvisoRestituisce404SeNonEsiste() throws Exception {
        // Formalmente valido (prima cifra 0|1|2|3), ma nessuna pendenza reale lo usa.
        mockMvc.perform(get("/v2/pendenze/byAvviso/{idDominio}/{numeroAvviso}", "12345678901", "199999999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /v2/pendenze/byAvviso/{idDominio}/{numeroAvviso} restituisce 400 se la prima cifra "
            + "non e' l'AuxDigit pagoPA valido (0|1|2|3), anche se la ricerca non troverebbe nulla comunque")
    void getPendenzaByAvvisoRestituisce400SePrimaCifraNonValida() throws Exception {
        mockMvc.perform(get("/v2/pendenze/byAvviso/{idDominio}/{numeroAvviso}", "12345678901", "999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /v2/pendenze/byAvviso/{idDominio}/{numeroAvviso} restituisce 403 se la pendenza "
            + "appartiene a un'altra applicazione (filtro sull'applicazione, come il legacy)")
    void getPendenzaByAvvisoRestituisce403SeApplicazioneDiversa() throws Exception {
        creaAltraApplicazione("A2A-ALTRO", "altra-password");

        String body = """
                {
                  "codDocumento": "pos-v2-avviso-altro-1",
                  "idDominio": "12345678901",
                  "descrizione": "test altra applicazione",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y",
                                           "anagrafica": "Mario Rossi" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-v2-avviso-altro-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-altro-1", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;
        String risposta = mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-ALTRO")
                        .with(comeApplicazione("A2A-ALTRO", "altra-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String numeroAvviso = JsonPath.read(risposta, "$.opzioniPagamento[0].pendenze[0].numeroAvviso");

        mockMvc.perform(get("/v2/pendenze/byAvviso/{idDominio}/{numeroAvviso}", "12345678901", numeroAvviso))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(403));
    }
}
