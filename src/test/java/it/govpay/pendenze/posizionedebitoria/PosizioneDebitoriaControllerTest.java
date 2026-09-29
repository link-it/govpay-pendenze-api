package it.govpay.pendenze.posizionedebitoria;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MvcResult;

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
    private TipoTributoRepository tipoTributoRepository;

    @Autowired
    private TributoRepository tributoRepository;

    @Autowired
    private IbanAccreditoRepository ibanAccreditoRepository;

    @Autowired
    private UnitaOperativaRepository unitaOperativaRepository;

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
        tipoVersamentoDominioRepository.deleteAll();
        tipoVersamentoRepository.deleteAll();
        tributoRepository.deleteAll();
        tipoTributoRepository.deleteAll();
        ibanAccreditoRepository.deleteAll();
        unitaOperativaRepository.deleteAll();
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
}
