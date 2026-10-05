package it.govpay.pendenze.posizionedebitoria;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TipoVersamentoEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TipoVersamentoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.security.UtenzaTipoVersamentoEntity;
import it.govpay.pendenze.security.UtenzaTipoVersamentoRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Verifica {@code POST /posizioni-debitorie/{idA2A}} a livello di integrazione (contesto
 * Spring completo, DB H2 con lo schema reale — vedi {@code application-test.properties}):
 * il percorso felice e le risoluzioni di anagrafica (idA2A/idDominio/idTipoPendenza) che
 * questo endpoint introduce per primo in questo progetto.
 *
 * <p>{@code GeneratoreIuvStandard} (registrato automaticamente da
 * {@code PendenzeAutoConfiguration}, vedi il suo Javadoc) genera IUV/numeroAvviso reali:
 * nessuno stub di test necessario, a differenza di quanto inizialmente ipotizzato — il
 * dominio di test ha comunque bisogno di {@code auxDigit} valorizzato (colonna NOT NULL).</p>
 *
 * <p><b>Niente {@code @Transactional} sulla classe</b> (bug del lead, 2026-09-27, trovato
 * proprio rimuovendolo): {@code crea()} e' {@code @Transactional} di suo — se il test stesso
 * fosse anche transazionale, la propria transazione (propagazione REQUIRED) si limiterebbe
 * ad aderire a quella del test, che resta aperta per l'intera chiamata MockMvc e non commita
 * mai realmente finche' il test non finisce (rollback). Questo maschererebbe esattamente la
 * classe di bug per cui questo endpoint e' stato controllato in revisione (es. una relazione
 * LAZY letta dal converter dopo che {@code crea()} e' davvero tornato, con la sessione
 * Hibernate ormai chiusa — riproducibile solo se la transazione di {@code crea()} chiude
 * per davvero al suo ritorno, non se resta annidata in quella del test). La pulizia tra i
 * test avviene quindi esplicitamente in {@link #pulisci()}, non per rollback.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PosizioneDebitoriaControllerTest.BasicAuthDiDefaultConfig.class)
class PosizioneDebitoriaControllerTest {

    private static final String PRINCIPAL = "A2A-TEST";
    private static final String PASSWORD = "test-password";

    /**
     * Applica di default le credenziali dell'applicazione "A2A-TEST" a ogni richiesta
     * {@code mockMvc.perform(...)} di questa classe (bug del lead, 2026-09-29: senza
     * autenticazione ogni richiesta tornerebbe 401 prima di raggiungere il controller —
     * {@code verificaIdA2A} e' ora chiamata a inizio di ogni metodo). I pochi test che
     * impersonano un'applicazione diversa (es. {@code A2A-DOMINIO-IGNOTO}) sovrascrivono
     * queste credenziali con {@code .with(httpBasic(...))} sulla singola richiesta.
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
    private UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository;

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
    private IbanAccreditoRepository ibanAccreditoRepository;

    @Autowired
    private UnitaOperativaRepository unitaOperativaRepository;

    @Autowired
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    @Autowired
    private PosizioneDebitoriaService posizioneDebitoriaService;

    private Long idDominio;

    @BeforeEach
    void creaAnagrafiche() {
        UtenzaEntity utenza = creaUtenza(PRINCIPAL, PASSWORD);

        ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                .codApplicazione("A2A-TEST")
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenza.getId())
                .build();
        applicazioneRepository.save(applicazione);

        DominioEntity dominio = DominioEntity.builder()
                .codDominio("12345678901")
                .ragioneSociale("Comune di Test")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                // auxDigit 1 (EC monointermediato, IUV numerico di 17 cifre): a differenza di
                // 0 non richiede l'application code della stazione del dominio, che qui non
                // configuriamo (fuori scopo di questo test).
                .auxDigit(1)
                .build();
        dominio = dominioRepository.save(dominio);
        idDominio = dominio.getId();

        TipoVersamentoEntity tipoVersamento = nuovoTipoVersamento("DIRITTI_SEGRETERIA", "Diritti di segreteria");
        tipoVersamentoRepository.save(tipoVersamento);

        TipoVersamentoDominioEntity override = new TipoVersamentoDominioEntity();
        override.setTipoVersamento(tipoVersamento);
        override.setDominio(dominioRepository.findById(idDominio).orElseThrow());
        tipoVersamentoDominioRepository.save(override);

        configuraTributoPerDominio("DIRITTI_SEGRETERIA", dominio);
    }

    /**
     * {@code codEntrata} di una voce RIFERIMENTO_ENTRATA va risolto contro {@code tipi_tributo}
     * (catalogo globale)/{@code tributi} (override per questo dominio) — bug del lead,
     * 2026-09-28: {@code VocePendenza} non aveva mai questa risoluzione, {@code codEntrata}
     * era una colonna aggiunta senza legame con l'anagrafica reale.
     */
    private void configuraTributoPerDominio(String codEntrata, DominioEntity dominio) {
        configuraTributoPerDominio(codEntrata, dominio, true);
    }

    private void configuraTributoPerDominio(String codEntrata, DominioEntity dominio, boolean abilitato) {
        TipoTributoEntity tipoTributo = new TipoTributoEntity();
        tipoTributo.setCodTributo(codEntrata);
        tipoTributo.setDescrizione(codEntrata);
        tipoTributoRepository.save(tipoTributo);

        TributoEntity tributo = new TributoEntity();
        tributo.setAbilitato(abilitato);
        tributo.setDominio(dominio);
        tributo.setTipoTributo(tipoTributo);
        tributoRepository.save(tributo);
    }

    /**
     * {@code tipi_versamento} ha 9 colonne {@code NOT NULL DEFAULT false} (configurazione
     * BO/PAG/avvisatura mail/AppIO, mai {@code null} su una riga reale) — valorizzate qui
     * esplicitamente a {@code false} ("nessuna integrazione attiva"), altrimenti l'INSERT
     * fallisce (il default SQL non si applica: Hibernate invia sempre un valore esplicito,
     * {@code NULL} se il campo Java non e' stato impostato).
     */
    private TipoVersamentoEntity nuovoTipoVersamento(String codTipoVersamento, String descrizione) {
        TipoVersamentoEntity tipoVersamento = new TipoVersamentoEntity();
        tipoVersamento.setCodTipoVersamento(codTipoVersamento);
        tipoVersamento.setDescrizione(descrizione);
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
        return tipoVersamento;
    }

    /**
     * Sostituisce il rollback automatico che avrebbe dato {@code @Transactional} — vedi
     * Javadoc di classe sul perche' non lo usiamo qui. Ordine: prima l'aggregato pendenza
     * (cascade su opzioni/pendenze/voci/soggetti), poi le anagrafiche — {@code tipi_vers_domini}
     * ha una FK reale verso {@code tipi_versamento} nello schema di test, va rimossa prima.
     */
    @AfterEach
    void pulisci() {
        posizioneDebitoriaRepository.deleteAll();
        utenzaTipoVersamentoRepository.deleteAll();
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        tributoRepository.deleteAll();
        tipoTributoRepository.deleteAll();
        ibanAccreditoRepository.deleteAll();
        unitaOperativaRepository.deleteAll();
        dominioRepository.deleteAll();
        applicazioneRepository.deleteAll();
        aclRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    /**
     * Nuova utenza per l'autenticazione Basic (verificata dal filter di govpay-common-auth
     * tramite {@code PendenzeGovpayPrincipalLoader}) — password codificata con lo stesso
     * {@link GovpayPasswordEncoder} usato dalla libreria per la verifica. Diritti pieni
     * ("RW", lettura+scrittura) sul servizio "API Pendenze": i pochi test che devono
     * verificare il rifiuto per diritti insufficienti usano
     * {@link #creaUtenza(String, String, String)} con diritti espliciti.
     */
    private UtenzaEntity creaUtenza(String principal, String password) {
        return creaUtenza(principal, password, "RW");
    }

    /**
     * Vedi Javadoc di {@link #creaUtenza(String, String)}. {@code diritti} e' la stringa
     * grezza della colonna {@code acl.diritti} ("R"/"W"/"RW"/"" — vuoto o {@code null} per
     * non creare affatto la riga ACL, come un'applicazione mai abilitata al servizio).
     */
    private UtenzaEntity creaUtenza(String principal, String password, String diritti) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        // false di default (nessun'utenza di questa classe ha in realta' un'autorizzazione
        // "star" ai tipi versamento): le applicazioni "trusted" bypassano comunque il
        // controllo, vedi PosizioneDebitoriaMapper#verificaAutorizzazioneTipoVersamento.
        utenza.setAutorizzazioneTipiVersStar(false);
        utenza.setPassword(passwordEncoder.encode(password));
        utenza = utenzaRepository.save(utenza);

        if (diritti != null && !diritti.isBlank()) {
            AclEntity acl = new AclEntity();
            acl.setServizio("API Pendenze");
            acl.setDiritti(diritti);
            acl.setIdUtenza(utenza.getId());
            aclRepository.save(acl);
        }

        return utenza;
    }

    /**
     * Sovrascrive per una singola richiesta le credenziali di default applicate da
     * {@link BasicAuthDiDefaultConfig} (bug del lead, 2026-09-29: {@code .with(httpBasic(...))}
     * da solo AGGIUNGE un secondo header {@code Authorization} invece di sostituirlo —
     * {@code defaultRequest} lo ha gia' impostato prima che i {@code RequestPostProcessor}
     * vengano applicati — e il server autentica con il primo dei due, quello di default).
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor comeApplicazione(
            String principal, String password) {
        return request -> {
            request.removeHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
            return httpBasic(principal, password).postProcessRequest(request);
        };
    }

    @Test
    void creaUnaPosizioneDebitoriaConUnaPendenzaInUnicaSoluzione() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "CERT2026-000058",
                  "idDominio": "12345678901",
                  "descrizione": "Diritti di segreteria - certificato di residenza",
                  "soggettiDebitori": [
                    { "tipo": "F", "identificativo": "FRRPLA90C41H501Y", "anagrafica": "Paola Ferrari" }
                  ],
                  "opzioniPagamento": [
                    {
                      "tipologia": "SOLUZIONE_UNICA",
                      "pendenze": [
                        {
                          "idPendenza": "CERT2026-000058-UNICA",
                          "idTipoPendenza": "DIRITTI_SEGRETERIA",
                          "importo": 16.00,
                          "voci": [
                            {
                              "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "CERT2026-000058-UNICA_1",
                              "importo": 16.00,
                              "descrizione": "Diritti di segreteria - certificato di residenza",
                              "codEntrata": "DIRITTI_SEGRETERIA"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idA2A").value("A2A-TEST"))
                .andExpect(jsonPath("$.idPosizioneDebitoria").value("CERT2026-000058"))
                .andExpect(jsonPath("$.idDominio").value("12345678901"))
                .andExpect(jsonPath("$.opzioniPagamento[0].tipologia").value("SOLUZIONE_UNICA"))
                .andExpect(jsonPath("$.opzioniPagamento[0].stato").value("DISPONIBILE"))
                .andExpect(jsonPath("$.opzioniPagamento[0].idOpzionePagamento").value(notNullValue()))
                .andExpect(jsonPath("$.opzioniPagamento[0].pendenze[0].idPendenza")
                        .value("CERT2026-000058-UNICA"))
                .andExpect(jsonPath("$.opzioniPagamento[0].pendenze[0].idTipoPendenza")
                        .value("DIRITTI_SEGRETERIA"))
                .andExpect(jsonPath("$.opzioniPagamento[0].pendenze[0].numeroAvviso").value(notNullValue()))
                .andExpect(jsonPath("$.opzioniPagamento[0].pendenze[0].iuv").value(notNullValue()))
                // Bug del lead, 2026-09-27: toDto() non copiava soggettiDebitori — una
                // richiesta con un debitore tornava con "soggettiDebitori":[].
                .andExpect(jsonPath("$.soggettiDebitori.length()").value(1))
                .andExpect(jsonPath("$.soggettiDebitori[0].identificativo").value("FRRPLA90C41H501Y"))
                .andExpect(jsonPath("$.soggettiDebitori[0].anagrafica").value("Paola Ferrari"));
    }

    @Test
    @DisplayName("crea con successo una voce ENTRATA, risolvendo ibanAccredito/ibanAppoggio contro "
            + "l'anagrafica censita (bug del lead, 2026-09-28: prima erano stringhe libere, mai validate)")
    void creaUnaVoceEntrataRisolveIbanAccreditoEAppoggio() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        salvaIban("IT60X0542811101000000123456", dominio);
        salvaIban("IT60X0542811101000000999999", dominio);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-entrata-1",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-entrata-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "ENTRATA", "idVocePendenza": "voce-entrata-1", "importo": 16.00,
                                  "descrizione": "test", "ibanAccredito": "IT60X0542811101000000123456",
                                  "ibanAppoggio": "IT60X0542811101000000999999", "tassonomia": "9/0101002IM/" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("crea con successo una voce ENTRATA con tassonomia comprensiva del MotivoGiuridico libero "
            + "che alcuni clienti accodano con un ulteriore '/' (chiarimento del lead, 2026-09-28)")
    void creaUnaVoceEntrataConTassonomiaEMotivoGiuridicoLibero() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        salvaIban("IT60X0542811101000000123456", dominio);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-entrata-motivo",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-entrata-motivo",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "ENTRATA", "idVocePendenza": "voce-entrata-motivo", "importo": 16.00,
                                  "descrizione": "test", "ibanAccredito": "IT60X0542811101000000123456",
                                  "tassonomia": "9/0101002IM//rif.pratica123" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void salvaIban(String codIban, DominioEntity dominio) {
        salvaIban(codIban, dominio, true);
    }

    private void salvaIban(String codIban, DominioEntity dominio, boolean abilitato) {
        IbanAccreditoEntity iban = new IbanAccreditoEntity();
        iban.setCodIban(codIban);
        iban.setPostale(false);
        iban.setAbilitato(abilitato);
        iban.setDominio(dominio);
        ibanAccreditoRepository.save(iban);
    }

    private void salvaUnitaOperativa(String codUo, DominioEntity dominio, boolean abilitato) {
        UnitaOperativaEntity unitaOperativa = new UnitaOperativaEntity();
        unitaOperativa.setCodUo(codUo);
        unitaOperativa.setAbilitato(abilitato);
        unitaOperativa.setDominio(dominio);
        unitaOperativaRepository.save(unitaOperativa);
    }

    @Test
    @DisplayName("rifiuta con 400 (non 201) un tributo disabilitato per il dominio (bug del lead, "
            + "2026-09-29: il mapper verificava solo che il tributo fosse censito, non abilitato — "
            + "v2 lo rifiuta, TRB_001)")
    void rifiutaConBadRequestSeTributoDisabilitato() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        configuraTributoPerDominio("TRIBUTO-DISABILITATO", dominio, false);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-tributo-disabilitato",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "TRIBUTO-DISABILITATO" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("rifiuta con 400 (non 201) un ibanAccredito disabilitato per il dominio (bug del "
            + "lead, 2026-09-29 — v2 lo rifiuta, VER_032)")
    void rifiutaConBadRequestSeIbanAccreditoDisabilitato() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        salvaIban("IT60X0542811101000000123456", dominio, false);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-iban-accredito-disabilitato",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                  "descrizione": "test", "ibanAccredito": "IT60X0542811101000000123456",
                                  "tassonomia": "9/0101002IM/" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("rifiuta con 400 (non 201) un ibanAppoggio disabilitato per il dominio (bug del "
            + "lead, 2026-09-29 — v2 lo rifiuta, VER_034)")
    void rifiutaConBadRequestSeIbanAppoggioDisabilitato() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        salvaIban("IT60X0542811101000000123456", dominio, true);
        salvaIban("IT60X0542811101000000999999", dominio, false);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-iban-appoggio-disabilitato",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                  "descrizione": "test", "ibanAccredito": "IT60X0542811101000000123456",
                                  "ibanAppoggio": "IT60X0542811101000000999999",
                                  "tassonomia": "9/0101002IM/" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("rifiuta con 400 (non 201) un'unita' operativa disabilitata per il dominio (stesso "
            + "bug di tributo/IBAN, trovato per analogia il 2026-09-29 — v2 lo rifiuta, UOP_001)")
    void rifiutaConBadRequestSeUnitaOperativaDisabilitata() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        salvaUnitaOperativa("UO-DISABILITATA", dominio, false);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-uo-disabilitata",
                  "idDominio": "12345678901",
                  "idUnitaOperativa": "UO-DISABILITATA",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("rifiuta con 404 (non 500) una voce RIFERIMENTO_ENTRATA con codEntrata non configurato "
            + "per il dominio")
    void rifiutaConNotFoundSeCodEntrataNonConfiguratoPerIlDominio() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-entrata-ignota",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "TRIBUTO-INESISTENTE" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("rifiuta con 404 (non 500) una voce ENTRATA con un IBAN non censito per il dominio")
    void rifiutaConNotFoundSeIbanAccreditoNonCensito() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-iban-ignoto",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                  "descrizione": "test", "ibanAccredito": "IT00Z0000000000000000000000",
                                  "tassonomia": "9/0101002IM/" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("crea con successo (201, non 500) per un dominio il cui prefisso IUV usa %(p): bug del "
            + "lead, 2026-09-27 — il converter non valorizzava codificaIuvTipoPendenza e le entita' "
            + "TipoVersamento/TipoVersamentoDominio non mappavano affatto codifica_iuv")
    void creaConPrefissoIuvCheUsaCodificaTipoPendenza() throws Exception {
        DominioEntity dominioConPrefisso = DominioEntity.builder()
                .codDominio("11111111111")
                .ragioneSociale("Comune con prefisso IUV")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .iuvPrefix("%(p)")
                .build();
        dominioConPrefisso = dominioRepository.save(dominioConPrefisso);

        TipoVersamentoEntity tipoVersamento = nuovoTipoVersamento("IMU", "Imposta Municipale Unica");
        tipoVersamento.setCodificaIuv("9902"); // deve risolvere in un prefisso numerico
        tipoVersamentoRepository.save(tipoVersamento);

        TipoVersamentoDominioEntity override = new TipoVersamentoDominioEntity();
        override.setTipoVersamento(tipoVersamento);
        override.setDominio(dominioConPrefisso);
        tipoVersamentoDominioRepository.save(override);

        configuraTributoPerDominio("IMU", dominioConPrefisso);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-prefisso-iuv",
                  "idDominio": "11111111111",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-prefisso-iuv",
                      "idTipoPendenza": "IMU",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "IMU" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                // Non l'IUV esatto (dipende da progressivo/check digit, non l'oggetto di questo
                // test): basta che il prefisso %(p) risolto in "9902" sia comparso davvero.
                .andExpect(jsonPath("$.opzioniPagamento[0].pendenze[0].iuv", startsWith("9902")));
    }

    @Test
    @DisplayName("rifiuta con 400 (non troncamento silenzioso) un giorni non intero — bug del lead, "
            + "2026-09-27: lo YAML ammetteva number, il converter usava intValue()")
    void rifiutaGiorniNonIntero() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-giorni-frazionario",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA_ENTRO",
                    "giorni": 1.9,
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("rifiuta con 400 un giorni superiore al limite (3650) — non un troncamento, ma il "
            + "vincolo esplicito aggiunto insieme al fix sul tipo intero")
    void rifiutaGiorniOltreIlLimite() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-giorni-eccessivo",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA_ENTRO",
                    "giorni": 999999999,
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Con l'autenticazione reale (govpay-common-auth) uno chiamante non puo' autenticarsi
     * come un'applicazione che non esiste: {@code verificaIdA2A} confronta idA2A col codice
     * dell'applicazione autenticata (qui "A2A-TEST", vedi {@link #BasicAuthDiDefaultConfig})
     * PRIMA di qualunque ricerca — lo scenario originale ("idA2A non censito", 404) e' quindi
     * ora irraggiungibile per un chiamante autenticato; il caso reale e' un mismatch di
     * identita' (403), come in v2 (bug del lead, 2026-09-29).
     */
    @Test
    void rifiutaConForbiddenSeIdA2ANonCorrispondeAllApplicazioneAutenticata() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-1",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-INESISTENTE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void rifiutaConNotFoundSeIdDominioNonEsiste() throws Exception {
        String principal = "A2A-DOMINIO-IGNOTO";
        String password = "altra-password";
        UtenzaEntity altraUtenza = creaUtenza(principal, password);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(altraUtenza.getId())
                .build());

        String body = """
                {
                  "idPosizioneDebitoria": "pos-1",
                  "idDominio": "00000000000",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", principal)
                        .with(comeApplicazione(principal, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * Bug del lead, 2026-09-29: {@code verificaIdA2A} da solo verifica solo l'identita', non
     * i diritti ACL sul servizio "API Pendenze" (tabella condivisa {@code acl}) — in v2 questo
     * controllo era presente su ogni endpoint ({@code AuthorizationManager.isAuthorized(...,
     * Servizio.API_PENDENZE, Diritti.LETTURA/SCRITTURA)}, verificato PRIMA del controllo
     * idA2A). Qui l'applicazione autentica correttamente come se stessa ma non ha ALCUNA riga
     * ACL per "API Pendenze": deve essere rifiutata comunque.
     */
    @Test
    void rifiutaConForbiddenSeApplicazioneNonHaDirittoDiScritturaSuApiPendenze() throws Exception {
        String principal = "A2A-SENZA-ACL";
        String password = "senza-acl-password";
        UtenzaEntity utenzaSenzaAcl = creaUtenza(principal, password, null);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenzaSenzaAcl.getId())
                .build());

        String body = """
                {
                  "idPosizioneDebitoria": "pos-1",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", principal)
                        .with(comeApplicazione(principal, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(403));
    }

    /**
     * Come sopra, ma per il diritto di LETTURA: un'applicazione con SOLA scrittura ("W", senza
     * "R") deve essere rifiutata su un GET. Diverso dal caso precedente: qui l'ACL esiste, ma
     * non copre il diritto richiesto da questo endpoint.
     */
    @Test
    void rifiutaConForbiddenSeApplicazioneNonHaDirittoDiLetturaSuApiPendenze() throws Exception {
        String principal = "A2A-SOLA-SCRITTURA";
        String password = "sola-scrittura-password";
        UtenzaEntity utenzaSolaScrittura = creaUtenza(principal, password, "W");
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
                .idUtenza(utenzaSolaScrittura.getId())
                .build());

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/pos-qualsiasi", principal)
                        .with(comeApplicazione(principal, password)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(403));
    }

    /**
     * Bug del lead, 2026-09-29: la risoluzione del tipo pendenza controllava solo che fosse
     * censito per il dominio, non che il CHIAMANTE potesse usarlo — in v2 questo controllo era
     * esplicito per le applicazioni non trusted ({@code VersamentoUtils.setTipoVersamento},
     * {@code VER_022}: {@code !applicazione.isTrusted() &&
     * !AuthorizationManager.isTipoVersamentoAuthorized(...)}). Qui l'applicazione e'
     * esplicitamente {@code trusted(false)}, senza autorizzazione "star" ne' una riga
     * esplicita in {@code utenze_tipo_vers}: deve essere rifiutata anche se "DIRITTI_SEGRETERIA"
     * e' censito/abilitato per il dominio (a differenza dell'ACL/idA2A, qui il precedente
     * legacy e' 422 — non usato da questa API — mappato su 400, stessa scelta gia' fatta per i
     * controlli "abilitato" di tributo/IBAN/UO).
     */
    @Test
    void rifiutaConBadRequestSeApplicazioneNonTrustedENonAutorizzataAlTipoPendenza() throws Exception {
        String principal = "A2A-NON-TRUSTED";
        String password = "non-trusted-password";
        UtenzaEntity utenzaNonTrusted = creaUtenza(principal, password);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(false)
                .idUtenza(utenzaNonTrusted.getId())
                .build());

        String body = """
                {
                  "idPosizioneDebitoria": "pos-non-trusted",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", principal)
                        .with(comeApplicazione(principal, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Come sopra, ma l'utenza ha l'autorizzazione "star" ({@code
     * utenze.autorizzazione_tipi_vers_star}): nessuna riga esplicita in
     * {@code utenze_tipo_vers} necessaria, la richiesta va a buon fine.
     */
    @Test
    void accettaSeApplicazioneNonTrustedHaAutorizzazioneStarAiTipiVersamento() throws Exception {
        String principal = "A2A-NON-TRUSTED-STAR";
        String password = "non-trusted-star-password";
        UtenzaEntity utenza = creaUtenza(principal, password);
        utenza.setAutorizzazioneTipiVersStar(true);
        utenzaRepository.save(utenza);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(false)
                .idUtenza(utenza.getId())
                .build());

        String body = """
                {
                  "idPosizioneDebitoria": "pos-non-trusted-star",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", principal)
                        .with(comeApplicazione(principal, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    /**
     * Come sopra, ma senza "star": l'autorizzazione arriva da una riga esplicita in
     * {@code utenze_tipo_vers} per il solo tipo pendenza usato dalla richiesta.
     */
    @Test
    void accettaSeApplicazioneNonTrustedHaAutorizzazioneEsplicitaAlTipoPendenza() throws Exception {
        String principal = "A2A-NON-TRUSTED-GRANT";
        String password = "non-trusted-grant-password";
        UtenzaEntity utenza = creaUtenza(principal, password);
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione(principal)
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(false)
                .idUtenza(utenza.getId())
                .build());

        Long idTipoVersamento = tipoVersamentoRepository.findByCodTipoVersamento("DIRITTI_SEGRETERIA")
                .orElseThrow().getId();
        UtenzaTipoVersamentoEntity grant = new UtenzaTipoVersamentoEntity();
        grant.setIdUtenza(utenza.getId());
        grant.setIdTipoVersamento(idTipoVersamento);
        utenzaTipoVersamentoRepository.save(grant);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-non-trusted-grant",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", principal)
                        .with(comeApplicazione(principal, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    /**
     * Bug del lead, 2026-09-29: la creazione accettava (201) sia un dominio disabilitato sia
     * un tipo pendenza disabilitato (globale o per il dominio) — {@link #risolviIdDominioAbilitato}/
     * {@link PosizioneDebitoriaMapper#risolviTipoVersamentoDominio} in
     * {@code PosizioneDebitoriaMapper} controllavano solo che l'anagrafica fosse CENSITA, mai
     * che fosse ABILITATA (a differenza dei controlli gia' presenti per UO/tributo/IBAN, che
     * non coprono questi tre casi). v2 lo fa in {@code VersamentoUtils}: {@code DOM_001}
     * (dominio), {@code TVR_001} (tipo pendenza globale), {@code TVD_001} (override per
     * dominio).
     */
    @Test
    void rifiutaConBadRequestSeDominioDisabilitato() throws Exception {
        dominioRepository.save(DominioEntity.builder()
                .codDominio("99999999999")
                .ragioneSociale("Comune disabilitato")
                .abilitato(false)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        String body = """
                {
                  "idPosizioneDebitoria": "pos-dominio-disabilitato",
                  "idDominio": "99999999999",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rifiutaConBadRequestSeTipoPendenzaGlobaleDisabilitato() throws Exception {
        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();

        TipoVersamentoEntity tipoVersamentoDisabilitato = nuovoTipoVersamento("TIPO-DISABILITATO", "Tipo disabilitato a livello globale");
        tipoVersamentoDisabilitato.setAbilitato(false);
        tipoVersamentoRepository.save(tipoVersamentoDisabilitato);

        TipoVersamentoDominioEntity override = new TipoVersamentoDominioEntity();
        override.setTipoVersamento(tipoVersamentoDisabilitato);
        override.setDominio(dominio);
        tipoVersamentoDominioRepository.save(override);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-tipo-globale-disabilitato",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "TIPO-DISABILITATO",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * A differenza del test precedente, qui il tipo pendenza e' abilitato a livello globale:
     * la disabilitazione e' solo nell'override per QUESTO dominio ({@code tipi_vers_domini.abilitato
     * = false} esplicito, non {@code null}).
     */
    @Test
    void rifiutaConBadRequestSeTipoPendenzaDisabilitatoPerIlDominio() throws Exception {
        DominioEntity altroDominio = dominioRepository.save(DominioEntity.builder()
                .codDominio("88888888888")
                .ragioneSociale("Comune con tipo pendenza disabilitato")
                .abilitato(true)
                .intermediato(false)
                .scaricaFr(false)
                .auxDigit(1)
                .build());

        TipoVersamentoEntity tipoVersamentoGlobale = tipoVersamentoRepository.findByCodTipoVersamento("DIRITTI_SEGRETERIA")
                .orElseThrow();
        TipoVersamentoDominioEntity overrideDisabilitato = new TipoVersamentoDominioEntity();
        overrideDisabilitato.setTipoVersamento(tipoVersamentoGlobale);
        overrideDisabilitato.setDominio(altroDominio);
        overrideDisabilitato.setAbilitato(false);
        tipoVersamentoDominioRepository.save(overrideDisabilitato);

        // "DIRITTI_SEGRETERIA" e' un cod_tributo GLOBALE (unique) gia' censito da
        // creaAnagrafiche(): qui si aggiunge solo l'override per il NUOVO dominio, non un
        // secondo TipoTributoEntity (violerebbe unique_tipi_tributo_1).
        TipoTributoEntity tipoTributoGlobale = tipoTributoRepository.findByCodTributo("DIRITTI_SEGRETERIA")
                .orElseThrow();
        TributoEntity tributo = new TributoEntity();
        tributo.setAbilitato(true);
        tributo.setDominio(altroDominio);
        tributo.setTipoTributo(tipoTributoGlobale);
        tributoRepository.save(tributo);

        String body = """
                {
                  "idPosizioneDebitoria": "pos-tipo-dominio-disabilitato",
                  "idDominio": "88888888888",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rifiutaConConflictSeIdPosizioneDebitoriaGiaEsistente() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-duplicata",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "%s",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "%s", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("pendenza-a", "voce-a")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("pendenza-b", "voce-b")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET restituisce la posizione appena creata, stesso contenuto della risposta del POST "
            + "(promessa esplicita dello YAML: \"risposta 201 = stesso payload di una GET\")")
    void getPosizioneDebitoriaTrovaLaPosizioneAppenaCreata() throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "pos-get-1",
                  "idDominio": "12345678901",
                  "descrizione": "test GET",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y", "anagrafica": "Paola Ferrari" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-get-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-get-1", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-get-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idA2A").value("A2A-TEST"))
                .andExpect(jsonPath("$.idPosizioneDebitoria").value("pos-get-1"))
                .andExpect(jsonPath("$.descrizione").value("test GET"))
                .andExpect(jsonPath("$.soggettiDebitori.length()").value(1))
                .andExpect(jsonPath("$.soggettiDebitori[0].identificativo").value("FRRPLA90C41H501Y"))
                .andExpect(jsonPath("$.opzioniPagamento[0].pendenze[0].idPendenza").value("pendenza-get-1"));
    }

    @Test
    @DisplayName("GET restituisce 404 se la posizione non esiste")
    void getPosizioneDebitoriaRestituisce404SeNonEsiste() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-inesistente"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    private void creaPosizioneMinimaConDebitore(String idPosizioneDebitoria, String idDebitore) throws Exception {
        String body = """
                {
                  "idPosizioneDebitoria": "%s",
                  "idDominio": "12345678901",
                  "descrizione": "test ricerca",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "%s" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-%s",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-%s", "importo": 16.00,
                                  "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """.formatted(idPosizioneDebitoria, idDebitore, idPosizioneDebitoria, idPosizioneDebitoria);

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} trova le posizioni del debitore indicato, non altre; "
            + "con total=true riporta anche il conteggio")
    void findPosizioniDebitorieTrovaLePosizioniDelDebitore() throws Exception {
        creaPosizioneMinimaConDebitore("pos-find-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-find-2", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-find-altro-debitore", "VRDGNN80A01H501W");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("total", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.limit").value(25))
                .andExpect(jsonPath("$.pagination.hasNextPage").value(false))
                .andExpect(jsonPath("$.pagination.totalResults").value(2))
                .andExpect(jsonPath("$.pagination.totalPages").value(1))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[*].idPosizioneDebitoria",
                        org.hamcrest.Matchers.containsInAnyOrder("pos-find-1", "pos-find-2")));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} pagina a offset (total=false, default): hasNextPage=true "
            + "quando ce ne sono altri e nessun totale, page=2 restituisce il resto")
    void findPosizioniDebitoriePaginaAOffsetSegnalaHasNextPage() throws Exception {
        creaPosizioneMinimaConDebitore("pos-pagina-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-pagina-2", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-pagina-3", "FRRPLA90C41H501Y");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(1))
                .andExpect(jsonPath("$.pagination.limit").value(2))
                .andExpect(jsonPath("$.pagination.hasNextPage").value(true))
                .andExpect(jsonPath("$.pagination.totalResults").doesNotExist())
                .andExpect(jsonPath("$.results.length()").value(2));

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("limit", "2")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.page").value(2))
                .andExpect(jsonPath("$.pagination.hasNextPage").value(false))
                .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} restituisce 400 (non 500) se manca idDebitore, obbligatorio")
    void findPosizioniDebitorieRestituisce400SeMancaIdDebitoreObbligatorio() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} restituisce 400 (non 500) se page non e' un numero")
    void findPosizioniDebitorieRestituisce400SePageNonENumerico() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} applica fields: restituisce solo i campi richiesti")
    void findPosizioniDebitorieApplicaFields() throws Exception {
        creaPosizioneMinimaConDebitore("pos-fields-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-fields-2", "FRRPLA90C41H501Y");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("limit", "1")
                        .param("fields", "idPosizioneDebitoria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].idPosizioneDebitoria").exists())
                .andExpect(jsonPath("$.results[0].descrizione").doesNotExist())
                .andExpect(jsonPath("$.results[0].soggettiDebitori").doesNotExist())
                .andExpect(jsonPath("$.results[0].idA2A").doesNotExist());
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} paginazione a cursore: nextCursor porta al resto dei "
            + "risultati senza sovrapposizioni, ordinamento fisso dataCreazione desc/id desc")
    void findPosizioniDebitorieCursoreScorreSenzaSovrapposizioni() throws Exception {
        creaPosizioneMinimaConDebitore("pos-cursore-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-cursore-2", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-cursore-3", "FRRPLA90C41H501Y");

        MvcResult primaPagina = mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("cursor", "")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").exists())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andReturn();

        String nextCursor = com.jayway.jsonpath.JsonPath.read(
                primaPagina.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("cursor", nextCursor)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].idPosizioneDebitoria").value("pos-cursore-1"));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} rifiuta con 400 'page' e 'cursor' insieme")
    void findPosizioniDebitorieRifiutaPageECursoreInsieme() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("page", "1")
                        .param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} rifiuta con 400 'sort' in modalita' cursore")
    void findPosizioniDebitorieRifiutaSortInModalitaCursore() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("cursor", "")
                        .param("sort", "dataCreazione:asc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} rifiuta con 400 'total=true' in modalita' cursore")
    void findPosizioniDebitorieRifiutaTotalInModalitaCursore() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("cursor", "")
                        .param("total", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} rifiuta con 400 un cursore malformato")
    void findPosizioniDebitorieRifiutaCursoreMalformato() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("cursor", "non-e-un-cursore-valido"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    // ── PATCH /posizioni-debitorie/{idA2A}/{idPosizioneDebitoria} ──────────────────────────

    private static final String PATCH_MEDIA_TYPE = "application/json-patch+json";

    @Test
    @DisplayName("PATCH replace /descrizione aggiorna il campo, leggibile dalla GET successiva")
    void patchReplaceDescrizione() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-descrizione", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-descrizione")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/descrizione", "value": "nuova descrizione" } ]
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-descrizione"))
                .andExpect(jsonPath("$.descrizione").value("nuova descrizione"));
    }

    @Test
    @DisplayName("PATCH replace /dataPubblicazione, poi remove: rispettivamente valorizza e azzera il campo")
    void patchReplaceERemoveDataPubblicazione() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-datapub", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-datapub")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/dataPubblicazione", "value": "2026-06-15" } ]
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-datapub"))
                .andExpect(jsonPath("$.dataPubblicazione").value("2026-06-15"));

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-datapub")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "remove", "path": "/dataPubblicazione" } ]
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-datapub"))
                .andExpect(jsonPath("$.dataPubblicazione").doesNotExist());
    }

    @Test
    @DisplayName("PATCH replace /notificaSend=true senza navNotifica lo assegna automaticamente "
            + "(stessa regola di POST, riusata da PosizioneDebitoriaService#aggiorna)")
    void patchNotificaSendAssegnaNavNotificaAutomaticamente() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-notifica-send", "FRRPLA90C41H501Y");
        String numeroAvviso = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                                "pos-patch-notifica-send"))
                        .andReturn().getResponse().getContentAsString(),
                "$.opzioniPagamento[0].pendenze[0].numeroAvviso");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-notifica-send")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/notificaSend", "value": true } ]
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-notifica-send"))
                .andExpect(jsonPath("$.notificaSend").value(true))
                .andExpect(jsonPath("$.navNotifica").value(numeroAvviso));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 un navNotifica che non corrisponde al numeroAvviso di alcuna pendenza")
    void patchRifiutaNavNotificaNonCorrispondente() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-nav-non-corrisp", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-nav-non-corrisp")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/navNotifica", "value": "999999999999999999" } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 'remove' su /descrizione (obbligatoria) e su /notificaSend (booleano)")
    void patchRifiutaRemoveSuCampiObbligatoriONonRimuovibili() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-remove-vietato", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-remove-vietato")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "remove", "path": "/descrizione" } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-remove-vietato")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "remove", "path": "/notificaSend" } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 un path non supportato (es. opzioniPagamento non si aggiorna da qui)")
    void patchRifiutaPathNonSupportato() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-path-ignoto", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-path-ignoto")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/opzioniPagamento", "value": [] } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 un value di tipo sbagliato (notificaSend non booleano)")
    void patchRifiutaValueDiTipoSbagliato() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-tipo-sbagliato", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-tipo-sbagliato")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/notificaSend", "value": "non-un-booleano" } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 un'operazione 'replace' senza 'value'")
    void patchRifiutaValueAssente() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-value-assente", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-value-assente")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/descrizione" } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 404 una posizione inesistente")
    void patchRifiutaPosizioneInesistente() throws Exception {
        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-inesistente")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/descrizione", "value": "x" } ]
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH sostituisce interamente soggettiDebitori, leggibile dalla GET successiva")
    void patchSostituisceSoggettiDebitori() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-soggetti", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-soggetti")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/soggettiDebitori", "value": [
                                    { "tipo": "F", "identificativo": "VRDGNN80A01H501W", "anagrafica": "Giovanna Verdi" }
                                ] } ]
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-soggetti"))
                .andExpect(jsonPath("$.soggettiDebitori.length()").value(1))
                .andExpect(jsonPath("$.soggettiDebitori[0].identificativo").value("VRDGNN80A01H501W"));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 uno svuotamento di soggettiDebitori (almeno un soggetto richiesto)")
    void patchRifiutaSoggettiDebitoriVuoti() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-soggetti-vuoti", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-soggetti-vuoti")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/soggettiDebitori", "value": [] } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 un body assente")
    void patchRifiutaBodyAssente() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-body-assente", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-body-assente")
                        .contentType(PATCH_MEDIA_TYPE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 (non 500) un body il cui array contiene un elemento null: [null]")
    void patchRifiutaElementoNulloNellArrayDiOperazioni() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-op-null", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-patch-op-null")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("[ null ]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 (non 500) 'soggettiDebitori' impostato esplicitamente a null")
    void patchRifiutaSoggettiDebitoriValueNull() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-soggetti-null", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-soggetti-null")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/soggettiDebitori", "value": null } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 (non 500) 'soggettiDebitori' con un elemento null: [null]")
    void patchRifiutaSoggettiDebitoriConElementoNullo() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-sogg-elem-null", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-sogg-elem-null")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/soggettiDebitori", "value": [ null ] } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH rifiuta con 400 (non 200) una descrizione di 141 caratteri, come in creazione (maxLength 140)")
    void patchRifiutaDescrizioneTroppoLunga() throws Exception {
        creaPosizioneMinimaConDebitore("pos-patch-descr-lunga", "FRRPLA90C41H501Y");
        String descrizione141 = "x".repeat(141);

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST",
                        "pos-patch-descr-lunga")
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/descrizione", "value": "%s" } ]
                                """.formatted(descrizione141)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Bug del lead, 2026-09-29: {@code CurrentApplicazioneService#get} lanciava
     * {@code IllegalStateException} (500) se il principal autenticato (credenziali VALIDE,
     * verificate dal filtro — {@code abilitato=true}) non risolveva a nessuna
     * {@link ApplicazioneEntity}: una condizione di dato reale (utenza censita senza
     * applicazione associata), non un errore di programmazione — deve essere 403
     * ({@link it.govpay.pendenze.web.AccessoNegatoException}), non un errore interno. La
     * stessa correzione si applica a {@code AclAuthorizer#utenzaAutenticata}, ma il diritto
     * ACL "RW" e' concesso qui apposta perche' il fallimento sotto test sia specificamente
     * quello di {@code CurrentApplicazioneService}, non quello (precedente, gia' testato) di
     * {@code AclAuthorizer}.
     */
    @Test
    void rifiutaConForbiddenSeUtenzaAutenticataNonHaApplicazioneAssociata() throws Exception {
        String principal = "A2A-SENZA-APPLICAZIONE";
        String password = "senza-applicazione-password";
        creaUtenza(principal, password, "RW");
        // Nessuna ApplicazioneEntity con idUtenza = questa utenza: deliberatamente orfana.

        String body = """
                {
                  "idPosizioneDebitoria": "pos-1",
                  "idDominio": "12345678901",
                  "descrizione": "test",
                  "soggettiDebitori": [ { "tipo": "F", "identificativo": "FRRPLA90C41H501Y" } ],
                  "opzioniPagamento": [ {
                    "tipologia": "SOLUZIONE_UNICA",
                    "pendenze": [ {
                      "idPendenza": "pendenza-1",
                      "idTipoPendenza": "DIRITTI_SEGRETERIA",
                      "importo": 16.00,
                      "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00, "descrizione": "test",
                                  "codEntrata": "DIRITTI_SEGRETERIA" } ]
                    } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", principal)
                        .with(comeApplicazione(principal, password))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST .../opzioni-pagamento aggiunge una nuova opzione a una posizione esistente, "
            + "generando numeroAvviso/iuv per la nuova pendenza")
    void addOpzionePagamentoCreaConSuccesso() throws Exception {
        creaPosizioneMinimaConDebitore("pos-add-opzione", "FRRPLA90C41H501Y");

        String body = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pos-add-opzione-seconda",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 16.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-seconda", "importo": 16.00,
                                "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-add-opzione")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.idOpzionePagamento").value(notNullValue()))
                .andExpect(jsonPath("$.tipologia").value("SOLUZIONE_UNICA"))
                .andExpect(jsonPath("$.stato").value("DISPONIBILE"))
                .andExpect(jsonPath("$.pendenze.length()").value(1))
                .andExpect(jsonPath("$.pendenze[0].idPendenza").value("pos-add-opzione-seconda"))
                .andExpect(jsonPath("$.pendenze[0].numeroAvviso").value(notNullValue()))
                .andExpect(jsonPath("$.pendenze[0].iuv").value(notNullValue()));

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-add-opzione"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opzioniPagamento.length()").value(2));
    }

    @Test
    @DisplayName("POST .../opzioni-pagamento restituisce 404 se la posizione non esiste")
    void addOpzionePagamentoRestituisce404SeLaPosizioneNonEsiste() throws Exception {
        String body = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pendenza-1",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 16.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-inesistente")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST .../opzioni-pagamento rifiuta con 400 un body assente")
    void addOpzionePagamentoRifiutaConBadRequestSeBodyAssente() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-body-assente", "FRRPLA90C41H501Y");

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-opz-body-assente")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Conferma che il controllo "tipo pendenza disabilitato per il dominio" (gap del lead,
     * 2026-09-29, {@link PosizioneDebitoriaMapper#risolviTipoVersamentoDominio}) si applica
     * anche a questo endpoint: e' riusato dallo stesso {@code toOpzionePagamento}, non
     * duplicato.
     */
    @Test
    @DisplayName("POST .../opzioni-pagamento rifiuta con 400 un tipo pendenza disabilitato per il dominio")
    void addOpzionePagamentoRifiutaConBadRequestSeTipoPendenzaDisabilitatoPerIlDominio() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-tipo-disab", "FRRPLA90C41H501Y");

        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        TipoVersamentoEntity tipoVersamentoDisabilitato = nuovoTipoVersamento("TIPO-DISABILITATO-OPZIONE",
                "Tipo disabilitato a livello globale, per questo test");
        tipoVersamentoDisabilitato.setAbilitato(false);
        tipoVersamentoRepository.save(tipoVersamentoDisabilitato);
        TipoVersamentoDominioEntity override = new TipoVersamentoDominioEntity();
        override.setTipoVersamento(tipoVersamentoDisabilitato);
        override.setDominio(dominio);
        tipoVersamentoDominioRepository.save(override);

        String body = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pendenza-tipo-disabilitato",
                    "idTipoPendenza": "TIPO-DISABILITATO-OPZIONE",
                    "importo": 16.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-1", "importo": 16.00,
                                "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-opz-tipo-disab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Bug del lead, 2026-09-29: la POST restituiva 201 (opzione DISPONIBILE creata) anche
     * quando la posizione aveva gia' un'opzione ATTIVATA — contraddice la regola dello YAML
     * secondo cui, dopo un pagamento, le alternative non sono piu' applicabili. Nessun
     * endpoint REST attiva ancora un'opzione ({@code PATCH .../opzioni-pagamento/{id}} resta
     * 501): l'attivazione e' simulata qui chiamando direttamente
     * {@code PosizioneDebitoriaService#attiva}, come farebbe quell'endpoint una volta
     * implementato.
     */
    @Test
    @DisplayName("POST .../opzioni-pagamento rifiuta con 409 se la posizione ha gia' un'opzione ATTIVATA")
    void addOpzionePagamentoRifiutaConConflictSeEsisteGiaUnaOpzioneAttivata() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-attivata", "FRRPLA90C41H501Y");

        String risposta = mockMvc
                .perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-opz-attivata"))
                .andReturn().getResponse().getContentAsString();
        String idOpzione = JsonPath.read(risposta, "$.opzioniPagamento[0].idOpzionePagamento");
        posizioneDebitoriaService.attiva(java.util.UUID.fromString(idOpzione));

        String body = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pos-opz-attivata-seconda",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 16.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-seconda", "importo": 16.00,
                                "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-opz-attivata")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409));
    }

    /**
     * Bug del lead, 2026-09-29: il dominio disabilitato DOPO la creazione della posizione non
     * impediva di aggiungerle una nuova opzione di pagamento — il nuovo mapper riusa i
     * controlli sui tipi pendenza ma non ricontrollava l'abilitazione del dominio (a
     * differenza della creazione).
     */
    @Test
    @DisplayName("POST .../opzioni-pagamento rifiuta con 400 se il dominio e' stato disabilitato dopo la creazione")
    void addOpzionePagamentoRifiutaConBadRequestSeDominioDisabilitatoDopoLaCreazione() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-dominio-disab", "FRRPLA90C41H501Y");

        DominioEntity dominio = dominioRepository.findByCodDominio("12345678901").orElseThrow();
        dominio.setAbilitato(false);
        dominioRepository.save(dominio);

        String body = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pos-opz-dominio-disab-2",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 16.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-seconda", "importo": 16.00,
                                "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-opz-dominio-disab")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Bug del lead, 2026-09-29: riusare l'idPendenza di una pendenza gia' esistente della
     * stessa applicazione falliva con 500 (vincolo UNIQUE {@code unique_versamenti_1} mai
     * tradotto), non con una risposta applicativa 4xx.
     */
    @Test
    @DisplayName("POST .../opzioni-pagamento rifiuta con 409 (non 500) un idPendenza gia' usato dalla stessa applicazione")
    void addOpzionePagamentoRifiutaConConflictSeIdPendenzaGiaUsato() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-id-dup", "FRRPLA90C41H501Y");

        String body = """
                {
                  "tipologia": "SOLUZIONE_UNICA",
                  "pendenze": [ {
                    "idPendenza": "pendenza-pos-opz-id-dup",
                    "idTipoPendenza": "DIRITTI_SEGRETERIA",
                    "importo": 16.00,
                    "voci": [ { "tipoRiferimento": "RIFERIMENTO_ENTRATA", "idVocePendenza": "voce-x", "importo": 16.00,
                                "descrizione": "test", "codEntrata": "DIRITTI_SEGRETERIA" } ]
                  } ]
                }
                """;

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento",
                        "A2A-TEST", "pos-opz-id-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409));
    }

    private String idOpzioneDi(String idPosizioneDebitoria) throws Exception {
        String risposta = mockMvc
                .perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", idPosizioneDebitoria))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(risposta, "$.opzioniPagamento[0].idOpzionePagamento");
    }

    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} annulla l'opzione, leggibile dalla GET successiva")
    void updateOpzionePagamentoAnnullaConSuccesso() throws Exception {
        creaPosizioneMinimaConDebitore("pos-annulla-opz", "FRRPLA90C41H501Y");
        String idOpzione = idOpzioneDi("pos-annulla-opz");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-annulla-opz", idOpzione)
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/stato", "value": "ANNULLATA" } ]
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-annulla-opz"))
                .andExpect(jsonPath("$.opzioniPagamento[0].stato").value("ANNULLATA"));
    }

    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} rifiuta con 400 un path diverso da /stato")
    void updateOpzionePagamentoRifiutaConBadRequestSePathNonSupportato() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-path-ignoto", "FRRPLA90C41H501Y");
        String idOpzione = idOpzioneDi("pos-opz-path-ignoto");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-opz-path-ignoto", idOpzione)
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/tipologia", "value": "PIANO_RATEALE" } ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * L'attivazione e' innescata da un pagamento reale, mai da un PATCH del chiamante (vedi
     * Javadoc di {@code PosizioneDebitoriaMapper#validaPatchAnnullamento}): rifiutata con lo
     * stesso 400 di qualunque altro valore non supportato.
     */
    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} rifiuta con 400 value=ATTIVATA: non raggiungibile da questo endpoint")
    void updateOpzionePagamentoRifiutaConBadRequestSeValoreAttivata() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-value-attivata", "FRRPLA90C41H501Y");
        String idOpzione = idOpzioneDi("pos-opz-value-attivata");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-opz-value-attivata", idOpzione)
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/stato", "value": "ATTIVATA" } ]
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} rifiuta con 400 un body assente")
    void updateOpzionePagamentoRifiutaConBadRequestSeBodyAssente() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-body-assente-2", "FRRPLA90C41H501Y");
        String idOpzione = idOpzioneDi("pos-opz-body-assente-2");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-opz-body-assente-2", idOpzione)
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("null"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} restituisce 404 se l'opzione non esiste")
    void updateOpzionePagamentoRestituisce404SeOpzioneNonEsiste() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-inesistente", "FRRPLA90C41H501Y");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-opz-inesistente", java.util.UUID.randomUUID().toString())
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/stato", "value": "ANNULLATA" } ]
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    /**
     * Bug potenziale verificato esplicitamente: {@code idOpzionePagamento} da solo non e'
     * legato a nessun controllo di appartenenza — deve essere il servizio a verificare che
     * appartenga davvero alla posizione indicata nel path, non solo che esista da qualche
     * parte (vedi Javadoc di {@code PosizioneDebitoriaService#annulla(String, String, UUID)}).
     */
    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} restituisce 404 se l'opzione esiste ma appartiene a un'altra posizione")
    void updateOpzionePagamentoRestituisce404SeOpzioneAppartieneAdAltraPosizione() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-altra-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-opz-altra-2", "FRRPLA90C41H501Y");
        String idOpzioneDellAltra = idOpzioneDi("pos-opz-altra-2");

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-opz-altra-1", idOpzioneDellAltra)
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/stato", "value": "ANNULLATA" } ]
                                """))
                .andExpect(status().isNotFound());

        // L'opzione dell'ALTRA posizione non deve essere stata toccata dal tentativo rifiutato.
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}", "A2A-TEST", "pos-opz-altra-2"))
                .andExpect(jsonPath("$.opzioniPagamento[0].stato").value("DISPONIBILE"));
    }

    @Test
    @DisplayName("PATCH .../opzioni-pagamento/{id} restituisce 409 se l'opzione e' gia' ATTIVATA")
    void updateOpzionePagamentoRestituisceConflictSeGiaAttivata() throws Exception {
        creaPosizioneMinimaConDebitore("pos-opz-gia-attivata", "FRRPLA90C41H501Y");
        String idOpzione = idOpzioneDi("pos-opz-gia-attivata");
        posizioneDebitoriaService.attiva(java.util.UUID.fromString(idOpzione));

        mockMvc.perform(patch("/posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/{idOpzionePagamento}",
                        "A2A-TEST", "pos-opz-gia-attivata", idOpzione)
                        .contentType(PATCH_MEDIA_TYPE)
                        .content("""
                                [ { "op": "replace", "path": "/stato", "value": "ANNULLATA" } ]
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409));
    }
}
