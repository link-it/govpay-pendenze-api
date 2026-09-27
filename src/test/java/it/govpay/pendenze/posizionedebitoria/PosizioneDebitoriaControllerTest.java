package it.govpay.pendenze.posizionedebitoria;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.entity.TipoVersamento;
import it.govpay.pendenze.entity.TipoVersamentoDominio;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.TipoVersamentoDominioRepository;
import it.govpay.pendenze.repository.TipoVersamentoRepository;

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
class PosizioneDebitoriaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicazioneRepository applicazioneRepository;

    @Autowired
    private DominioRepository dominioRepository;

    @Autowired
    private TipoVersamentoRepository tipoVersamentoRepository;

    @Autowired
    private TipoVersamentoDominioRepository tipoVersamentoDominioRepository;

    @Autowired
    private PosizioneDebitoriaRepository posizioneDebitoriaRepository;

    private Long idDominio;

    @BeforeEach
    void creaAnagrafiche() {
        ApplicazioneEntity applicazione = ApplicazioneEntity.builder()
                .codApplicazione("A2A-TEST")
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
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

        TipoVersamento tipoVersamento = new TipoVersamento();
        tipoVersamento.setCodTipoVersamento("DIRITTI_SEGRETERIA");
        tipoVersamento.setDescrizione("Diritti di segreteria");
        tipoVersamento.setAbilitato(true);
        tipoVersamentoRepository.save(tipoVersamento);

        TipoVersamentoDominio override = new TipoVersamentoDominio();
        override.setTipoVersamento(tipoVersamento);
        override.setIdDominio(idDominio);
        tipoVersamentoDominioRepository.save(override);
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
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        dominioRepository.deleteAll();
        applicazioneRepository.deleteAll();
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

        TipoVersamento tipoVersamento = new TipoVersamento();
        tipoVersamento.setCodTipoVersamento("IMU");
        tipoVersamento.setDescrizione("Imposta Municipale Unica");
        tipoVersamento.setAbilitato(true);
        tipoVersamento.setCodificaIuv("9902"); // deve risolvere in un prefisso numerico
        tipoVersamentoRepository.save(tipoVersamento);

        TipoVersamentoDominio override = new TipoVersamentoDominio();
        override.setTipoVersamento(tipoVersamento);
        override.setIdDominio(dominioConPrefisso.getId());
        tipoVersamentoDominioRepository.save(override);

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

    @Test
    void rifiutaConNotFoundSeIdA2ANonEsiste() throws Exception {
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
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rifiutaConNotFoundSeIdDominioNonEsiste() throws Exception {
        applicazioneRepository.save(ApplicazioneEntity.builder()
                .codApplicazione("A2A-DOMINIO-IGNOTO")
                .autoIuv(true)
                .firmaRicevuta("N")
                .trusted(true)
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

        mockMvc.perform(post("/posizioni-debitorie/{idA2A}", "A2A-DOMINIO-IGNOTO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
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
    @DisplayName("GET /posizioni-debitorie/{idA2A} trova le posizioni del debitore indicato, non altre")
    void findPosizioniDebitorieTrovaLePosizioniDelDebitore() throws Exception {
        creaPosizioneMinimaConDebitore("pos-find-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-find-2", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-find-altro-debitore", "VRDGNN80A01H501W");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(2))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(25))
                .andExpect(jsonPath("$.prossimiRisultati").doesNotExist())
                .andExpect(jsonPath("$.risultati.length()").value(2))
                .andExpect(jsonPath("$.risultati[*].idPosizioneDebitoria",
                        org.hamcrest.Matchers.containsInAnyOrder("pos-find-1", "pos-find-2")));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} popola prossimiRisultati quando ce ne sono altri, "
            + "con lo stesso idDebitore/limit e l'offset avanzato")
    void findPosizioniDebitoriePopolaProssimiRisultati() throws Exception {
        creaPosizioneMinimaConDebitore("pos-pagina-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-pagina-2", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-pagina-3", "FRRPLA90C41H501Y");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numRisultati").value(3))
                .andExpect(jsonPath("$.risultati.length()").value(2))
                .andExpect(jsonPath("$.prossimiRisultati")
                        .value("/posizioni-debitorie/A2A-TEST?idDebitore=FRRPLA90C41H501Y&offset=2&limit=2"));
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
    @DisplayName("GET /posizioni-debitorie/{idA2A} restituisce 400 (non 500) se offset non e' un numero")
    void findPosizioniDebitorieRestituisce400SeOffsetNonENumerico() throws Exception {
        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("offset", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /posizioni-debitorie/{idA2A} applica fields: restituisce solo i campi richiesti, "
            + "e li mantiene in prossimiRisultati")
    void findPosizioniDebitorieApplicaFields() throws Exception {
        creaPosizioneMinimaConDebitore("pos-fields-1", "FRRPLA90C41H501Y");
        creaPosizioneMinimaConDebitore("pos-fields-2", "FRRPLA90C41H501Y");

        mockMvc.perform(get("/posizioni-debitorie/{idA2A}", "A2A-TEST")
                        .param("idDebitore", "FRRPLA90C41H501Y")
                        .param("limit", "1")
                        .param("fields", "idPosizioneDebitoria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risultati[0].idPosizioneDebitoria").exists())
                .andExpect(jsonPath("$.risultati[0].descrizione").doesNotExist())
                .andExpect(jsonPath("$.risultati[0].soggettiDebitori").doesNotExist())
                .andExpect(jsonPath("$.risultati[0].idA2A").doesNotExist())
                .andExpect(jsonPath("$.prossimiRisultati")
                        .value("/posizioni-debitorie/A2A-TEST?idDebitore=FRRPLA90C41H501Y&offset=1&limit=1"
                                + "&fields=idPosizioneDebitoria"));
    }
}
