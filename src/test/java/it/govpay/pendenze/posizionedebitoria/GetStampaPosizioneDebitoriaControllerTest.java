package it.govpay.pendenze.posizionedebitoria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.sun.net.httpserver.HttpServer;

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
import it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifica {@code GET /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/stampa}
 * ({@code getStampaPosizioneDebitoria}, issue govpay-pendenze-api#5, §38
 * {@code proposta-modello-nativo-v3.md}) a livello di integrazione — stesso impianto di
 * {@code GetAvvisiDocumentoControllerTest} (contesto completo, H2, stub JDK {@link HttpServer}
 * di govpay-stampe). Le posizioni vengono create tramite {@code POST /posizioni-debitorie},
 * compreso il caso con più di un'{@code opzioniPagamento} nello stesso body (opzioni
 * alternative). Lo stub conserva l'ultimo payload ricevuto, per verificare
 * {@code full}/{@code instalments}/{@code options} effettivamente inviati.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GetStampaPosizioneDebitoriaControllerTest.BasicAuthDiDefaultConfig.class)
class GetStampaPosizioneDebitoriaControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";
    private static final String ID_DOMINIO = "12345678901";
    private static final String PATH = "/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/stampa";

    @TestConfiguration
    static class BasicAuthDiDefaultConfig {
        @Bean
        MockMvcBuilderCustomizer basicAuthDiDefaultCustomizer() {
            return builder -> builder.defaultRequest(
                    MockMvcRequestBuilders.get("/").with(httpBasic(PRINCIPAL, PASSWORD)));
        }
    }

    private static HttpServer stampeStub;
    private static final byte[] FINTO_PDF = "%PDF-finto-per-test".getBytes(StandardCharsets.UTF_8);
    private static final AtomicReference<String> ULTIMO_PAYLOAD = new AtomicReference<>();
    private static final AtomicReference<String> ULTIMO_PATH = new AtomicReference<>();
    private static final AtomicInteger CHIAMATE = new AtomicInteger();

    @BeforeAll
    static void avviaStampeStub() throws IOException {
        stampeStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                ULTIMO_PAYLOAD.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
            ULTIMO_PATH.set(exchange.getRequestURI().getPath());
            CHIAMATE.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, FINTO_PDF.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(FINTO_PDF);
            }
        };
        stampeStub.createContext("/standard", handler);
        stampeStub.createContext("/cds_violation", handler);
        stampeStub.start();
    }

    @AfterAll
    static void fermaStampeStub() {
        stampeStub.stop(0);
    }

    @DynamicPropertySource
    static void registraBaseUrlStampe(DynamicPropertyRegistry registry) {
        registry.add("app.stampe.base-url",
                () -> "http://localhost:" + stampeStub.getAddress().getPort());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private PosizioneDebitoriaService posizioneDebitoriaService;

    @BeforeEach
    void creaAnagrafiche() {
        ULTIMO_PAYLOAD.set(null);
        ULTIMO_PATH.set(null);
        CHIAMATE.set(0);

        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(PRINCIPAL);
        utenza.setPrincipalOriginale(PRINCIPAL);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
        utenza.setAutorizzazioneDominiStar(false);
        utenza.setPassword(passwordEncoder.encode(PASSWORD));
        utenza = utenzaRepository.save(utenza);

        AclEntity acl = new AclEntity();
        acl.setServizio("API Pendenze");
        acl.setDiritti("RW");
        acl.setIdUtenza(utenza.getId());
        aclRepository.save(acl);

        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(PRINCIPAL)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build());

        DominioEntity dominio = dominioRepository.save(DominioEntity.builder()
                .codDominio(ID_DOMINIO)
                .ragioneSociale("Comune di Test")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
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

        TipoTributoEntity tipoTributo = new TipoTributoEntity();
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

    @Test
    @DisplayName("un'unica opzione con una sola pendenza: 200, 'full' (stesso criterio di getStampaPendenza)")
    void unaSolaOpzioneConUnaPendenza() throws Exception {
        String idPosizioneDebitoria = creaConUnaOpzione("pos-stampa-full", 1);

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + idPosizioneDebitoria + ".pdf\""))
                .andExpect(content().bytes(FINTO_PDF));

        JsonNode payload = ultimoPayload();
        assertNotNull(payload.get("full"));
        assertFalse(payload.get("full").isNull());
        assertTrue(payload.get("options") == null || payload.get("options").isEmpty());
    }

    @Test
    @DisplayName("un'unica opzione con piu' rate: 200, 'instalments' cumulativo, nessuna 'options'")
    void unaSolaOpzioneConPiuRate() throws Exception {
        String idPosizioneDebitoria = creaConUnaOpzione("pos-stampa-instalments", 3);

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertTrue(payload.get("full") == null || payload.get("full").isNull());
        assertEquals(3, payload.get("instalments").size());
        assertTrue(payload.get("options") == null || payload.get("options").isEmpty());
    }

    @Test
    @DisplayName("due opzioni alternative ancora disponibili: 200, un solo PDF con 'options' (2 elementi), "
            + "nessun 'full'/'instalments' a livello di documento")
    void dueOpzioniAlternativeDisponibili() throws Exception {
        String idPosizioneDebitoria = creaConDueOpzioniAlternative("pos-stampa-opzioni", 1, 2);

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertTrue(payload.get("full") == null || payload.get("full").isNull());
        assertTrue(payload.get("instalments") == null || payload.get("instalments").isEmpty());
        JsonNode options = payload.get("options");
        assertEquals(2, options.size());
        assertNotNull(options.get(0).get("full"));
        assertEquals(2, options.get(1).get("instalments").size());
    }

    @Test
    @DisplayName("un'opzione pagata parzialmente (ATTIVATA) con rate ancora pagabili e l'alternativa "
            + "ANNULLATA automaticamente: 200, 'full' della sola rata restante, nessuna 'options' "
            + "(l'opzione annullata non vi compare)")
    void opzioneAnnullataEsclusaDallaStampa() throws Exception {
        String idPosizioneDebitoria = creaConDueOpzioniAlternative("pos-stampa-annullata", 2, 1);

        posizioneDebitoriaService.registraEsitoPendenza(PRINCIPAL, "pos-stampa-annullata-A-r1",
                StatoPendenza.ESEGUITO);

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertNotNull(payload.get("full"));
        assertFalse(payload.get("full").isNull());
        assertTrue(payload.get("options") == null || payload.get("options").isEmpty());
    }

    @Test
    @DisplayName("coppia SOLUZIONE_UNICA_ENTRO/OLTRE entrambe ancora pagabili: 200, un solo PDF con "
            + "'reducedPayments' a due elementi, nessun 'options'")
    void coppiaEntroOltreProduceReducedPaymentsSuUnaSolaPagina() throws Exception {
        String idPosizioneDebitoria = creaConEntroEOltre("pos-stampa-soglie");

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertTrue(payload.get("full") == null || payload.get("full").isNull());
        assertTrue(payload.get("options") == null || payload.get("options").isEmpty());
        JsonNode reducedPayments = payload.get("reduced_payments");
        assertEquals(2, reducedPayments.size());
        assertEquals("ENTRO", reducedPayments.get(0).get("threshold_type").asText());
        assertEquals("OLTRE", reducedPayments.get(1).get("threshold_type").asText());
    }

    @Test
    @DisplayName("coppia SOLUZIONE_UNICA_RIDOTTO/SCONTATO (violazione CdS) entrambe ancora pagabili: 200, "
            + "chiamata a POST /cds_violation invece di /standard")
    void coppiaRidottoScontatoChiamaCdsViolation() throws Exception {
        String idPosizioneDebitoria = creaConRidottoEScontato("pos-stampa-cds");

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        assertEquals("/cds_violation", ULTIMO_PATH.get());
        JsonNode payload = ultimoPayload();
        assertEquals(70.0, payload.get("reduced_amount").get("amount").asDouble());
        assertEquals(100.0, payload.get("discounted_amount").get("amount").asDouble());
    }

    @Test
    @DisplayName("nessuna pendenza pagabile in nessuna opzione: 422, nessuna chiamata a govpay-stampe")
    void nessunaPendenzaPagabile422() throws Exception {
        String idPosizioneDebitoria = creaConUnaOpzione("pos-stampa-422", 1);
        posizioneDebitoriaService.registraEsitoPendenza(PRINCIPAL, "pos-stampa-422-r1", StatoPendenza.ESEGUITO);

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(422));
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("posizione debitoria inesistente: 404")
    void posizioneInesistente404() throws Exception {
        mockMvc.perform(get(PATH, PRINCIPAL,
                        IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, "non-esiste"))
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Accept diverso da application/pdf (o assente): 406")
    void acceptNonPdf406() throws Exception {
        String idPosizioneDebitoria = creaConUnaOpzione("pos-stampa-406", 1);

        mockMvc.perform(get(PATH, PRINCIPAL, idPosizioneDebitoria).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("pendenza con Marca da Bollo Telematica: 422, indipendentemente dal numero di opzioni")
    void mbt422() throws Exception {
        String body = """
                {
                  "codDocumento": "pos-stampa-mbt",
                  "idDominio": "%s",
                  "descrizione": "test MBT",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pos-stampa-mbt-pendenza",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "BOLLO", "idVocePendenza": "voce-mbt", "importo": 16.00,
                                  "descrizione": "test", "tipoBollo": "01", "hashDocumento": "aGFzaA==",
                                  "provinciaResidenza": "RM", "tassonomia": "9/0101002IM/" } ]
                    } ]
                  } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH, PRINCIPAL,
                        IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, "pos-stampa-mbt"))
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422));
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("debitore senza anagrafica: 422")
    void anagraficaAssente422() throws Exception {
        String body = """
                {
                  "codDocumento": "pos-stampa-no-anagrafica",
                  "idDominio": "%s",
                  "descrizione": "test anagrafica assente",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pos-stampa-no-anagrafica-pendenza",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-no-anagrafica",
                                  "importo": 16.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH, PRINCIPAL,
                        IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, "pos-stampa-no-anagrafica"))
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422));
    }

    /** Un'unica opzione {@code SOLUZIONE_UNICA}/piano rateale con {@code numeroRate} pendenze. */
    private String creaConUnaOpzione(String codDocumento, int numeroRate) throws Exception {
        StringBuilder pendenze = new StringBuilder();
        for (int i = 1; i <= numeroRate; i++) {
            if (i > 1) {
                pendenze.append(',');
            }
            String campoRata = numeroRate > 1 ? ("\"rata\": " + i + ",") : "";
            pendenze.append("""
                    {
                      "idPendenza": "%s-r%d",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      %s
                      "importo": 10.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-voce%d",
                                  "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    }
                    """.formatted(codDocumento, i, campoRata, codDocumento, i));
        }
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test stampa posizione",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y", "anagrafica": "Paola Ferrari" } ],
                  "opzioniPagamento": [ { "tipologia": "%s", "pendenze": [ %s ] } ]
                }
                """.formatted(codDocumento, ID_DOMINIO, numeroRate > 1 ? "PIANO_RATEALE" : "SOLUZIONE_UNICA",
                pendenze);

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, codDocumento);
    }

    /**
     * Due opzioni alternative nello stesso body di creazione: "A" con {@code numeroRateA} rate
     * (idPendenza {@code <codDocumento>-A-r<n>}), "B" con {@code numeroRateB} rate (idPendenza
     * {@code <codDocumento>-B-r<n>}), entrambe DISPONIBILE.
     */
    private String creaConDueOpzioniAlternative(String codDocumento, int numeroRateA, int numeroRateB)
            throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test stampa posizione con opzioni alternative",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y", "anagrafica": "Paola Ferrari" } ],
                  "opzioniPagamento": [
                    { "tipologia": "%s", "pendenze": [ %s ] },
                    { "tipologia": "%s", "pendenze": [ %s ] }
                  ]
                }
                """.formatted(codDocumento, ID_DOMINIO,
                numeroRateA > 1 ? "PIANO_RATEALE" : "SOLUZIONE_UNICA", pendenzeOpzione(codDocumento, "A", numeroRateA),
                numeroRateB > 1 ? "PIANO_RATEALE" : "SOLUZIONE_UNICA", pendenzeOpzione(codDocumento, "B", numeroRateB));

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, codDocumento);
    }

    /** Coppia {@code SOLUZIONE_UNICA_RIDOTTO}/{@code _SCONTATO} (violazione CdS) sullo stesso debito. */
    private String creaConRidottoEScontato(String codDocumento) throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test stampa violazione CdS",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y", "anagrafica": "Paola Ferrari" } ],
                  "opzioniPagamento": [
                    { "tipologia": "SOLUZIONE_UNICA_RIDOTTO", "pendenze": [ {
                        "idPendenza": "%s-ridotto", "idTipoPendenza": "DIRITTI_SEGRETERIA", "importo": 70.00,
                        "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-ridotto-voce",
                                    "importo": 70.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                      } ] },
                    { "tipologia": "SOLUZIONE_UNICA_SCONTATO", "pendenze": [ {
                        "idPendenza": "%s-scontato", "idTipoPendenza": "DIRITTI_SEGRETERIA", "importo": 100.00,
                        "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-scontato-voce",
                                    "importo": 100.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                      } ] }
                  ]
                }
                """.formatted(codDocumento, ID_DOMINIO, codDocumento, codDocumento, codDocumento, codDocumento);

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, codDocumento);
    }

    /** Coppia {@code SOLUZIONE_UNICA_ENTRO}/{@code _OLTRE} sullo stesso debito, {@code giorni=5}. */
    private String creaConEntroEOltre(String codDocumento) throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test stampa soglie ENTRO/OLTRE",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y", "anagrafica": "Paola Ferrari" } ],
                  "opzioniPagamento": [
                    { "tipologia": "SOLUZIONE_UNICA_ENTRO", "giorni": 5, "pendenze": [ {
                        "idPendenza": "%s-ridotta", "idTipoPendenza": "DIRITTI_SEGRETERIA", "importo": 7.00,
                        "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-ridotta-voce",
                                    "importo": 7.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                      } ] },
                    { "tipologia": "SOLUZIONE_UNICA_OLTRE", "giorni": 5, "pendenze": [ {
                        "idPendenza": "%s-intera", "idTipoPendenza": "DIRITTI_SEGRETERIA", "importo": 10.00,
                        "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-intera-voce",
                                    "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                      } ] }
                  ]
                }
                """.formatted(codDocumento, ID_DOMINIO, codDocumento, codDocumento, codDocumento, codDocumento);

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, codDocumento);
    }

    private static String pendenzeOpzione(String codDocumento, String etichettaOpzione, int numeroRate) {
        StringBuilder pendenze = new StringBuilder();
        for (int i = 1; i <= numeroRate; i++) {
            if (i > 1) {
                pendenze.append(',');
            }
            String campoRata = numeroRate > 1 ? ("\"rata\": " + i + ",") : "";
            pendenze.append("""
                    {
                      "idPendenza": "%s-%s-r%d",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      %s
                      "importo": 10.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-%s-voce%d",
                                  "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    }
                    """.formatted(codDocumento, etichettaOpzione, i, campoRata, codDocumento, etichettaOpzione, i));
        }
        return pendenze.toString();
    }

    private JsonNode ultimoPayload() {
        String payload = ULTIMO_PAYLOAD.get();
        assertNotNull(payload, "nessuna chiamata a govpay-stampe");
        return objectMapper.readTree(payload);
    }
}
