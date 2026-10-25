package it.govpay.pendenze.v2.pendenza;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

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
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Verifica {@code GET /v2/pendenze} (operationId {@code findPendenze}) a livello di
 * integrazione — stesso impianto di {@link PendenzaV2ControllerTest} (vedi il suo Javadoc di
 * classe per il motivo del duplicato invece di una superclasse comune).
 *
 * <p>Il focus di questi test e' la fedelta' al comportamento reale della v2 legacy decisa dal
 * committente ({@code feedback_wrapper_v2_fedelta_legacy}): asimmetrie e parametri scollegati
 * sono comportamento voluto, non bug da correggere.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FindPendenzeV2ControllerTest.BasicAuthDiDefaultConfig.class)
class FindPendenzeV2ControllerTest {

    private static final String PRINCIPAL = "A2A-TEST-FIND";
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

    private DominioEntity dominio;

    @BeforeEach
    void creaAnagrafiche() {
        UtenzaEntity utenza = creaUtenza(PRINCIPAL, PASSWORD);

        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(PRINCIPAL)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build());

        dominio = dominioRepository.save(DominioEntity.builder()
                .codDominio("12345678902")
                .ragioneSociale("Comune di Test Find")
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

    /** Stesso endpoint di scrittura v3 usato da {@code PendenzaV2ControllerTest}. */
    private String creaPendenza(String idPosizioneDebitoria, String idPendenza) throws Exception {
        String body = """
                {
                  "codDocumento": "%s",
                  "idDominio": "12345678902",
                  "descrizione": "test findPendenze v2",
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
                """.formatted(idPosizioneDebitoria, idPendenza, idPendenza);

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", PRINCIPAL)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        return idPendenza;
    }

    private Pendenza pendenzaCreata(String idPendenza) {
        Long idApplicazione = applicazioneRepository.findByCodApplicazione(PRINCIPAL).orElseThrow().getId();
        return pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(idApplicazione, idPendenza)
                .orElseThrow();
    }

    @Test
    @DisplayName("GET /v2/pendenze senza filtri trova le pendenze dell'applicazione autenticata, con metadati")
    void findPendenzeSenzaFiltri() throws Exception {
        creaPendenza("pos-find-1", "pendenza-find-1");

        mockMvc.perform(get("/v2/pendenze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.risultatiPerPagina").value(25))
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.numPagine").value(1))
                .andExpect(jsonPath("$.risultati.length()").value(1))
                .andExpect(jsonPath("$.risultati[0].idPendenza").value("pendenza-find-1"));
    }

    @Test
    @DisplayName("GET /v2/pendenze con idDominio inesistente restituisce 0 risultati, non un errore")
    void findPendenzeConIdDominioInesistente() throws Exception {
        creaPendenza("pos-find-2", "pendenza-find-2");

        mockMvc.perform(get("/v2/pendenze").queryParam("idDominio", "00000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0))
                .andExpect(jsonPath("$.risultati.length()").value(0));
    }

    /**
     * {@code Pendenza.srcDebitoreIdentificativo} (la colonna fisica su cui il legacy filtra
     * {@code idDebitore}) NON viene mai popolata dal percorso di scrittura v3-nativo
     * ({@code PosizioneDebitoriaService.crea}, verificato: resta sul valore sentinella
     * {@code "VEDERE_SOGGETTI_DEBITORI"}, il vero debitore vive solo su
     * {@code soggettiDebitori}) — per testare il filtro bisogna impostarla direttamente
     * sull'entita', esattamente come per {@code direzione}/{@code divisione}/{@code stato} negli
     * altri test. Questo e' un limite REALE del filtro per le pendenze create dal vero percorso
     * v3 (utile solo per dati migrati direttamente dal legacy): fedelta' alla colonna del
     * legacy, non un difetto del test.
     */
    @Test
    @DisplayName("GET /v2/pendenze filtra per idDebitore case-insensitive (su srcDebitoreIdentificativo)")
    void findPendenzeFiltroIdDebitoreCaseInsensitive() throws Exception {
        creaPendenza("pos-find-3", "pendenza-find-3");
        Pendenza pendenza = pendenzaCreata("pendenza-find-3");
        pendenza.setSrcDebitoreIdentificativo("FRRPLA90C41H501Y");
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get("/v2/pendenze").queryParam("idDebitore", "frrpla90c41h501y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].idPendenza").value("pendenza-find-3"));

        mockMvc.perform(get("/v2/pendenze").queryParam("idDebitore", "ALTRO-DEBITORE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0));
    }

    @Test
    @DisplayName("GET /v2/pendenze filtra per direzione/divisione")
    void findPendenzeFiltroDirezioneDivisione() throws Exception {
        creaPendenza("pos-find-4", "pendenza-find-4");
        Pendenza pendenza = pendenzaCreata("pendenza-find-4");
        pendenza.setDirezione("Direzione ABC");
        pendenza.setDivisione("Divisione XYZ");
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get("/v2/pendenze").queryParam("direzione", "Direzione ABC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));

        mockMvc.perform(get("/v2/pendenze").queryParam("divisione", "Divisione XYZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));

        mockMvc.perform(get("/v2/pendenze").queryParam("direzione", "Non esiste"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0));
    }

    @Test
    @DisplayName("GET /v2/pendenze filtra per dataDa/dataA sulla data di creazione")
    void findPendenzeFiltroDataDaDataA() throws Exception {
        creaPendenza("pos-find-5", "pendenza-find-5");
        Pendenza pendenza = pendenzaCreata("pendenza-find-5");
        OffsetDateTime dataCreazione = pendenza.getDataCreazione();

        mockMvc.perform(get("/v2/pendenze")
                        .queryParam("dataDa", dataCreazione.minusDays(1).toString())
                        .queryParam("dataA", dataCreazione.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1));

        mockMvc.perform(get("/v2/pendenze")
                        .queryParam("dataDa", dataCreazione.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0));
    }

    @Test
    @DisplayName("GET /v2/pendenze?stato=ESEGUITA e' ASIMMETRICO rispetto alla lettura singola: "
            + "trova solo stato=ESEGUITO+statoPagamento=PAGATO, non stato=ESEGUITO+NON_PAGATO "
            + "(comportamento reale del legacy, replicato fedelmente)")
    void findPendenzeStatoEseguitaAsimmetrico() throws Exception {
        creaPendenza("pos-find-6a", "pendenza-find-6a");
        creaPendenza("pos-find-6b", "pendenza-find-6b");

        Pendenza pagata = pendenzaCreata("pendenza-find-6a");
        pagata.setStato(StatoPendenza.ESEGUITO);
        pagata.setStatoPagamento(StatoPagamento.PAGATO);
        pendenzaRepository.save(pagata);

        Pendenza nonPagata = pendenzaCreata("pendenza-find-6b");
        nonPagata.setStato(StatoPendenza.ESEGUITO);
        nonPagata.setStatoPagamento(StatoPagamento.NON_PAGATO);
        pendenzaRepository.save(nonPagata);

        mockMvc.perform(get("/v2/pendenze").queryParam("stato", "ESEGUITA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].idPendenza").value("pendenza-find-6a"));
    }

    @Test
    @DisplayName("GET /v2/pendenze?stato=ANOMALA e' un filtro MORTO per design: restituisce sempre "
            + "0 risultati, anche se esiste una pendenza con anomalo=true nel DB — bug del legacy "
            + "replicato fedelmente (il valore enum ANOMALO non viene mai scritto sulla colonna "
            + "reale, nemmeno da v3-nativo), decisione esplicita del committente di NON correggerlo")
    void findPendenzeStatoAnomalaFiltroMorto() throws Exception {
        creaPendenza("pos-find-7", "pendenza-find-7");
        Pendenza pendenza = pendenzaCreata("pendenza-find-7");
        pendenza.setAnomalo(true);
        pendenzaRepository.save(pendenza);

        mockMvc.perform(get("/v2/pendenze").queryParam("stato", "ANOMALA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(0))
                .andExpect(jsonPath("$.risultati.length()").value(0));
    }

    @Test
    @DisplayName("GET /v2/pendenze ignora idA2A nella query string: resta filtrato sull'applicazione autenticata")
    void findPendenzeIgnoraIdA2ANellaQuery() throws Exception {
        creaPendenza("pos-find-8", "pendenza-find-8");

        mockMvc.perform(get("/v2/pendenze").queryParam("idA2A", "A2A-DIVERSA-INESISTENTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].idPendenza").value("pendenza-find-8"));
    }

    @Test
    @DisplayName("GET /v2/pendenze: idPendenza e idPagamento sono accettati ma non filtrano nulla "
            + "(stessa disconnessione del legacy)")
    void findPendenzeIdPendenzaEIdPagamentoNonFiltrano() throws Exception {
        creaPendenza("pos-find-9", "pendenza-find-9");

        mockMvc.perform(get("/v2/pendenze")
                        .queryParam("idPendenza", "valore-che-non-corrisponde-a-nulla")
                        .queryParam("idPagamento", "altro-valore-inesistente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(1))
                .andExpect(jsonPath("$.risultati[0].idPendenza").value("pendenza-find-9"));
    }

    @Test
    @DisplayName("GET /v2/pendenze?metadatiPaginazione=false non calcola numRisultati/numPagine")
    void findPendenzeMetadatiPaginazioneFalse() throws Exception {
        creaPendenza("pos-find-10", "pendenza-find-10");

        mockMvc.perform(get("/v2/pendenze").queryParam("metadatiPaginazione", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").doesNotExist())
                .andExpect(jsonPath("$.numPagine").doesNotExist())
                .andExpect(jsonPath("$.risultati.length()").value(1));
    }

    @Test
    @DisplayName("GET /v2/pendenze pagina correttamente con risultatiPerPagina")
    void findPendenzePaginazione() throws Exception {
        creaPendenza("pos-find-11a", "pendenza-find-11a");
        creaPendenza("pos-find-11b", "pendenza-find-11b");
        creaPendenza("pos-find-11c", "pendenza-find-11c");

        mockMvc.perform(get("/v2/pendenze")
                        .queryParam("pagina", "1")
                        .queryParam("risultatiPerPagina", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(3))
                .andExpect(jsonPath("$.numPagine").value(2))
                .andExpect(jsonPath("$.risultati.length()").value(2))
                .andExpect(jsonPath("$.prossimiRisultati").value(org.hamcrest.Matchers.containsString("pagina=2")));

        mockMvc.perform(get("/v2/pendenze")
                        .queryParam("pagina", "2")
                        .queryParam("risultatiPerPagina", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risultati.length()").value(1))
                .andExpect(jsonPath("$.prossimiRisultati").doesNotExist());
    }
}
