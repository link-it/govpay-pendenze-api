package it.govpay.pendenze.v2.pendenza;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
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
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Verifica {@code PATCH /v2/pendenze/{idA2A}/{idPendenza}} ({@code updatePendenza}) a livello
 * di integrazione — stesso impianto di {@link AddPendenzaControllerTest} (duplicato per lo
 * stesso motivo documentato li': nessuna superclasse comune tra controller di test analoghi
 * di questo package), senza lo stub di {@code govpay-stampe} (qui non serve).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(UpdatePendenzaControllerTest.BasicAuthDiDefaultConfig.class)
class UpdatePendenzaControllerTest {

    private static final String PRINCIPAL = "A2A-PATCH-TEST";
    private static final String PASSWORD = "test-password";
    private static final String ID_DOMINIO = "98765432101";

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
    private AllegatoRepository allegatoRepository;

    private Long idApplicazione;

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
                .ragioneSociale("Comune di Test Patch")
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

    private void creaPendenzaSoluzioneUnica(String idPendenza) throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, idPendenza)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoBase("pagamento di test", 10.00, "voce-" + idPendenza)))
                .andExpect(status().isCreated());
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

    private String corpoConDocumentoERata(String idPendenza, String identificativo, int rata) {
        return """
                {
                  "idTipoPendenza": "DIRITTI_SEGRETERIA",
                  "idDominio": "%s",
                  "causale": "rata %s",
                  "soggettoPagatore": { "tipo": "F", "identificativo": "RSSMRA80A01H501U", "anagrafica": "Mario Rossi" },
                  "importo": 10.00,
                  "documento": { "identificativo": "%s", "descrizione": "Documento di test", "rata": %s },
                  "voci": [ { "idVocePendenza": "voce-%s", "importo": 10.00, "descrizione": "test", \
                "codEntrata": "DIRITTI_SEGRETERIA" } ]
                }
                """.formatted(ID_DOMINIO, rata, identificativo, rata, idPendenza);
    }

    private Pendenza leggiPendenza(String idPendenza) {
        return pendenzaRepository.findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(
                idApplicazione, idPendenza).orElseThrow();
    }

    @Test
    @DisplayName("PATCH risponde 403 se l'applicazione non e' trusted e non e' autorizzata su dominio/"
            + "tipoVersamento, PRIMA di applicare qualunque operazione")
    void patchNonAutorizzataRispondeAccessoNegato() throws Exception {
        creaPendenzaSoluzioneUnica("pend-403-1");

        ApplicazioneEntity applicazione = applicazioneRepository.findByCodApplicazione(PRINCIPAL).orElseThrow();
        applicazione.setTrusted(false);
        applicazioneRepository.save(applicazione);

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-403-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/ack", "value": false } ]
                        """))
                .andExpect(status().isForbidden());

        // ack e' true di default (vedi Pendenza#ack): se il controllo di autorizzazione non
        // avesse bloccato la richiesta PRIMA di applicare la patch, questa asserzione fallirebbe.
        Assertions.assertTrue(leggiPendenza("pend-403-1").isAck());
    }

    @Test
    @DisplayName("PATCH /descrizioneStato (REPLACE) aggiorna la pendenza (200)")
    void patchDescrizioneStatoAggiorna() throws Exception {
        creaPendenzaSoluzioneUnica("pend-ds-1");

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ds-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/descrizioneStato", "value": "motivo di test" } ]
                        """))
                .andExpect(status().isOk());

        Assertions.assertEquals("motivo di test", leggiPendenza("pend-ds-1").getDescrizioneStato());
    }

    @Test
    @DisplayName("PATCH /nota (ADD) scrive sulla stessa colonna di /descrizioneStato (quirk legacy replicato)")
    void patchNotaScriveSuDescrizioneStato() throws Exception {
        creaPendenzaSoluzioneUnica("pend-nota-1");

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-nota-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "ADD", "path": "/nota", "value": { "oggetto": "nota di test" } } ]
                        """))
                .andExpect(status().isOk());

        Assertions.assertEquals("nota di test", leggiPendenza("pend-nota-1").getDescrizioneStato());
    }

    @Test
    @DisplayName("PATCH /ack (REPLACE) aggiorna la pendenza (200)")
    void patchAckAggiorna() throws Exception {
        creaPendenzaSoluzioneUnica("pend-ack-1");
        Assertions.assertTrue(leggiPendenza("pend-ack-1").isAck());

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ack-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/ack", "value": false } ]
                        """))
                .andExpect(status().isOk());

        Assertions.assertFalse(leggiPendenza("pend-ack-1").isAck());
    }

    @Test
    @DisplayName("PATCH /stato=ANNULLATA su un'opzione con una sola pendenza: annulla (200)")
    void patchStatoAnnullataConUnaSolaPendenza() throws Exception {
        creaPendenzaSoluzioneUnica("pend-ann-1");

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-ann-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/stato", "value": "ANNULLATA" } ]
                        """))
                .andExpect(status().isOk());

        Assertions.assertEquals(StatoPendenza.ANNULLATO, leggiPendenza("pend-ann-1").getStato());
    }

    @Test
    @DisplayName("PATCH /stato=NON_ESEGUITA dopo un annullamento: ripristina (200)")
    void patchStatoNonEseguitaRipristina() throws Exception {
        creaPendenzaSoluzioneUnica("pend-rip-1");
        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-rip-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/stato", "value": "ANNULLATA" } ]
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-rip-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/stato", "value": "NON_ESEGUITA" } ]
                        """))
                .andExpect(status().isOk());

        Assertions.assertEquals(StatoPendenza.NON_ESEGUITO, leggiPendenza("pend-rip-1").getStato());
    }

    @Test
    @DisplayName("PATCH /stato con un valore non ammesso (ESEGUITA): 400")
    void patchStatoValoreNonAmmesso() throws Exception {
        creaPendenzaSoluzioneUnica("pend-bad-1");

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-bad-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/stato", "value": "ESEGUITA" } ]
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /stato su un'opzione con piu' di una pendenza (piano rateale): 409, nessuna cascata")
    void patchStatoSuOpzioneConPiuDiUnaPendenza() throws Exception {
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-rate-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoERata("rate-1", "DOC-PATCH-RATE", 1)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-rate-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(corpoConDocumentoERata("rate-2", "DOC-PATCH-RATE", 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-rate-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/stato", "value": "ANNULLATA" } ]
                        """))
                .andExpect(status().isConflict());

        // La pendenza sorella non e' stata toccata dal tentativo rifiutato.
        Assertions.assertEquals(StatoPendenza.NON_ESEGUITO, leggiPendenza("pend-rate-2").getStato());
    }

    @Test
    @DisplayName("PATCH con un path non valido: 400")
    void patchPathNonValido() throws Exception {
        creaPendenzaSoluzioneUnica("pend-path-1");

        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "pend-path-1")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/nonEsiste", "value": "x" } ]
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH su una pendenza inesistente: 404")
    void patchPendenzaInesistente() throws Exception {
        mockMvc.perform(patch("/v2/pendenze/{idA2A}/{idPendenza}", PRINCIPAL, "non-esiste")
                .contentType("application/json-patch+json")
                .content("""
                        [ { "op": "REPLACE", "path": "/ack", "value": false } ]
                        """))
                .andExpect(status().isNotFound());
    }
}
