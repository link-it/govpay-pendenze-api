package it.govpay.pendenze.v2.pendenza;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sun.net.httpserver.HttpServer;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.identificativo.IdentificatorePosizioneDebitoria;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Verifica {@code PUT /v2/pendenze/{idA2A}/{idPendenza}} ({@code addPendenza}) a livello di
 * integrazione (contesto Spring completo, DB H2 con lo schema reale) — stesso impianto di
 * {@link PendenzaV2ControllerTest}/{@code it.govpay.pendenze.posizionedebitoria.PosizioneDebitoriaControllerTest},
 * duplicato in una classe propria per isolare il fixture del microservizio govpay-stampe (un
 * server HTTP embedded, vedi {@link #avviaStampeStub()}), non condiviso dagli altri test di
 * questo package.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AddPendenzaControllerTest.BasicAuthDiDefaultConfig.class)
class AddPendenzaControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";
    private static final String ID_DOMINIO = "12345678901";

    @TestConfiguration
    static class BasicAuthDiDefaultConfig {
        @Bean
        MockMvcBuilderCustomizer basicAuthDiDefaultCustomizer() {
            return builder -> builder.defaultRequest(
                    MockMvcRequestBuilders.get("/").with(httpBasic(PRINCIPAL, PASSWORD)));
        }
    }

    /** Stub minimale (JDK {@link HttpServer}, nessuna dipendenza di test aggiuntiva) del
     *  microservizio govpay-stampe: risponde a {@code POST /standard} con pochi byte fissi,
     *  solo per verificare che {@code addPendenza} propaghi un {@code pdf} non vuoto quando
     *  {@code stampaAvviso=true} — non e' un PDF valido, il contenuto esatto non e'
     *  rilevante per questa classe di test (nessun parsing lato GovPay). */
    private static HttpServer stampeStub;
    private static final byte[] FINTO_PDF = "%PDF-finto-per-test".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void avviaStampeStub() throws IOException {
        stampeStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stampeStub.createContext("/standard", exchange -> {
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
    private UnitaOperativaRepository unitaOperativaRepository;

    @Autowired
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    @Autowired
    private PendenzaRepository pendenzaRepository;

    @Autowired
    private RptRepository rptRepository;

    @Autowired
    private AllegatoRepository allegatoRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long idApplicazione;
    private Long idDominio;

    /**
     * Ogni chiamata a un repository Spring Data apre e chiude la propria transazione
     * (default di {@code SimpleJpaRepository}): l'entity restituita e' gia' "detached"
     * quando il metodo ritorna, quindi leggere qui una collezione/proxy LAZY
     * (es. {@code Pendenza.getOpzionePagamento()}) solleverebbe
     * {@code LazyInitializationException}. Usato SOLO per le asserzioni di questa classe di
     * test, mai per avvolgere la chiamata {@code mockMvc.perform(...)} stessa: avvolgere
     * anche quella maschererebbe un eventuale bug reale di confine transazionale nel
     * controller/mapper sotto test (stesso principio di "nessun {@code @Transactional} di
     * classe" documentato in {@link PendenzaV2ControllerTest}).
     */
    private <T> T inTransazioneDiLettura(java.util.function.Supplier<T> azione) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> azione.get());
    }

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
        idDominio = dominio.getId();

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
        unitaOperativaRepository.deleteAll();
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        tributoRepository.deleteAll();
        tipoTributoRepository.deleteAll();
        dominioRepository.deleteAll();
        applicazioneRepository.deleteAll();
        aclRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    private String corpoBase(String causale, double importo, String idVocePendenza) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, idVocePendenza, importo);
    }

    @Test
    @DisplayName("creazione senza 'documento': sintetizza un documento e crea un'opzione SOLUZIONE_UNICA (201)")
    void creaSenzaDocumentoSintetizzaSoluzioneUnica() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-su-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("pagamento senza documento", 16.00, "voce-su-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idDominio").value(ID_DOMINIO))
                .andExpect(jsonPath("$.numeroAvviso").value(not(blankOrNullString())));

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-su-1").orElseThrow();
            OpzionePagamento opzione = pendenza.getOpzionePagamento();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.SOLUZIONE_UNICA,
                    opzione.getTipologia());
            PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
            // UUID senza trattini (32 hex), nessun prefisso "MIGR-" (riservato alla migrazione).
            org.junit.jupiter.api.Assertions.assertEquals(32, posizione.getCodDocumento().length());
            org.junit.jupiter.api.Assertions.assertFalse(posizione.getCodDocumento().contains("-"));
            return null;
        });
    }

    @Test
    @DisplayName("creazione con soggettoPagatore.cellulare: persistito su SoggettoDebitore (campo aggiunto, "
            + "2026-10-27 — in precedenza scartato silenziosamente, gap noto)")
    void creaConCellulareDelSoggettoLoPersiste() throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "test cellulare",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi",
                                         "cellulare": "+39 000-1234567" },
                  "importo": 16.00,
                  "voci": [ { "idVocePendenza": "voce-cellulare", "importo": 16.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-cellulare-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-cellulare-1").orElseThrow();
            SoggettoDebitore soggetto = pendenza.getOpzionePagamento().getPosizioneDebitoria()
                    .getSoggettiDebitori().get(0);
            org.junit.jupiter.api.Assertions.assertEquals("+39 000-1234567", soggetto.getCellulare());
            return null;
        });
    }

    @Test
    @DisplayName("creazione con documento+rata=1: crea un'opzione PIANO_RATEALE (201)")
    void creaConDocumentoERataUnoCreaPianoRateale() throws Exception {
        String corpo = corpoConDocumento("pagamento rateale", 10.00, "voce-pr-1", "DOC-PR-1", 1);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-pr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-pr-1").orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.PIANO_RATEALE,
                    pendenza.getOpzionePagamento().getTipologia());
            org.junit.jupiter.api.Assertions.assertEquals(1, pendenza.getNumeroRata());
            org.junit.jupiter.api.Assertions.assertEquals("DOC-PR-1",
                    pendenza.getOpzionePagamento().getPosizioneDebitoria().getCodDocumento());
            return null;
        });
    }

    @Test
    @DisplayName("seconda chiamata con documento+rata=2 sullo stesso documento: si aggiunge alla stessa opzione")
    void secondaRataSiAggiungeAllaStessaOpzione() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-pr-2a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumento("rata 1", 10.00, "voce-pr-2a", "DOC-PR-2", 1)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-pr-2b")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumento("rata 2", 10.00, "voce-pr-2b", "DOC-PR-2", 2)))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            PosizioneDebitoria posizione = posizioneDebitoriaRepository
                    .findByIdApplicazioneAndIdDominioAndCodDocumento(idApplicazione, idDominio, "DOC-PR-2")
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(1, posizione.getOpzioniPagamento().size());
            OpzionePagamento opzione = posizione.getOpzioniPagamento().get(0);
            org.junit.jupiter.api.Assertions.assertEquals(2, opzione.getPendenze().size());
            return null;
        });
    }

    @Test
    @DisplayName("una seconda rata con 'documento.descrizione' diversa sovrascrive quella della posizione "
            + "esistente (decisione del committente del 2026-10-27: vince l'ultima, non resta piu' fissata alla "
            + "creazione)")
    void secondaRataConDescrizioneDiversaSovrascriveQuellaDellaPosizione() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-descr-1a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoEDescrizione("rata 1", 10.00, "voce-descr-1a", "DOC-DESCR-1",
                        "descrizione originale", 1)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-descr-1b")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoEDescrizione("rata 2", 10.00, "voce-descr-1b", "DOC-DESCR-1",
                        "descrizione aggiornata", 2)))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            PosizioneDebitoria posizione = posizioneDebitoriaRepository
                    .findByIdApplicazioneAndIdDominioAndCodDocumento(idApplicazione, idDominio, "DOC-DESCR-1")
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals("descrizione aggiornata", posizione.getDescrizione());
            return null;
        });
    }

    @Test
    @DisplayName("un secondo PUT sulla STESSA pendenza (aggiornamento) con causale diversa e nessun 'documento' "
            + "sovrascrive la descrizione della posizione (sintetica) con la nuova causale")
    void aggiornamentoStessaPendenzaConCausaleDiversaSovrascriveDescrizionePosizioneSintetica() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-descr-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("causale originale", 10.00, "voce-descr-2")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-descr-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("causale aggiornata", 10.00, "voce-descr-2")))
                .andExpect(status().isOk());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository
                    .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, "pend-descr-2")
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals("causale aggiornata",
                    pendenza.getOpzionePagamento().getPosizioneDebitoria().getDescrizione());
            return null;
        });
    }

    /** Stesso corpo di {@link #corpoConDocumento}, con una {@code documento.descrizione} parametrica. */
    private String corpoConDocumentoEDescrizione(String causale, double importo, String idVocePendenza,
            String identificativo, String descrizioneDocumento, int rata) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "%s", "rata": %s },
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, identificativo, descrizioneDocumento, rata,
                idVocePendenza, importo);
    }

    @Test
    @DisplayName("creazione con documento+soglia ENTRO: crea un'opzione SOLUZIONE_UNICA_ENTRO (201), giorni dalla soglia")
    void creaConDocumentoESogliaEntroCreaSoluzioneUnicaEntro() throws Exception {
        String corpo = corpoConSoglia("sanzione ridotta", 70.00, "voce-entro-1", "DOC-ENTRO-1", "ENTRO", 5);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-entro-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-entro-1").orElseThrow();
            OpzionePagamento opzione = pendenza.getOpzionePagamento();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO,
                    opzione.getTipologia());
            org.junit.jupiter.api.Assertions.assertEquals(5, opzione.getGiorni());
            return null;
        });
    }

    @Test
    @DisplayName("creazione con documento+soglia OLTRE: crea un'opzione SOLUZIONE_UNICA_OLTRE (201)")
    void creaConDocumentoESogliaOltreCreaSoluzioneUnicaOltre() throws Exception {
        String corpo = corpoConSoglia("sanzione intera", 100.00, "voce-oltre-1", "DOC-OLTRE-1", "OLTRE", 5);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-oltre-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-oltre-1").orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE,
                    pendenza.getOpzionePagamento().getTipologia());
            return null;
        });
    }

    @Test
    @DisplayName("creazione con documento+soglia RIDOTTO (violazione CdS): crea un'opzione SOLUZIONE_UNICA_RIDOTTO "
            + "(201), 'giorni' ignorato anche se presente nel body")
    void creaConDocumentoESogliaRidottoCreaSoluzioneUnicaRidottoIgnorandoGiorni() throws Exception {
        String corpo = corpoConSoglia("sanzione CdS ridotta", 70.00, "voce-ridotto-1", "DOC-RIDOTTO-1", "RIDOTTO", 5);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ridotto-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-ridotto-1").orElseThrow();
            OpzionePagamento opzione = pendenza.getOpzionePagamento();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.SOLUZIONE_UNICA_RIDOTTO,
                    opzione.getTipologia());
            org.junit.jupiter.api.Assertions.assertNull(opzione.getGiorni());
            return null;
        });
    }

    @Test
    @DisplayName("creazione con documento+soglia SCONTATO (violazione CdS): crea un'opzione SOLUZIONE_UNICA_SCONTATO "
            + "(201)")
    void creaConDocumentoESogliaScontatoCreaSoluzioneUnicaScontato() throws Exception {
        String corpo = corpoConSoglia("sanzione CdS scontata", 100.00, "voce-scontato-1", "DOC-SCONTATO-1",
                "SCONTATO", 5);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-scontato-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-scontato-1").orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.SOLUZIONE_UNICA_SCONTATO,
                    pendenza.getOpzionePagamento().getTipologia());
            return null;
        });
    }

    @Test
    @DisplayName("seconda soglia complementare (OLTRE) sullo stesso documento (prima ENTRO): completa la coppia "
            + "aggiungendo una SECONDA opzione, invece di 400")
    void secondaSogliaOltreSuDocumentoEntroCompletaLaCoppia() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-coppia-eo-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConSoglia("sanzione ridotta", 70.00, "voce-coppia-eo-1", "DOC-COPPIA-EO", "ENTRO", 5)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-coppia-eo-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConSoglia("sanzione intera", 100.00, "voce-coppia-eo-2", "DOC-COPPIA-EO", "OLTRE", 5)))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            PosizioneDebitoria posizione = posizioneDebitoriaRepository
                    .findByIdApplicazioneAndIdDominioAndCodDocumento(idApplicazione, idDominio, "DOC-COPPIA-EO")
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(2, posizione.getOpzioniPagamento().size());
            var tipologie = posizione.getOpzioniPagamento().stream()
                    .map(OpzionePagamento::getTipologia).collect(java.util.stream.Collectors.toSet());
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.Set.of(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO,
                            TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE),
                    tipologie);
            posizione.getOpzioniPagamento()
                    .forEach(o -> org.junit.jupiter.api.Assertions.assertEquals(1, o.getPendenze().size()));
            return null;
        });
    }

    @Test
    @DisplayName("una TERZA soglia sullo stesso documento (coppia ENTRO/OLTRE gia' completa): 400")
    void terzaSogliaSuCoppiaGiaCompletaRestituisce400() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-coppia-eo3-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConSoglia("sanzione ridotta", 70.00, "voce-coppia-eo3-1", "DOC-COPPIA-EO3", "ENTRO", 5)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-coppia-eo3-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConSoglia("sanzione intera", 100.00, "voce-coppia-eo3-2", "DOC-COPPIA-EO3", "OLTRE", 5)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-coppia-eo3-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConSoglia("sanzione in piu'", 50.00, "voce-coppia-eo3-3", "DOC-COPPIA-EO3", "ENTRO", 10)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("un documento con PIU' di un'opzione (creato dalla v3-nativa, non da questo wrapper) accetta "
            + "comunque una nuova pendenza se una sola delle opzioni ha la tipologia richiesta")
    void documentoV3NativoConPiuOpzioniTrovaQuellaCompatibile() throws Exception {
        String creazione = """
                {
                  "codDocumento": "DOC-MULTI-1",
                  "idDominio": "%s",
                  "descrizione": "documento con piu' opzioni",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "RSSMRA80A01H501U" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pend-multi-su",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 10.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-multi-su",
                                  "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(creazione))
                .andExpect(status().isCreated());

        String secondaOpzione = """
                {
                  "tipologia": "SOLUZIONE_UNICA_ENTRO",
                  "giorni": 5,
                  "pendenze": [ {
                    "idPendenza": "pend-multi-entro",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 70.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-multi-entro",
                                "importo": 70.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento", PRINCIPAL,
                IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, "DOC-MULTI-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondaOpzione))
                .andExpect(status().isCreated());

        // Il documento ha ora 2 opzioni (SOLUZIONE_UNICA, SOLUZIONE_UNICA_ENTRO): una richiesta
        // v2 senza 'rata'/'soglia' (-> SOLUZIONE_UNICA) non e' piu' rifiutata per il solo fatto
        // che il documento ne ha piu' di una, perche' esattamente una corrisponde.
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-multi-nuova")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoSenzaRata("terza pendenza sul documento", 10.00, "voce-multi-nuova",
                        "DOC-MULTI-1")))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            PosizioneDebitoria posizione = posizioneDebitoriaRepository
                    .findByIdApplicazioneAndIdDominioAndCodDocumento(idApplicazione, idDominio, "DOC-MULTI-1")
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(2, posizione.getOpzioniPagamento().size());
            OpzionePagamento soluzioneUnica = posizione.getOpzioniPagamento().stream()
                    .filter(o -> o.getTipologia() == TipologiaOpzionePagamento.SOLUZIONE_UNICA).findFirst()
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(2, soluzioneUnica.getPendenze().size());
            OpzionePagamento entro = posizione.getOpzioniPagamento().stream()
                    .filter(o -> o.getTipologia() == TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO).findFirst()
                    .orElseThrow();
            org.junit.jupiter.api.Assertions.assertEquals(1, entro.getPendenze().size());
            return null;
        });
    }

    @Test
    @DisplayName("un documento con DUE opzioni della STESSA tipologia (creato dalla v3-nativa): riferimento "
            + "ambiguo, 400 invece di scegliere arbitrariamente")
    void documentoV3NativoConDueOpzioniDellaStessaTipologiaRestituisce400Ambiguo() throws Exception {
        String creazione = """
                {
                  "codDocumento": "DOC-MULTI-2",
                  "idDominio": "%s",
                  "descrizione": "documento con due opzioni della stessa tipologia",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "RSSMRA80A01H501U" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pend-ambig-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 10.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-ambig-1",
                                  "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(ID_DOMINIO);
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(creazione))
                .andExpect(status().isCreated());

        String secondaOpzione = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pend-ambig-2",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 10.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-ambig-2",
                                "importo": 10.00, "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento", PRINCIPAL,
                IdentificatorePosizioneDebitoria.comporre(PRINCIPAL, ID_DOMINIO, "DOC-MULTI-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondaOpzione))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ambig-nuova")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoSenzaRata("terza pendenza ambigua", 10.00, "voce-ambig-nuova",
                        "DOC-MULTI-2")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("documento.soglia RIDOTTO senza 'giorni' nel body: 201 (non e' un valore ammesso per questa "
            + "tipologia, non e' richiesto)")
    void creaConDocumentoESogliaRidottoSenzaGiorniNelBodyRestituisce201() throws Exception {
        String corpo = corpoConSogliaSenzaGiorni("sanzione CdS ridotta", 70.00, "voce-ridotto-ng-1",
                "DOC-RIDOTTO-NG-1", "RIDOTTO");
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ridotto-ng-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());

        inTransazioneDiLettura(() -> {
            Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                    idApplicazione, "pend-ridotto-ng-1").orElseThrow();
            OpzionePagamento opzione = pendenza.getOpzionePagamento();
            org.junit.jupiter.api.Assertions.assertEquals(TipologiaOpzionePagamento.SOLUZIONE_UNICA_RIDOTTO,
                    opzione.getTipologia());
            org.junit.jupiter.api.Assertions.assertNull(opzione.getGiorni());
            return null;
        });
    }

    @Test
    @DisplayName("documento.soglia ENTRO senza 'giorni' nel body: 400 (obbligatorio per questa tipologia)")
    void creaConDocumentoESogliaEntroSenzaGiorniNelBodyRestituisce400() throws Exception {
        String corpo = corpoConSogliaSenzaGiorni("sanzione ridotta", 70.00, "voce-entro-ng-1", "DOC-ENTRO-NG-1",
                "ENTRO");
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-entro-ng-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("documento.rata e documento.soglia insieme nello stesso body: 400")
    void rataESogliaInsiemeRestituisce400() throws Exception {
        String corpo = corpoConRataESoglia("non valido", 10.00, "voce-rata-soglia", "DOC-RATA-SOGLIA", 1, "ENTRO", 5);
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-rata-soglia")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("update di una pendenza NON_ESEGUITO esistente: 200, non 201")
    void updatePendenzaNonEseguitaRestituisce200() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-upd-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("originale", 16.00, "voce-upd-1")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-upd-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("aggiornata", 20.00, "voce-upd-1")))
                .andExpect(status().isOk());

        Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                idApplicazione, "pend-upd-1").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(20.00, pendenza.getImporto());
    }

    @Test
    @DisplayName("update su una pendenza gia' ESEGUITA: 409")
    void updatePendenzaEseguitaRestituisce409() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-eseg-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("originale", 16.00, "voce-eseg-1")))
                .andExpect(status().isCreated());

        Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                idApplicazione, "pend-eseg-1").orElseThrow();
        pendenza.setStato(StatoPendenza.ESEGUITO);
        pendenzaRepository.save(pendenza);

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-eseg-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("tentativo di aggiornamento", 20.00, "voce-eseg-1")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("mismatch di tipologia (documento SOLUZIONE_UNICA, poi chiamata con rata): 400")
    void mismatchTipologiaRestituisce400() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-mis-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoSenzaRata("prima pendenza", 10.00, "voce-mis-1", "DOC-MIS-1")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-mis-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumento("seconda pendenza con rata", 10.00, "voce-mis-2", "DOC-MIS-1", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("update con un idDominio diverso da quello originale: 400, nessun cambio di beneficiario")
    void aggiornamentoConDominioDiversoRifiutato() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-dom-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("originale", 16.00, "voce-dom-1")))
                .andExpect(status().isCreated());

        dominioRepository.save(DominioEntity.builder()
                .codDominio("11111111111")
                .ragioneSociale("Comune Diverso")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-dom-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("originale", 16.00, "voce-dom-1").replace(ID_DOMINIO, "11111111111")))
                .andExpect(status().isBadRequest());

        Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                idApplicazione, "pend-dom-1").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(idDominio, pendenza.getIdDominio());
    }

    @Test
    @DisplayName("update con un idDominio inesistente: 404, non un 200 con dominio fantasma")
    void aggiornamentoConDominioInesistenteRifiutato() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-dom-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("originale", 16.00, "voce-dom-2")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-dom-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("originale", 16.00, "voce-dom-2").replace(ID_DOMINIO, "00000000099")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("voce con 'datiAllegati' (v2, senza equivalente v3): 400 invece di un successo con perdita "
            + "silenziosa del dato")
    void voceConDatiAllegatiRifiutata() throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "test datiAllegati",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "voci": [ { "idVocePendenza": "voce-datiallegati-1", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA", "datiAllegati": { "qualcosa": "reale" } } ]
                }
                """.formatted(ID_DOMINIO);

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-datiallegati-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("voce con 'contabilita.quote' popolata (v2, senza equivalente v3 — la colonna fisica e' "
            + "gia' presa da dettaglioContabile): 400")
    void voceConContabilitaRifiutata() throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "test contabilita",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "voci": [ { "idVocePendenza": "voce-contabilita-1", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA",
                              "contabilita": { "quote": [ { "importo": 5.00 } ] } } ]
                }
                """.formatted(ID_DOMINIO);

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-contabilita-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("update con un nuovo allegato: REPLACE non APPEND, il precedente viene rimosso")
    void allegatiSuAggiornamentoRimpiazzanoIPrecedenti() throws Exception {
        String conAllegato1 = corpoConAllegato("originale", "primo.pdf", "cHJpbW8=");
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-alleg-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(conAllegato1))
                .andExpect(status().isCreated());

        Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                idApplicazione, "pend-alleg-1").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(1,
                allegatoRepository.findByIdVersamentoOrderByIdAsc(pendenza.getId()).size());

        String conAllegato2 = corpoConAllegato("aggiornata", "secondo.pdf", "c2Vjb25kbw==");
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-alleg-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(conAllegato2))
                .andExpect(status().isOk());

        var allegatiRimasti = allegatoRepository.findByIdVersamentoOrderByIdAsc(pendenza.getId());
        org.junit.jupiter.api.Assertions.assertEquals(1, allegatiRimasti.size());
        org.junit.jupiter.api.Assertions.assertEquals("secondo.pdf", allegatiRimasti.get(0).getNome());
    }

    private String corpoConAllegato(String causale, String nomeAllegato, String contenutoBase64) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "voci": [ { "idVocePendenza": "voce-alleg-1", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA" } ],
                  "allegati": [ { "nome": "%s", "contenuto": "%s" } ]
                }
                """.formatted(ID_DOMINIO, causale, nomeAllegato, contenutoBase64);
    }

    @Test
    @DisplayName("PUT risponde 403 se l'applicazione non e' trusted e non e' autorizzata su dominio/tipoVersamento")
    void putNonAutorizzataRispondeAccessoNegato() throws Exception {
        ApplicazioneEntity applicazione = applicazioneRepository.findByCodApplicazione(PRINCIPAL).orElseThrow();
        applicazione.setTrusted(false);
        applicazioneRepository.save(applicazione);

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-403-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("tentativo non autorizzato", 10.00, "voce-403-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("stampaAvviso=true: la risposta include un pdf non vuoto")
    void stampaAvvisoTrueRestituiscePdfNonVuoto() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}?stampaAvviso=true", PRINCIPAL, "pend-pdf-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("con stampa avviso", 16.00, "voce-pdf-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pdf").value(not(blankOrNullString())));
    }

    private String corpoConDocumento(String causale, double importo, String idVocePendenza, String identificativo,
            int rata) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test", "rata": %s },
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, identificativo, rata, idVocePendenza, importo);
    }

    private String corpoConSoglia(String causale, double importo, String idVocePendenza, String identificativo,
            String tipoSoglia, int giorni) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test",
                    "soglia": { "tipo": "%s", "giorni": %s } },
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, identificativo, tipoSoglia, giorni, idVocePendenza,
                importo);
    }

    /** Stesso corpo di {@link #corpoConSoglia}, ma senza il campo {@code giorni} nella soglia. */
    private String corpoConSogliaSenzaGiorni(String causale, double importo, String idVocePendenza,
            String identificativo, String tipoSoglia) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test",
                    "soglia": { "tipo": "%s" } },
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, identificativo, tipoSoglia, idVocePendenza, importo);
    }

    /** {@code rata} e {@code soglia} insieme nello stesso body: 400. */
    private String corpoConRataESoglia(String causale, double importo, String idVocePendenza, String identificativo,
            int rata, String tipoSoglia, int giorni) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test", "rata": %s,
                    "soglia": { "tipo": "%s", "giorni": %s } },
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, identificativo, rata, tipoSoglia, giorni,
                idVocePendenza, importo);
    }

    private String corpoConDocumentoSenzaRata(String causale, double importo, String idVocePendenza,
            String identificativo) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test" },
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, causale, importo, identificativo, idVocePendenza, importo);
    }

    private void salvaUnitaOperativa(String codUo) {
        DominioEntity dominio = dominioRepository.findById(idDominio).orElseThrow();
        UnitaOperativaEntity unitaOperativa = new UnitaOperativaEntity();
        unitaOperativa.setCodUo(codUo);
        unitaOperativa.setAbilitato(true);
        unitaOperativa.setDominio(dominio);
        unitaOperativaRepository.save(unitaOperativa);
    }

    private String corpoConUnitaOperativa(String causale, double importo, String idVocePendenza,
            String idUnitaOperativa) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "idUnitaOperativa": "%s",
                  "causale": "%s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": %s,
                  "voci": [ { "idVocePendenza": "%s", "importo": %s, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, idUnitaOperativa, causale, importo, idVocePendenza, importo);
    }

    @Test
    @DisplayName("update che omette 'idUnitaOperativa' su una pendenza associata a EC: 200, non un 400 per un "
            + "falso cambio di beneficiario (il legacy interpreta l'omissione come EC)")
    void aggiornamentoSenzaUnitaOperativaSuPendenzaEcNonRifiutato() throws Exception {
        salvaUnitaOperativa("EC");

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ec-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConUnitaOperativa("originale", 16.00, "voce-ec-1", "EC")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ec-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("aggiornata", 20.00, "voce-ec-1")))
                .andExpect(status().isOk());

        Pendenza pendenza = pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                idApplicazione, "pend-ec-1").orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(20.00, pendenza.getImporto());
    }

    @Test
    @DisplayName("update che cambia davvero l'unita' operativa (non EC): 400, il rifiuto resta")
    void aggiornamentoConUnitaOperativaDiversaRifiutato() throws Exception {
        salvaUnitaOperativa("UO1");
        salvaUnitaOperativa("UO2");

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-uo-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConUnitaOperativa("originale", 16.00, "voce-uo-1", "UO1")))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-uo-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConUnitaOperativa("tentativo", 16.00, "voce-uo-1", "UO2")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("voce con 'contabilita.quote' esplicitamente null: trattata come assente (201), non una "
            + "NullPointerException/500")
    void voceConContabilitaQuoteNullNonLanciaNpe() throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "test contabilita.quote null",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "voci": [ { "idVocePendenza": "voce-contabilita-null-1", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA",
                              "contabilita": { "quote": null } } ]
                }
                """.formatted(ID_DOMINIO);

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-contabilita-null-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("voce con 'metadata.mapEntries' esplicitamente null: trattata come assente (201), non una "
            + "NullPointerException/500")
    void voceConMetadataMapEntriesNullNonLanciaNpe() throws Exception {
        String corpo = """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "test metadata.mapEntries null",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "voci": [ { "idVocePendenza": "voce-metadata-null-1", "importo": 10.00, "descrizione": "test",
                              "codEntrata": "DIRITTI_SEGRETERIA",
                              "metadata": { "mapEntries": null } } ]
                }
                """.formatted(ID_DOMINIO);

        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-metadata-null-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpo))
                .andExpect(status().isCreated());
    }
}
