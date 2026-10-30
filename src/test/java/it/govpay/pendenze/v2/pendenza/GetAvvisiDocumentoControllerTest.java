package it.govpay.pendenze.v2.pendenza;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifica {@code GET /v2/documenti/{idDominio}/{numeroDocumento}/avvisi}
 * ({@code getAvvisiDocumento}) a livello di integrazione — stesso impianto di
 * {@link AddPendenzaControllerTest} (contesto completo, H2, stub JDK {@link HttpServer} di
 * govpay-stampe). I documenti vengono creati tramite {@code addPendenza}, cioe' dallo stesso
 * percorso di scrittura che li produce in esercizio. Lo stub conserva l'ultimo payload
 * ricevuto, per verificare {@code full}/{@code instalments} effettivamente inviati.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GetAvvisiDocumentoControllerTest.BasicAuthDiDefaultConfig.class)
class GetAvvisiDocumentoControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";
    private static final String ID_DOMINIO = "12345678901";
    private static final String PATH = "/v2/documenti/{idDominio}/{numeroDocumento}/avvisi";

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
    private PendenzaRepository pendenzaRepository;

    @Autowired
    private RptRepository rptRepository;

    @Autowired
    private AllegatoRepository allegatoRepository;

    private Long idApplicazione;

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

        ApplicazioneEntity applicazione = applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(PRINCIPAL)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build());
        idApplicazione = applicazione.getId();

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

    @Test
    @DisplayName("documento con una sola pendenza (soluzione unica): 200, PDF a rata unica ('full'), "
            + "titolo la descrizione del documento (mai la causale della pendenza, anche con una sola rata)")
    void documentoConUnaSolaPendenza() throws Exception {
        creaPendenzaSenzaRata("pend-su-1", "DOC-SU-1");

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-1").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ID_DOMINIO + "_DOC-SU-1.pdf\""))
                .andExpect(content().bytes(FINTO_PDF));

        JsonNode payload = ultimoPayload();
        assertNotNull(payload.get("full"));
        assertFalse(payload.get("full").isNull());
        assertTrue(instalmentNumbers(payload).isEmpty());
        assertEquals("Documento di test", payload.get("title").asString());
    }

    @Test
    @DisplayName("piano rateale di 3 rate: 'instalments' con 3 elementi ordinati per numero rata, 'full' assente")
    void pianoRatealeProduceInstalments() throws Exception {
        creaPianoRateale("DOC-PR-1", 3);

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-PR-1").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(content().bytes(FINTO_PDF));

        JsonNode payload = ultimoPayload();
        assertTrue(payload.get("full") == null || payload.get("full").isNull());
        assertEquals(List.of(1, 2, 3), instalmentNumbers(payload));
        for (JsonNode instalment : payload.get("instalments")) {
            assertFalse(instalment.get("notice_number").asString().isBlank());
            assertFalse(instalment.get("qrcode").asString().isBlank());
        }
    }

    @Test
    @DisplayName("piano rateale con una sola rata persistita: 'full', non 'instalments' (la scelta segue la "
            + "cardinalita', non la tipologia dell'opzione)")
    void pianoRatealeConUnaSolaRataProduceFull() throws Exception {
        creaPianoRateale("DOC-PR-UNA", 1);

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-PR-UNA").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertNotNull(payload.get("full"));
        assertFalse(payload.get("full").isNull());
        assertTrue(instalmentNumbers(payload).isEmpty());
    }

    @Test
    @DisplayName("soluzione unica con piu' di una pendenza pagabile: 'instalments', un solo documento cumulativo "
            + "come nel legacy (AvvisoPagamento#getBytesAvvisoDocumento)")
    void soluzioneUnicaConPiuPendenzeProduceInstalments() throws Exception {
        creaSoluzioneUnicaMultipla("DOC-SU-MULTI", 2);

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-MULTI").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertTrue(payload.get("full") == null || payload.get("full").isNull());
        assertEquals(2, instalmentNumbers(payload).size());
    }

    @Test
    @DisplayName("rata gia' ESEGUITA: esclusa dall'avviso cumulativo")
    void rataEseguitaEsclusa() throws Exception {
        creaPianoRateale("DOC-PR-2", 3);
        Pendenza seconda = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, "DOC-PR-2-r2")
                .orElseThrow();
        seconda.setStato(StatoPendenza.ESEGUITO);
        pendenzaRepository.save(seconda);

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-PR-2").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        assertEquals(List.of(1, 3), instalmentNumbers(ultimoPayload()));
    }

    @Test
    @DisplayName("tutte le rate gia' eseguite: 422, nessuna chiamata a govpay-stampe")
    void nessunaRataPagabile422() throws Exception {
        creaPendenzaSenzaRata("pend-su-2", "DOC-SU-2");
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, "pend-su-2")
                .orElseThrow();
        pendenza.setStato(StatoPendenza.ESEGUITO);
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-2").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("numeriAvviso non filtra (bug legacy replicato): tutte le rate pagabili restano nell'avviso")
    void numeriAvvisoNonFiltra() throws Exception {
        creaPianoRateale("DOC-PR-3", 2);
        String navPrimaRata = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, "DOC-PR-3-r1")
                .orElseThrow().getNumeroAvviso();

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-PR-3").param("numeriAvviso", navPrimaRata)
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        assertEquals(List.of(1, 2), instalmentNumbers(ultimoPayload()));
    }

    @Test
    @DisplayName("numeriAvviso con prima cifra non AuxDigit: 400, stessa validazione IuvUtils.toIuv del legacy")
    void numeriAvvisoFormatoNonValido400() throws Exception {
        creaPendenzaSenzaRata("pend-su-3", "DOC-SU-3");

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-3").param("numeriAvviso", "912345678901234567")
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("due opzioni alternative (non soglia) entrambe ancora pagabili: 400, questo wrapper gestisce "
            + "solo un'opzione pagabile per documento")
    void dueOpzioniAlternativeNonSogliaAncoraPagabili400() throws Exception {
        creaViaV3Nativo("DOC-ALT-400", """
                [
                  { "tipologia": "SOLUZIONE_UNICA", "pendenze": [ %s ] },
                  { "tipologia": "PIANO_RATEALE", "pendenze": [ %s, %s ] }
                ]
                """.formatted(pendenzaV3("DOC-ALT-400-A", null),
                pendenzaV3("DOC-ALT-400-B-r1", 1), pendenzaV3("DOC-ALT-400-B-r2", 2)));

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-ALT-400").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isBadRequest());
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("coppia SOLUZIONE_UNICA_ENTRO/OLTRE entrambe ancora pagabili: 200, un solo avviso con "
            + "'reduced_payments' a due elementi, non 400")
    void coppiaEntroOltreProduceReducedPaymentsInveceDi400() throws Exception {
        creaViaV3Nativo("DOC-SOGLIE", """
                [
                  { "tipologia": "SOLUZIONE_UNICA_ENTRO", "giorni": 5, "pendenze": [ %s ] },
                  { "tipologia": "SOLUZIONE_UNICA_OLTRE", "giorni": 5, "pendenze": [ %s ] }
                ]
                """.formatted(pendenzaV3("DOC-SOGLIE-ridotta", null), pendenzaV3("DOC-SOGLIE-intera", null)));

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SOGLIE").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertTrue(payload.get("full") == null || payload.get("full").isNull());
        JsonNode reducedPayments = payload.get("reduced_payments");
        assertEquals(2, reducedPayments.size());
        assertEquals("ENTRO", reducedPayments.get(0).get("threshold_type").asString());
        assertEquals("OLTRE", reducedPayments.get(1).get("threshold_type").asString());
    }

    @Test
    @DisplayName("sola opzione SOLUZIONE_UNICA_RIDOTTO (creata da questo stesso wrapper, senza il suo "
            + "SCONTATO), nessuna coppia completa: 200, 'full' su /standard, non /cds_violation")
    void solaOpzioneRidottoSenzaScontatoProduceFull() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-cds-solo-ridotto")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConSoglia("pend-cds-solo-ridotto", "DOC-CDS-SOLO", "RIDOTTO", 70.00)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-CDS-SOLO").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        assertEquals("/standard", ULTIMO_PATH.get());
        JsonNode payload = ultimoPayload();
        assertNotNull(payload.get("full"));
        assertFalse(payload.get("full").isNull());
    }

    @Test
    @DisplayName("coppia SOLUZIONE_UNICA_RIDOTTO/SCONTATO (violazione CdS) entrambe ancora pagabili: 200, "
            + "chiamata a POST /cds_violation invece di /standard")
    void coppiaRidottoScontatoChiamaCdsViolation() throws Exception {
        creaViaV3Nativo("DOC-CDS", """
                [
                  { "tipologia": "SOLUZIONE_UNICA_RIDOTTO", "pendenze": [ %s ] },
                  { "tipologia": "SOLUZIONE_UNICA_SCONTATO", "pendenze": [ %s ] }
                ]
                """.formatted(pendenzaV3("DOC-CDS-ridotto", null), pendenzaV3("DOC-CDS-scontato", null)));

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-CDS").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        assertEquals("/cds_violation", ULTIMO_PATH.get());
    }

    /** Creazione diretta con {@code POST /posizioni-debitorie/{idA2A}} (v3-nativo). */
    private void creaViaV3Nativo(String codDocumento, String opzioniPagamentoJson) throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "%s",
                  "descrizione": "test getAvvisiDocumento con piu' opzioni",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" } ],
                  "opzioniPagamento": %s
                }
                """.formatted(codDocumento, ID_DOMINIO, opzioniPagamentoJson);
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private static String pendenzaV3(String idPendenza, Integer rata) {
        String campoRata = rata == null ? "" : "\"rata\": " + rata + ",";
        return """
                {
                  "idPendenza": "%s",
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  %s
                  "importo": 10.00,
                  "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s-voce",
                              "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(idPendenza, campoRata, idPendenza);
    }

    @Test
    @DisplayName("documento inesistente: 404")
    void documentoInesistente404() throws Exception {
        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-NON-ESISTE").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("dominio inesistente: 404")
    void dominioInesistente404() throws Exception {
        mockMvc.perform(get(PATH, "99999999999", "DOC-NON-ESISTE").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("linguaSecondaria diversa da false: 422, nessuna chiamata a govpay-stampe")
    void linguaSecondaria422() throws Exception {
        creaPendenzaSenzaRata("pend-su-4", "DOC-SU-4");

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-4").param("linguaSecondaria", "en")
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("linguaSecondaria=false: avviso solo in italiano, 200")
    void linguaSecondariaFalse200() throws Exception {
        creaPendenzaSenzaRata("pend-su-5", "DOC-SU-5");

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-5").param("linguaSecondaria", "false")
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());
        JsonNode secondLanguage = ultimoPayload().get("second_language");
        assertTrue(secondLanguage == null || secondLanguage.isNull());
    }

    @Test
    @DisplayName("linguaSecondaria con causaleTradotta persistita su proprieta: 200, second_language popolato")
    void linguaSecondariaConProprietaPersistitaProduceSecondLanguage() throws Exception {
        String proprieta = """
                { "linguaSecondariaCausale": "Translated reason", "informativaImportoAvviso": "nota",
                  "linguaSecondariaInformativaImportoAvviso": "note" }
                """;
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-su-bil-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConProprieta("pend-su-bil-1", "DOC-SU-BIL-1", proprieta)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-BIL-1").param("linguaSecondaria", "en")
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());

        JsonNode payload = ultimoPayload();
        assertEquals("nota", payload.get("informativa_importo").asString());
        JsonNode secondLanguage = payload.get("second_language");
        assertNotNull(secondLanguage);
        assertEquals("Translated reason", secondLanguage.get("title").asString());
        assertEquals("note", secondLanguage.get("informativa_importo").asString());
    }

    @Test
    @DisplayName("linguaSecondaria su piano rateale con piu' rate pagabili: 422, nessuna chiamata a govpay-stampe")
    void linguaSecondariaSuPianoRatealeConPiuRatePagabili422() throws Exception {
        creaPianoRateale("DOC-PR-BIL-1", 2);

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-PR-BIL-1").param("linguaSecondaria", "en")
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("linguaSecondaria sulla coppia ENTRO/OLTRE: 422, nessuna chiamata a govpay-stampe")
    void linguaSecondariaSuCoppiaEntroOltre422() throws Exception {
        creaViaV3Nativo("DOC-SOGLIE-BIL-1", """
                [
                  { "tipologia": "SOLUZIONE_UNICA_ENTRO", "giorni": 5, "pendenze": [ %s ] },
                  { "tipologia": "SOLUZIONE_UNICA_OLTRE", "giorni": 5, "pendenze": [ %s ] }
                ]
                """.formatted(pendenzaV3("DOC-SOGLIE-BIL-1-ridotta", null),
                pendenzaV3("DOC-SOGLIE-BIL-1-intera", null)));

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SOGLIE-BIL-1").param("linguaSecondaria", "en")
                .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableContent());
        assertEquals(0, CHIAMATE.get());
    }

    @Test
    @DisplayName("Accept diverso da application/pdf (o assente): 406, come il legacy")
    void acceptNonPdf406() throws Exception {
        creaPendenzaSenzaRata("pend-su-6", "DOC-SU-6");

        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-6").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());
        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-6").accept(MediaType.ALL))
                .andExpect(status().isNotAcceptable());
        mockMvc.perform(get(PATH, ID_DOMINIO, "DOC-SU-6"))
                .andExpect(status().isNotAcceptable());
        assertEquals(0, CHIAMATE.get());
    }

    private void creaPendenzaSenzaRata(String idPendenza, String identificativoDocumento) throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, idPendenza)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo(idPendenza, identificativoDocumento, null)))
                .andExpect(status().isCreated());
    }

    /** Rate con {@code idPendenza} {@code <identificativo>-r<n>}, n da 1. */
    private void creaPianoRateale(String identificativoDocumento, int numeroRate) throws Exception {
        for (int rata = 1; rata <= numeroRate; rata++) {
            String idPendenza = identificativoDocumento + "-r" + rata;
            mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, idPendenza)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo(idPendenza, identificativoDocumento, rata)))
                    .andExpect(status().isCreated());
        }
    }

    /**
     * Pendenze con {@code idPendenza} {@code <identificativo>-su<n>}, n da 1, tutte aggiunte
     * allo stesso documento SENZA mai dichiarare {@code rata}: l'opzione resta
     * {@code SOLUZIONE_UNICA} (la tipologia e' decisa alla creazione e non cambia mai, §31) ma
     * accumula piu' di una pendenza, possibile in v3 (§3.3, nessun limite di cardinalita').
     */
    private void creaSoluzioneUnicaMultipla(String identificativoDocumento, int numeroPendenze) throws Exception {
        for (int i = 1; i <= numeroPendenze; i++) {
            String idPendenza = identificativoDocumento + "-su" + i;
            mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, idPendenza)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(corpo(idPendenza, identificativoDocumento, null)))
                    .andExpect(status().isCreated());
        }
    }

    private String corpo(String idPendenza, String identificativoDocumento, Integer rata) {
        String campoRata = rata == null ? "" : ", \"rata\": " + rata;
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "causale %s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test"%s },
                  "voci": [ { "idVocePendenza": "voce-%s", "importo": 10.00, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, idPendenza, identificativoDocumento, campoRata, idPendenza);
    }

    /** Stesso corpo di {@link #corpo}, con {@code proprieta} (JSON grezzo) sul documento. */
    private String corpoConProprieta(String idPendenza, String identificativoDocumento, String proprietaJson) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "causale %s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test" },
                  "proprieta": %s,
                  "voci": [ { "idVocePendenza": "voce-%s", "importo": 10.00, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, idPendenza, identificativoDocumento, proprietaJson, idPendenza);
    }

    private String corpoConSoglia(String idPendenza, String identificativoDocumento, String tipoSoglia,
            double importo) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "causale %s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test",
                    "soglia": { "tipo": "%s", "giorni": 5 } },
                  "voci": [ { "idVocePendenza": "voce-%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, idPendenza, importo, identificativoDocumento, tipoSoglia, idPendenza,
                importo);
    }

    private JsonNode ultimoPayload() {
        String payload = ULTIMO_PAYLOAD.get();
        assertNotNull(payload, "nessuna chiamata a govpay-stampe");
        return objectMapper.readTree(payload);
    }

    private static List<Integer> instalmentNumbers(JsonNode payload) {
        List<Integer> numeri = new ArrayList<>();
        JsonNode instalments = payload.get("instalments");
        if (instalments != null && !instalments.isNull()) {
            for (JsonNode instalment : instalments) {
                numeri.add(instalment.get("instalment_number").asInt());
            }
        }
        return numeri;
    }
}
