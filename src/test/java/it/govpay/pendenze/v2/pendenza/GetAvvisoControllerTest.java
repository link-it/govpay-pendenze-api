package it.govpay.pendenze.v2.pendenza;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Verifica {@code GET /v2/avvisi/{idDominio}/{numeroAvviso}} ({@code getAvviso}) a livello di
 * integrazione — stesso impianto di {@link GetAvvisiDocumentoControllerTest} (contesto
 * completo, H2, stub JDK {@link HttpServer} di govpay-stampe). Le pendenze vengono create
 * tramite {@code addPendenza}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GetAvvisoControllerTest.BasicAuthDiDefaultConfig.class)
class GetAvvisoControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";
    private static final String ID_DOMINIO = "12345678901";
    private static final String PATH = "/v2/avvisi/{idDominio}/{numeroAvviso}";

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

    @BeforeAll
    static void avviaStampeStub() throws IOException {
        stampeStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stampeStub.createContext("/standard", exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                is.readAllBytes();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, FINTO_PDF.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(FINTO_PDF);
            }
        });
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

    @BeforeEach
    void creaAnagrafiche() {
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
    @DisplayName("Accept application/json, pendenza inesistente: 200 con stato SCONOSCIUTA (non 404) — replica "
            + "fedele del legacy")
    void jsonPendenzaInesistenteRestituisceSconosciuta() throws Exception {
        mockMvc.perform(get(PATH, ID_DOMINIO, "199999999999999999").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("SCONOSCIUTA"))
                .andExpect(jsonPath("$.idDominio").value(ID_DOMINIO))
                .andExpect(jsonPath("$.numeroAvviso").value("199999999999999999"));
    }

    @Test
    @DisplayName("Accept application/pdf, pendenza inesistente: 404 (non lo stesso 200 del JSON)")
    void pdfPendenzaInesistenteRestituisce404() throws Exception {
        mockMvc.perform(get(PATH, ID_DOMINIO, "199999999999999999").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Accept non supportato (e assente): 406")
    void acceptNonSupportatoRestituisce406() throws Exception {
        mockMvc.perform(get(PATH, ID_DOMINIO, "199999999999999999").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
        mockMvc.perform(get(PATH, ID_DOMINIO, "199999999999999999"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("JSON, pendenza NON_ESEGUITO non scaduta: stato NON_ESEGUITA, qrcode/barcode popolati")
    void jsonNonEseguitaRestituisceNonEseguita() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-1", "DOC-AVV-1");

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("NON_ESEGUITA"))
                .andExpect(jsonPath("$.numeroAvviso").value(numeroAvviso))
                .andExpect(jsonPath("$.idDominio").value(ID_DOMINIO))
                .andExpect(jsonPath("$.qrcode").isNotEmpty())
                .andExpect(jsonPath("$.barcode").isNotEmpty())
                .andExpect(jsonPath("$.descrizione").value("causale pend-avv-1"));
    }

    @Test
    @DisplayName("JSON, pendenza creata via API v3-nativa (causaleVersamento mai scritta): descrizione dalla "
            + "posizione debitoria, non vuota — stesso fallback della stampa PDF")
    void jsonPendenzaCreataViaV3NativaRestituisceDescrizioneDallaPosizione() throws Exception {
        String corpo = """
                {
                  "codDocumento": "DOC-AVV-V3-1",
                  "idDominio": "%s",
                  "descrizione": "descrizione della posizione v3",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pend-avv-v3-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 10.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-avv-v3-1",
                                  "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated());
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-v3-1")
                .orElseThrow();

        mockMvc.perform(get(PATH, ID_DOMINIO, pendenza.getNumeroAvviso()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descrizione").value("descrizione della posizione v3"));
    }

    @Test
    @DisplayName("JSON, pendenza ESEGUITO: stato DUPLICATA (switch del legacy, diverso da quello di getPendenza)")
    void jsonEseguitaRestituisceDuplicata() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-2", "DOC-AVV-2");
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-2")
                .orElseThrow();
        pendenza.setStato(StatoPendenza.ESEGUITO);
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("DUPLICATA"));
    }

    @Test
    @DisplayName("JSON, pendenza ANNULLATO: stato ANNULLATA")
    void jsonAnnullataRestituisceAnnullata() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-3", "DOC-AVV-3");
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-3")
                .orElseThrow();
        pendenza.setStato(StatoPendenza.ANNULLATO);
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("ANNULLATA"));
    }

    @Test
    @DisplayName("JSON, pendenza NON_ESEGUITO con dataScadenzaAvviso nel passato: stato SCADUTA")
    void jsonNonEseguitaScadutaRestituisceScaduta() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-4", "DOC-AVV-4");
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-4")
                .orElseThrow();
        pendenza.setDataScadenzaAvviso(java.time.OffsetDateTime.now().minusDays(10));
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("SCADUTA"));
    }

    @Test
    @DisplayName("JSON, pendenza ANOMALO: nessun caso nello switch originale del legacy, stato resta null "
            + "(replica fedele, anche se lo schema lo dichiara required)")
    void jsonAnomaloRestituisceStatoNull() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-5", "DOC-AVV-5");
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-5")
                .orElseThrow();
        pendenza.setStato(StatoPendenza.ANOMALO);
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").doesNotExist());
    }

    @Test
    @DisplayName("PDF, pendenza esistente: 200, PDF da /standard")
    void pdfPendenzaEsistenteRestituisce200() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-6", "DOC-AVV-6");

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ID_DOMINIO + "_" + numeroAvviso + ".pdf\""))
                .andExpect(content().bytes(FINTO_PDF));
    }

    @Test
    @DisplayName("PDF, pendenza con Marca da Bollo Telematica: 422 (il legacy risponderebbe 500 non gestito, "
            + "non replicato)")
    void pdfMarcaDaBolloRestituisce422() throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "test MBT",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 16.00,
                  "documento": { "identificativo": "DOC-AVV-MBT", "descrizione": "test" },
                  "voci": [ { "idVocePendenza": "voce-mbt", "importo": 16.00, "descrizione": "test",
                              "tipoBollo": "01", "hashDocumento": "aGFzaA==", "provinciaResidenza": "RM",
                              "tipoContabilita": "ALTRO", "codiceContabilita": "0101002IM" } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-avv-mbt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated());
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-mbt")
                .orElseThrow();

        mockMvc.perform(get(PATH, ID_DOMINIO, pendenza.getNumeroAvviso()).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("PDF, linguaSecondaria con causaleTradotta persistita su proprieta: 200 (il bilingue non e' "
            + "un parametro di richiesta, come in getAvvisiDocumento)")
    void pdfBilingueConProprietaPersistitaRestituisce200() throws Exception {
        String proprieta = """
                { "linguaSecondariaCausale": "Translated reason" }
                """;
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "causale bilingue",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "documento": { "identificativo": "DOC-AVV-BIL", "descrizione": "test" },
                  "proprieta": %s,
                  "voci": [ { "idVocePendenza": "voce-bil", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, proprieta);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-avv-bil")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated());
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(),
                        "pend-avv-bil")
                .orElseThrow();

        mockMvc.perform(get(PATH, ID_DOMINIO, pendenza.getNumeroAvviso()).param("linguaSecondaria", "en")
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PDF, linguaSecondaria senza causaleTradotta persistita: 422")
    void pdfBilingueSenzaProprietaRestituisce422() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-7", "DOC-AVV-7");

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).param("linguaSecondaria", "en")
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("piu' di una pendenza con lo stesso idDominio+numeroAvviso (avviso cumulativo migrato): "
            + "409 in entrambi i formati, non disambiguabile da questo endpoint")
    void piuDiUnaPendenzaRestituisce409() throws Exception {
        String numeroAvviso = creaPendenza("pend-avv-8", "DOC-AVV-8");

        Long idDominio = dominioRepository.findByCodDominio(ID_DOMINIO).orElseThrow().getId();
        Pendenza migrata = new Pendenza();
        migrata.setIdApplicazione(idApplicazioneCorrente());
        migrata.setIdDominio(idDominio);
        migrata.setIdPendenza("pend-avv-8-migrata");
        migrata.setIdTipoPendenza(1L);
        migrata.setIdTipoVersamento(1L);
        migrata.setImporto(10.00);
        migrata.setNumeroAvviso(numeroAvviso);
        migrata.setDataCreazione(java.time.OffsetDateTime.now());
        migrata.setDataUltimoAggiornamento(java.time.OffsetDateTime.now());
        pendenzaRepository.save(migrata);

        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
        mockMvc.perform(get(PATH, ID_DOMINIO, numeroAvviso).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isConflict());
    }

    private Long idApplicazioneCorrente() {
        return applicazioneRepository.findByCodApplicazione(PRINCIPAL).orElseThrow().getId();
    }

    private String creaPendenza(String idPendenza, String identificativoDocumento) throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "causale %s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test" },
                  "voci": [ { "idVocePendenza": "voce-%s", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, idPendenza, identificativoDocumento, idPendenza);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, idPendenza)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated());
        return pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazioneCorrente(), idPendenza)
                .orElseThrow()
                .getNumeroAvviso();
    }
}
