package it.govpay.pendenze.pendenza;

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

import java.time.OffsetDateTime;

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
import it.govpay.pendenze.entity.FlussoRendicontazione;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rendicontazione;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoRendicontazione;
import it.govpay.pendenze.repository.FlussoRendicontazioneRepository;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RendicontazioneRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Verifica {@code GET /pendenze/{idA2A}} (ricerca per numero avviso) a livello di
 * integrazione, come {@code PosizioneDebitoriaControllerTest}: contesto Spring completo, DB
 * H2 con lo schema reale, niente {@code @Transactional} sulla classe per lo stesso motivo li'
 * documentato (maschererebbe la classe di bug delle relazioni LAZY lette dopo la chiusura
 * della transazione di scrittura).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PendenzaControllerTest.BasicAuthDiDefaultConfig.class)
class PendenzaControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";

    /**
     * Vedi Javadoc dell'omologo in {@code PosizioneDebitoriaControllerTest}: nessun test in
     * questa classe usa un idA2A diverso da "A2A-TEST", quindi qui basta l'iniezione di
     * default, senza override per-richiesta.
     */
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
    private PendenzaRepository pendenzaRepository;

    @Autowired
    private RptRepository rptRepository;

    @Autowired
    private FlussoRendicontazioneRepository flussoRendicontazioneRepository;

    @Autowired
    private RendicontazioneRepository rendicontazioneRepository;

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

        creaDominio("12345678901", "Comune di Test");

        tipoVersamento = nuovoTipoVersamento("DIRITTI_SEGRETERIA", "Diritti di segreteria");
        tipoVersamentoRepository.save(tipoVersamento);

        tipoTributo = new TipoTributoEntity();
        tipoTributo.setCodTributo("DIRITTI_SEGRETERIA");
        tipoTributo.setDescrizione("Diritti di segreteria");
        tipoTributoRepository.save(tipoTributo);

        configuraTipoVersamentoPerDominio("12345678901");
    }

    /**
     * {@code tipi_versamento} ha 9 colonne {@code NOT NULL DEFAULT false} (configurazione
     * BO/PAG/avvisatura mail/AppIO, mai {@code null} su una riga reale) — valorizzate qui
     * esplicitamente a {@code false} ("nessuna integrazione attiva"), altrimenti l'INSERT
     * fallisce (il default SQL non si applica: Hibernate invia sempre un valore esplicito,
     * {@code NULL} se il campo Java non e' stato impostato).
     */
    private TipoVersamentoEntity nuovoTipoVersamento(String codTipoVersamento, String descrizione) {
        TipoVersamentoEntity tv = new TipoVersamentoEntity();
        tv.setCodTipoVersamento(codTipoVersamento);
        tv.setDescrizione(descrizione);
        tv.setAbilitato(true);
        tv.setPagaTerzi(false);
        tv.setBoAbilitato(false);
        tv.setPagAbilitato(false);
        tv.setAvvMailPromAvvAbilitato(false);
        tv.setAvvMailPromRicAbilitato(false);
        tv.setAvvMailPromScadAbilitato(false);
        tv.setAvvAppIoPromAvvAbilitato(false);
        tv.setAvvAppIoPromRicAbilitato(false);
        tv.setAvvAppIoPromScadAbilitato(false);
        return tv;
    }

    private DominioEntity creaDominio(String codDominio, String ragioneSociale) {
        return dominioRepository.save(DominioEntity.builder()
                .codDominio(codDominio)
                .ragioneSociale(ragioneSociale)
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());
    }

    /**
     * Configura sia {@code tipi_vers_domini} (idTipoPendenza) sia {@code tributi} (codEntrata
     * di RIFERIMENTO_ENTRATA — bug del lead, 2026-09-28: {@code VocePendenza.codEntrata} va
     * risolto contro l'anagrafica reale, non e' piu' una stringa libera) per questo dominio:
     * ogni test di questa classe usa sempre lo stesso {@code codEntrata}
     * ({@code DIRITTI_SEGRETERIA}) delle voci, su domini diversi (M13).
     */
    private void configuraTipoVersamentoPerDominio(String codDominio) {
        DominioEntity dominio = dominioRepository.findByCodDominio(codDominio).orElseThrow();
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
        rendicontazioneRepository.deleteAll();
        flussoRendicontazioneRepository.deleteAll();
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

    /**
     * Vedi Javadoc dell'omologo in {@code PosizioneDebitoriaControllerTest}: diritti pieni
     * ("RW") sul servizio "API Pendenze" — questa classe usa POST (setup dei dati via l'altro
     * controller) e GET (l'endpoint sotto test), entrambi richiedono l'ACL.
     */
    private UtenzaEntity creaUtenza(String principal, String password) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
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
     * {@code numeroAvviso} non indicato nella richiesta (a differenza di
     * {@code PosizioneDebitoriaControllerTest}, che ne verifica solo il prefisso): il formato
     * pagoPA di un numero avviso valido include un check digit mod-93 dipendente da un
     * progressivo interno — costruirne uno a mano nel test rischia di produrne uno che
     * {@code GeneratoreIuvStandard#convertiDaNumeroAvviso} rifiuta. Si lascia percio' generare
     * IUV/numero avviso reali da {@code GeneratoreIuvStandard} (nessuno stub, come nell'altro
     * test) e si recupera il valore effettivo dalla risposta del POST, da riusare in seguito
     * per la ricerca.
     *
     * @return il {@code numeroAvviso} generato per la pendenza appena creata
     */
    private String creaPosizioneConPendenza(String idPosizioneDebitoria, String codDominio, String idPendenza)
            throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "%s",
                  "idDominio": "%s",
                  "descrizione": "test ricerca pendenze",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
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

    /**
     * Come {@link #creaPosizioneConPendenza}, ma con un {@code numeroAvviso} esplicito nella
     * richiesta invece di lasciarlo generare: il servizio lo accetta senza toccarlo se e'
     * accompagnato da un IUV ricavabile per conversione (vedi Javadoc di
     * {@code PosizioneDebitoriaService#crea}). Usato solo per gli scenari M13 (stesso
     * {@code numeroAvviso} riusato su un secondo dominio): valido perche' il check digit
     * mod-93 di {@code IuvUtils} dipende solo da {@code auxDigit} (uguale su entrambi i
     * domini di test), non dall'identita' del dominio.
     */
    private void creaPosizioneConPendenzaENumeroAvviso(String idPosizioneDebitoria, String codDominio,
            String idPendenza, String numeroAvviso) throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "%s",
                  "idDominio": "%s",
                  "descrizione": "test ricerca pendenze",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "%s",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "numeroAvviso": "%s",
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-%s", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(idPosizioneDebitoria, codDominio, idPendenza, numeroAvviso, idPendenza);

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} trova la pendenza per numeroAvviso, comprensiva di "
            + "opzionePagamento e posizioneDebitoria")
    void findPendenzeTrovaLaPendenzaPerNumeroAvviso() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-nav-1", "12345678901", "pendenza-nav-1");

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("total", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.totalResults").value(1))
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].idPendenza").value("pendenza-nav-1"))
                .andExpect(jsonPath("$.results[0].numeroAvviso").value(numeroAvviso))
                .andExpect(jsonPath("$.results[0].idDominio").value("12345678901"))
                .andExpect(jsonPath("$.results[0].opzionePagamento.tipologia").value("SOLUZIONE_UNICA"))
                .andExpect(jsonPath("$.results[0].posizioneDebitoria.idPosizioneDebitoria").value("pos-nav-1"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} restituisce 400 (non 500) se numeroAvviso manca, obbligatorio")
    void findPendenzeRestituisce400SeNumeroAvvisoMancante() throws Exception {
        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} restituisce 400 se numeroAvviso non rispetta il formato (18 cifre)")
    void findPendenzeRestituisce400SeNumeroAvvisoNonValido() throws Exception {
        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", "non-un-numero-avviso"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("M13: lo stesso numeroAvviso puo' esistere su domini diversi — senza idDominio "
            + "trova entrambe le pendenze, con idDominio filtra a una sola")
    void findPendenzeConStessoNumeroAvvisoSuDominiDiversiFiltraConIdDominio() throws Exception {
        creaDominio("22222222222", "Secondo Comune di Test");
        configuraTipoVersamentoPerDominio("22222222222");

        String numeroAvviso = creaPosizioneConPendenza("pos-m13-dominio-1", "12345678901",
                "pendenza-m13-dominio-1");
        creaPosizioneConPendenzaENumeroAvviso("pos-m13-dominio-2", "22222222222", "pendenza-m13-dominio-2",
                numeroAvviso);

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("total", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.totalResults").value(2))
                .andExpect(jsonPath("$.results.length()").value(2));

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("idDominio", "22222222222")
                        .param("total", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.totalResults").value(1))
                .andExpect(jsonPath("$.results[0].idDominio").value("22222222222"))
                .andExpect(jsonPath("$.results[0].idPendenza").value("pendenza-m13-dominio-2"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} restituisce 404 se idDominio non corrisponde a nessun dominio censito")
    void findPendenzeRestituisce404SeIdDominioNonEsiste() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-dominio-ignoto", "12345678901",
                "pendenza-dominio-ignoto");

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("idDominio", "00000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} pagina a offset (total=false, default): hasNextPage=true quando ce ne "
            + "sono altri, mantenendo numeroAvviso/idDominio su page=2")
    void findPendenzePaginaAOffsetSegnalaHasNextPage() throws Exception {
        creaDominio("22222222222", "Secondo Comune di Test");
        configuraTipoVersamentoPerDominio("22222222222");

        String numeroAvviso = creaPosizioneConPendenza("pos-pagina-1", "12345678901", "pendenza-pagina-1");
        creaPosizioneConPendenzaENumeroAvviso("pos-pagina-2", "22222222222", "pendenza-pagina-2", numeroAvviso);

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.hasNextPage").value(true))
                .andExpect(jsonPath("$.pagination.totalResults").doesNotExist())
                .andExpect(jsonPath("$.results.length()").value(1));

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("limit", "1")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.hasNextPage").value(false))
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} applica fields: restituisce solo i campi richiesti")
    void findPendenzeApplicaFields() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-fields-1", "12345678901", "pendenza-fields-1");

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("fields", "idPendenza"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].idPendenza").exists())
                .andExpect(jsonPath("$.results[0].numeroAvviso").doesNotExist())
                .andExpect(jsonPath("$.results[0].posizioneDebitoria").doesNotExist())
                .andExpect(jsonPath("$.results[0].opzionePagamento").doesNotExist());
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} paginazione a cursore: nextCursor porta al resto dei risultati "
            + "senza sovrapposizioni")
    void findPendenzeCursoreScorreSenzaSovrapposizioni() throws Exception {
        creaDominio("22222222222", "Secondo Comune di Test");
        configuraTipoVersamentoPerDominio("22222222222");

        String numeroAvviso = creaPosizioneConPendenza("pos-cursore-1", "12345678901", "pendenza-cursore-1");
        creaPosizioneConPendenzaENumeroAvviso("pos-cursore-2", "22222222222", "pendenza-cursore-2", numeroAvviso);

        org.springframework.test.web.servlet.MvcResult primaPagina = mockMvc.perform(get("/pendenze/{idA2A}",
                        "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("cursor", "")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").exists())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andReturn();

        String nextCursor = com.jayway.jsonpath.JsonPath.read(
                primaPagina.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", numeroAvviso)
                        .param("cursor", nextCursor)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A} rifiuta con 400 'page' e 'cursor' insieme")
    void findPendenzeRifiutaPageECursoreInsieme() throws Exception {
        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-TEST")
                        .param("numeroAvviso", "123456789012345678")
                        .param("page", "1")
                        .param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute restituisce l'elenco, piu' recenti prima, "
            + "con 'tipo' mappato dal valore legacy di 'versione'")
    void findRicevutePendenzaRestituisceElenco() throws Exception {
        creaPosizioneConPendenza("pos-ricevute-1", "12345678901", "pendenza-ricevute-1");
        OffsetDateTime adesso = OffsetDateTime.now();
        ricevutaPersistita("pendenza-ricevute-1", "iur-vecchia", "SANP_230", adesso.minusDays(1));
        ricevutaPersistita("pendenza-ricevute-1", "iur-recente", "SANP_321_V2", adesso);

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute", "A2A-TEST", "pendenza-ricevute-1")
                        .param("sort", "data:desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].iur").value("iur-recente"))
                .andExpect(jsonPath("$.results[0].tipo").value("ctReceiptV2"))
                .andExpect(jsonPath("$.results[1].iur").value("iur-vecchia"))
                .andExpect(jsonPath("$.results[1].tipo").value("ctRicevutaTelematica"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute esclude le rpt per cui la ricevuta non e' "
            + "ancora arrivata e restituisce 404 se la pendenza non esiste")
    void findRicevutePendenzaEscludeRptInAttesaERestituisce404SeNonEsiste() throws Exception {
        creaPosizioneConPendenza("pos-ricevute-attesa", "12345678901", "pendenza-ricevute-attesa");
        ricevutaPersistita("pendenza-ricevute-attesa", "iur-arrivata", "SANP_240", OffsetDateTime.now());
        Pendenza pendenza = pendenzaRepository.findAll().stream()
                .filter(p -> "pendenza-ricevute-attesa".equals(p.getIdPendenza()))
                .findFirst().orElseThrow();
        Rpt inAttesa = new Rpt();
        inAttesa.setIdVersamento(pendenza.getId());
        inAttesa.setIuv(pendenza.getIuv());
        inAttesa.setIur("iur-in-attesa");
        inAttesa.setCodDominio("12345678901");
        inAttesa.setVersione("SANP_240");
        rptRepository.save(inAttesa);

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute", "A2A-TEST", "pendenza-ricevute-attesa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].iur").value("iur-arrivata"));

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute", "A2A-TEST", "pendenza-inesistente"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute paginazione a cursore: nextCursor porta al "
            + "resto dei risultati senza sovrapposizioni")
    void findRicevutePendenzaCursoreScorreSenzaSovrapposizioni() throws Exception {
        creaPosizioneConPendenza("pos-ricevute-cursore", "12345678901", "pendenza-ricevute-cursore");
        OffsetDateTime adesso = OffsetDateTime.now();
        ricevutaPersistita("pendenza-ricevute-cursore", "iur-1", "SANP_240", adesso.minusDays(2));
        ricevutaPersistita("pendenza-ricevute-cursore", "iur-2", "SANP_240", adesso.minusDays(1));
        ricevutaPersistita("pendenza-ricevute-cursore", "iur-3", "SANP_240", adesso);

        org.springframework.test.web.servlet.MvcResult primaPagina = mockMvc
                .perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute", "A2A-TEST", "pendenza-ricevute-cursore")
                        .param("cursor", "")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").exists())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].iur").value("iur-3"))
                .andExpect(jsonPath("$.results[1].iur").value("iur-2"))
                .andReturn();

        String nextCursor = com.jayway.jsonpath.JsonPath.read(
                primaPagina.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute", "A2A-TEST", "pendenza-ricevute-cursore")
                        .param("cursor", nextCursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].iur").value("iur-1"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute rifiuta con 400 'page' e 'cursor' insieme")
    void findRicevutePendenzaRifiutaPageECursoreInsieme() throws Exception {
        creaPosizioneConPendenza("pos-ricevute-mutex", "12345678901", "pendenza-ricevute-mutex");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute", "A2A-TEST", "pendenza-ricevute-mutex")
                        .param("page", "1")
                        .param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur} restituisce il dettaglio nel "
            + "formato ctReceipt, con 'tipo' coerente con quello dell'elenco")
    void getRicevutaPendenzaRestituisceCtReceipt() throws Exception {
        creaPosizioneConPendenza("pos-ricevuta-det-1", "12345678901", "pendenza-ricevuta-det-1");
        ricevutaPersistita("pendenza-ricevuta-det-1", "RT900000003", "RPTV2_RTV1", OffsetDateTime.now(),
                xmlFixture("rt-v2-ok.xml"));

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute/{iur}", "A2A-TEST",
                        "pendenza-ricevuta-det-1", "RT900000003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("ctReceipt"))
                .andExpect(jsonPath("$.receiptId").value("RT900000003"))
                .andExpect(jsonPath("$.fiscalCode").value("12345678901"))
                .andExpect(jsonPath("$.outcome").value("OK"))
                .andExpect(jsonPath("$.paymentAmount").value(10.00))
                .andExpect(jsonPath("$.debtor.fullName").value("Mario Rossi"))
                .andExpect(jsonPath("$.transferList[0].IBAN").value("IT60X0542811101000000123456"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur} restituisce il dettaglio nel "
            + "formato ctReceiptV2")
    void getRicevutaPendenzaRestituisceCtReceiptV2() throws Exception {
        creaPosizioneConPendenza("pos-ricevuta-det-2", "12345678901", "pendenza-ricevuta-det-2");
        ricevutaPersistita("pendenza-ricevuta-det-2", "RT900000001", "SANP_321_V2", OffsetDateTime.now(),
                xmlFixture("rt-v2_2-ok.xml"));

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute/{iur}", "A2A-TEST",
                        "pendenza-ricevuta-det-2", "RT900000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("ctReceiptV2"))
                .andExpect(jsonPath("$.receiptId").value("RT900000001"))
                .andExpect(jsonPath("$.officeName").value("Ufficio Tributi"))
                .andExpect(jsonPath("$.debtor.city").value("Roma"))
                .andExpect(jsonPath("$.paymentMethod").value("AD"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur} restituisce 404 se l'iur non esiste "
            + "o se la pendenza non ha ancora questa ricevuta")
    void getRicevutaPendenzaRestituisce404SeIurNonEsiste() throws Exception {
        creaPosizioneConPendenza("pos-ricevuta-404", "12345678901", "pendenza-ricevuta-404");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/ricevute/{iur}", "A2A-TEST",
                        "pendenza-ricevuta-404", "iur-inesistente"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni restituisce l'elenco, comprensivo dei dati "
            + "di testata del flusso (trn = flusso.iur, rename verificato contro il legacy)")
    void findRendicontazioniPendenzaRestituisceElenco() throws Exception {
        creaPosizioneConPendenza("pos-rend-1", "12345678901", "pendenza-rend-1");
        OffsetDateTime adesso = OffsetDateTime.now();
        rendicontazionePersistita("pendenza-rend-1", "flusso-rend-1", adesso, "iur-rend-1");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/rendicontazioni", "A2A-TEST", "pendenza-rend-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].iur").value("iur-rend-1"))
                .andExpect(jsonPath("$.results[0].esito").value(0))
                .andExpect(jsonPath("$.results[0].stato").value("OK"))
                .andExpect(jsonPath("$.results[0].flusso.idFlusso").value("flusso-rend-1"))
                .andExpect(jsonPath("$.results[0].flusso.trn").value("trn-flusso-rend-1"))
                .andExpect(jsonPath("$.results[0].flusso.stato").value("ACCETTATA"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni paginazione a cursore: nextCursor porta "
            + "al resto dei risultati senza sovrapposizioni, ordinati per flusso.dataOraFlusso desc")
    void findRendicontazioniPendenzaCursoreScorreSenzaSovrapposizioni() throws Exception {
        creaPosizioneConPendenza("pos-rend-cursore", "12345678901", "pendenza-rend-cursore");
        OffsetDateTime adesso = OffsetDateTime.now();
        rendicontazionePersistita("pendenza-rend-cursore", "flusso-rc-1", adesso.minusDays(2), "iur-1");
        rendicontazionePersistita("pendenza-rend-cursore", "flusso-rc-2", adesso.minusDays(1), "iur-2");
        rendicontazionePersistita("pendenza-rend-cursore", "flusso-rc-3", adesso, "iur-3");

        org.springframework.test.web.servlet.MvcResult primaPagina = mockMvc
                .perform(get("/pendenze/{idA2A}/{idPendenza}/rendicontazioni", "A2A-TEST", "pendenza-rend-cursore")
                        .param("cursor", "")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").exists())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].iur").value("iur-3"))
                .andExpect(jsonPath("$.results[1].iur").value("iur-2"))
                .andReturn();

        String nextCursor = com.jayway.jsonpath.JsonPath.read(
                primaPagina.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/rendicontazioni", "A2A-TEST", "pendenza-rend-cursore")
                        .param("cursor", nextCursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].iur").value("iur-1"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni rifiuta con 400 'page' e 'cursor' insieme")
    void findRendicontazioniPendenzaRifiutaPageECursoreInsieme() throws Exception {
        creaPosizioneConPendenza("pos-rend-mutex", "12345678901", "pendenza-rend-mutex");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/rendicontazioni", "A2A-TEST", "pendenza-rend-mutex")
                        .param("page", "1")
                        .param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza} restituisce il dettaglio completo, comprensivo "
            + "di voci, opzione di pagamento e posizione debitoria di appartenenza")
    void getPendenzaRestituisceIlDettaglioCompleto() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-get-1", "12345678901", "pendenza-get-1");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}", "A2A-TEST", "pendenza-get-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idA2A").value("A2A-TEST"))
                .andExpect(jsonPath("$.idPendenza").value("pendenza-get-1"))
                .andExpect(jsonPath("$.idDominio").value("12345678901"))
                .andExpect(jsonPath("$.stato").value("NON_ESEGUITO"))
                .andExpect(jsonPath("$.numeroAvviso").value(numeroAvviso))
                .andExpect(jsonPath("$.iuv").value(notNullValue()))
                .andExpect(jsonPath("$.opzionePagamento.tipologia").value("SOLUZIONE_UNICA"))
                .andExpect(jsonPath("$.opzionePagamento.stato").value("DISPONIBILE"))
                .andExpect(jsonPath("$.posizioneDebitoria.idPosizioneDebitoria").value("pos-get-1"))
                .andExpect(jsonPath("$.voci.length()").value(1))
                .andExpect(jsonPath("$.voci[0].idVocePendenza").value("voce-pendenza-get-1"))
                .andExpect(jsonPath("$.voci[0].codEntrata").value("DIRITTI_SEGRETERIA"))
                .andExpect(jsonPath("$.voci[0].stato").value("NON_ESEGUITO"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza} restituisce 404 se la pendenza non esiste")
    void getPendenzaRestituisce404SeNonEsiste() throws Exception {
        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}", "A2A-TEST", "pendenza-inesistente"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * Le pendenze create dall'helper di setup sono sempre {@code NON_ESEGUITO} senza scadenza:
     * per i test sulla mappatura STATO -> {@code Avviso.stato} si forza direttamente lo stato
     * (e all'occorrenza la scadenza) sull'entita' gia' persistita, senza passare per un secondo
     * endpoint di transizione che questo servizio non espone ancora.
     */
    private Rpt ricevutaPersistita(String idPendenza, String iur, String versione, OffsetDateTime dataMsgRicevuta) {
        return ricevutaPersistita(idPendenza, iur, versione, dataMsgRicevuta,
                "<Receipt/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Rpt ricevutaPersistita(String idPendenza, String iur, String versione, OffsetDateTime dataMsgRicevuta,
            byte[] xmlRt) {
        Pendenza pendenza = pendenzaRepository.findAll().stream()
                .filter(p -> idPendenza.equals(p.getIdPendenza()))
                .findFirst().orElseThrow();
        Rpt rpt = new Rpt();
        rpt.setIdVersamento(pendenza.getId());
        rpt.setIuv(pendenza.getIuv());
        rpt.setIur(iur);
        rpt.setCodDominio("12345678901");
        rpt.setXmlRt(xmlRt);
        rpt.setDataMsgRicevuta(dataMsgRicevuta);
        rpt.setVersione(versione);
        return rptRepository.save(rpt);
    }

    private Rendicontazione rendicontazionePersistita(String idPendenza, String codFlusso,
            OffsetDateTime dataOraFlusso, String iurRendicontazione) {
        Pendenza pendenza = pendenzaRepository.findAll().stream()
                .filter(p -> idPendenza.equals(p.getIdPendenza()))
                .findFirst().orElseThrow();
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();

        FlussoRendicontazione flusso = new FlussoRendicontazione();
        flusso.setIdDominio(dominio.getId());
        flusso.setCodDominio(dominio.getCodDominio());
        flusso.setCodFlusso(codFlusso);
        flusso.setDataOraFlusso(dataOraFlusso);
        flusso.setIur("trn-" + codFlusso);
        flusso.setDataAcquisizione(dataOraFlusso);
        flusso.setDataRegolamento(dataOraFlusso);
        flusso.setCodPsp("PSP-1");
        flusso.setNumeroPagamenti(1L);
        flusso.setImportoTotale(pendenza.getImporto());
        flusso.setStato(StatoFlussoRendicontazione.ACCETTATA);
        flusso.setRevisione(1L);
        flusso.setObsoleto(false);
        flussoRendicontazioneRepository.save(flusso);

        Rendicontazione rendicontazione = new Rendicontazione();
        rendicontazione.setFlusso(flusso);
        rendicontazione.setIuv(pendenza.getIuv());
        rendicontazione.setIur(iurRendicontazione);
        rendicontazione.setImportoPagato(pendenza.getImporto());
        rendicontazione.setEsito(0);
        rendicontazione.setData(dataOraFlusso);
        rendicontazione.setStato(StatoRendicontazione.OK);
        return rendicontazioneRepository.save(rendicontazione);
    }

    private static byte[] xmlFixture(String nome) throws java.io.IOException {
        try (var in = PendenzaControllerTest.class.getResourceAsStream("/rt/" + nome)) {
            return in.readAllBytes();
        }
    }

    private void forzaStato(String idPendenza, StatoPendenza stato) {
        Pendenza pendenza = pendenzaRepository.findAll().stream()
                .filter(p -> idPendenza.equals(p.getIdPendenza()))
                .findFirst().orElseThrow();
        pendenza.setStato(stato);
        pendenzaRepository.save(pendenza);
    }

    private void forzaStatoEScadenzaPassata(String idPendenza, StatoPendenza stato) {
        Pendenza pendenza = pendenzaRepository.findAll().stream()
                .filter(p -> idPendenza.equals(p.getIdPendenza()))
                .findFirst().orElseThrow();
        pendenza.setStato(stato);
        pendenza.setDataScadenzaAvviso(OffsetDateTime.now().minusDays(1));
        pendenzaRepository.save(pendenza);
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa restituisce l'avviso in JSON, con qrcode/barcode")
    void getStampaPendenzaRestituisceAvvisoJson() throws Exception {
        String numeroAvviso = creaPosizioneConPendenza("pos-stampa-1", "12345678901", "pendenza-stampa-1");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.stato").value("NON_ESEGUITA"))
                .andExpect(jsonPath("$.importo").value(16.00))
                .andExpect(jsonPath("$.idDominio").value("12345678901"))
                .andExpect(jsonPath("$.numeroAvviso").value(numeroAvviso))
                .andExpect(jsonPath("$.descrizione").value("test ricerca pendenze"))
                .andExpect(jsonPath("$.qrcode").value(notNullValue()))
                .andExpect(jsonPath("$.barcode").value(notNullValue()));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa mappa ESEGUITO su stato DUPLICATA")
    void getStampaPendenzaMappaEseguitoSuDuplicata() throws Exception {
        creaPosizioneConPendenza("pos-stampa-eseguito", "12345678901", "pendenza-stampa-eseguito");
        forzaStato("pendenza-stampa-eseguito", StatoPendenza.ESEGUITO);

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-eseguito"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("DUPLICATA"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa mappa NON_ESEGUITO con scadenza passata su stato SCADUTA")
    void getStampaPendenzaMappaNonEseguitoConScadenzaPassataSuScaduta() throws Exception {
        creaPosizioneConPendenza("pos-stampa-scaduta", "12345678901", "pendenza-stampa-scaduta");
        forzaStatoEScadenzaPassata("pendenza-stampa-scaduta", StatoPendenza.NON_ESEGUITO);

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-scaduta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("SCADUTA"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa mappa uno stato ambiguo (ANOMALO/ESEGUITO_SENZA_RPT/"
            + "INCASSATO) su stato SCONOSCIUTA, come fa govpay-portal-api per lo stesso caso")
    void getStampaPendenzaMappaStatoAmbiguoSuSconosciuta() throws Exception {
        creaPosizioneConPendenza("pos-stampa-anomalo", "12345678901", "pendenza-stampa-anomalo");
        forzaStato("pendenza-stampa-anomalo", StatoPendenza.ANOMALO);

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-anomalo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").value("SCONOSCIUTA"));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa restituisce 404 se la pendenza non esiste")
    void getStampaPendenzaRestituisce404SeNonEsiste() throws Exception {
        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-inesistente"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa restituisce 406 per un Accept non compatibile "
            + "ne' con JSON ne' con PDF")
    void getStampaPendenzaRestituisce406PerAcceptNonSupportato() throws Exception {
        creaPosizioneConPendenza("pos-stampa-406", "12345678901", "pendenza-stampa-406");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-406")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.status").value(406));
    }

    /**
     * {@code application/json;q=0} esclude esplicitamente JSON (non e' solo "meno preferito"):
     * con {@code informativaImporto} valorizzato (400 sul ramo JSON, nessun campo corrispondente
     * in {@link it.govpay.pendenze.api.model.Avviso}), il 503 (non il 400) conferma che e' stato
     * scelto il ramo PDF nonostante JSON sia elencato per primo nell'Accept.
     */
    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa rispetta q=0: Accept 'application/json;q=0, "
            + "application/pdf' sceglie il PDF anche se JSON e' elencato per primo")
    void getStampaPendenzaRispettaQualityZeroEscludendoJson() throws Exception {
        creaPosizioneConPendenza("pos-stampa-q0", "12345678901", "pendenza-stampa-q0");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-q0")
                        .header("Accept", "application/json;q=0, application/pdf")
                        .param("informativaImporto", "Testo personalizzato"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    /**
     * Il wildcard generico non deve "resuscitare" un'esclusione specifica: la qualita' di JSON
     * va presa dal match piu' specifico ({@code application/json;q=0}), non dal wildcard
     * ({@code *}/{@code *};q=1) solo perche' quest'ultimo ha una qualita' maggiore.
     */
    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa rispetta q=0 anche se un wildcard generico con "
            + "qualita' maggiore e' presente: Accept 'application/json;q=0, */*;q=1' non riabilita JSON")
    void getStampaPendenzaIlWildcardNonResuscitaUnFormatoEscluso() throws Exception {
        creaPosizioneConPendenza("pos-stampa-wild", "12345678901", "pendenza-stampa-wild");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-wild")
                        .header("Accept", "application/json;q=0, */*;q=1")
                        .param("informativaImporto", "Testo personalizzato"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa rispetta la priorita' per qualita': Accept "
            + "'application/pdf;q=0.5, application/json' sceglie JSON (qualita' maggiore) anche se PDF e' "
            + "elencato per primo")
    void getStampaPendenzaRispettaPrioritaPerQualita() throws Exception {
        creaPosizioneConPendenza("pos-stampa-prio", "12345678901", "pendenza-stampa-prio");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-prio")
                        .header("Accept", "application/pdf;q=0.5, application/json"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa restituisce 400 se causaleTradotta e' valorizzato "
            + "sulla variante application/json: non ha alcun effetto li', si applica solo al PDF")
    void getStampaPendenzaJsonRestituisce400SeCausaleTradottaValorizzata() throws Exception {
        creaPosizioneConPendenza("pos-stampa-causale", "12345678901", "pendenza-stampa-causale");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-causale")
                        .param("causaleTradotta", "Payment notice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Il 503 (non il 400) conferma che causaleTradotta e' stato accettato e inoltrato verso
     * govpay-stampe (va in second_language.title, l'oggetto del pagamento tradotto).
     */
    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa (Accept: application/pdf) accetta causaleTradotta "
            + "e lo inoltra a govpay-stampe")
    void getStampaPendenzaPdfAccettaCausaleTradotta() throws Exception {
        creaPosizioneConPendenza("pos-stampa-causale-pdf", "12345678901", "pendenza-stampa-causale-pdf");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-causale-pdf")
                        .accept(MediaType.APPLICATION_PDF)
                        .param("linguaSecondaria", "EN")
                        .param("causaleTradotta", "Payment notice"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa restituisce 400 se informativaImportoTradotta e' "
            + "valorizzato sulla variante application/json: non ha alcun effetto li', si applica solo al PDF")
    void getStampaPendenzaJsonRestituisce400SeInformativaImportoTradottaValorizzata() throws Exception {
        creaPosizioneConPendenza("pos-stampa-info-tradotta", "12345678901", "pendenza-stampa-info-tradotta");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-info-tradotta")
                        .param("informativaImportoTradotta", "Testo tradotto"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Il 503 (non il 400) conferma che informativaImportoTradotta e' stato accettato e inoltrato
     * verso govpay-stampe (va in second_language.informativa_importo).
     */
    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa (Accept: application/pdf) accetta "
            + "informativaImportoTradotta e lo inoltra a govpay-stampe")
    void getStampaPendenzaPdfAccettaInformativaImportoTradotta() throws Exception {
        creaPosizioneConPendenza("pos-stampa-info-tra-pdf", "12345678901", "pendenza-stampa-info-tra-pdf");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-info-tra-pdf")
                        .accept(MediaType.APPLICATION_PDF)
                        .param("linguaSecondaria", "EN")
                        .param("causaleTradotta", "Secretarial fees")
                        .param("informativaImporto", "Testo personalizzato")
                        .param("informativaImportoTradotta", "Testo tradotto"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa (Accept: application/pdf) restituisce 400 se "
            + "linguaSecondaria e' specificato senza causaleTradotta: un avviso bilingue senza causale tradotta "
            + "non e' un documento valido")
    void getStampaPendenzaPdfRestituisce400SeLinguaSecondariaSenzaCausaleTradotta() throws Exception {
        creaPosizioneConPendenza("pos-stampa-bil-no-causale", "12345678901", "pendenza-stampa-bil-no-causale");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-bil-no-causale")
                        .accept(MediaType.APPLICATION_PDF)
                        .param("linguaSecondaria", "EN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa (Accept: application/pdf) restituisce 503 se "
            + "app.stampe.base-url non e' configurato (default di questo contesto di test)")
    void getStampaPendenzaPdfRestituisce503SeStampeNonConfigurato() throws Exception {
        creaPosizioneConPendenza("pos-stampa-pdf-503", "12345678901", "pendenza-stampa-pdf-503");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-pdf-503")
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa restituisce 400 se informativaImporto e' valorizzato "
            + "sulla variante application/json: non ha alcun effetto li', si applica solo al PDF")
    void getStampaPendenzaJsonRestituisce400SeInformativaImportoValorizzata() throws Exception {
        creaPosizioneConPendenza("pos-stampa-info-json", "12345678901", "pendenza-stampa-info-json");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-info-json")
                        .param("informativaImporto", "Testo personalizzato"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Il 503 (non il 400) conferma che informativaImporto e' stato accettato e inoltrato verso
     * govpay-stampe: con app.stampe.base-url non configurato (default di test) la richiesta
     * arriva comunque a tentare la chiamata, fallendo per quello, non per la validazione.
     */
    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa (Accept: application/pdf) accetta informativaImporto "
            + "e lo inoltra a govpay-stampe")
    void getStampaPendenzaPdfAccettaInformativaImporto() throws Exception {
        creaPosizioneConPendenza("pos-stampa-info-pdf", "12345678901", "pendenza-stampa-info-pdf");

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-info-pdf")
                        .accept(MediaType.APPLICATION_PDF)
                        .param("informativaImporto", "Testo personalizzato"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }

    /**
     * Una pendenza con una voce BOLLO (tipoBollo/hashDocumento/provinciaResidenza tutti
     * valorizzati, vedi schema {@code Bollo}) e' una Marca da Bollo Telematica: l'avviso PDF
     * non le si applica, a prescindere da {@code app.stampe.base-url} (il controllo avviene
     * PRIMA di contattare govpay-stampe).
     */
    @Test
    @DisplayName("GET /pendenze/{idA2A}/{idPendenza}/stampa (Accept: application/pdf) restituisce 422 per una "
            + "pendenza con Marca da Bollo Telematica")
    void getStampaPendenzaPdfRestituisce422PerPendenzaMbt() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-stampa-pdf-mbt",
                  "idDominio": "12345678901",
                  "descrizione": "test MBT",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-stampa-pdf-mbt",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "BOLLO", "idVocePendenza": "voce-mbt", "importo": 16.00,
                                  "descrizione": "test", "tipoBollo": "01", "hashDocumento": "aGFzaA==",
                                  "provinciaResidenza": "RM", "tassonomia": "9/0101002IM/" } ]
                    } ]
                  } ]
                }
                """;
        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/pendenze/{idA2A}/{idPendenza}/stampa", "A2A-TEST", "pendenza-stampa-pdf-mbt")
                        .accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(422));
    }
}
