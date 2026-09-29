package it.govpay.pendenze.pendenza;

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
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
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
        posizioneDebitoriaRepository.deleteAll();
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        tributoRepository.deleteAll();
        tipoTributoRepository.deleteAll();
        dominioRepository.deleteAll();
        applicazioneRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    /**
     * Vedi Javadoc dell'omologo in {@code PosizioneDebitoriaControllerTest}.
     */
    private UtenzaEntity creaUtenza(String principal, String password) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        utenza.setPassword(passwordEncoder.encode(password));
        return utenzaRepository.save(utenza);
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
}
