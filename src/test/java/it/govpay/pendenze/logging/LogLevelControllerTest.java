package it.govpay.pendenze.logging;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.pendenze.security.AclEntity;
import it.govpay.pendenze.security.AclRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Verifica {@code /admin/logging} a livello di integrazione: protezione ACL sul servizio
 * "Configurazione e manutenzione" (distinto da "API Pendenze", verificato non bastare) e
 * comportamento dell'endpoint una volta autorizzato.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogLevelControllerTest {

    private static final String PRINCIPAL_AUTORIZZATO = "ADMIN-LOGGING-TEST";
    private static final String PRINCIPAL_SOLO_API_PENDENZE = "SOLO-API-PENDENZE-TEST";
    private static final String PASSWORD = "test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UtenzaRepository utenzaRepository;

    @Autowired
    private AclRepository aclRepository;

    @Autowired
    private GovpayPasswordEncoder passwordEncoder;

    @BeforeEach
    void preparaUtenze() {
        creaUtenza(PRINCIPAL_AUTORIZZATO, "Configurazione e manutenzione", "RW");
        creaUtenza(PRINCIPAL_SOLO_API_PENDENZE, "API Pendenze", "RW");
    }

    @AfterEach
    void pulisci() {
        aclRepository.deleteAll();
        utenzaRepository.deleteAll();
    }

    private void creaUtenza(String principal, String servizio, String diritti) {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(principal);
        utenza.setPrincipalOriginale(principal);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
        utenza.setPassword(passwordEncoder.encode(PASSWORD));
        utenza = utenzaRepository.save(utenza);

        AclEntity acl = new AclEntity();
        acl.setServizio(servizio);
        acl.setDiritti(diritti);
        acl.setIdUtenza(utenza.getId());
        aclRepository.save(acl);
    }

    @Test
    @DisplayName("un'utenza con diritti su 'Configurazione e manutenzione' vede l'elenco dei logger")
    void loggersRestituisceLElencoSeAutorizzato() throws Exception {
        mockMvc.perform(get("/admin/logging/loggers").with(httpBasic(PRINCIPAL_AUTORIZZATO, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("un'utenza con diritti solo su 'API Pendenze' non basta: 403")
    void loggersRifiutaConForbiddenSeSoloApiPendenze() throws Exception {
        mockMvc.perform(
                get("/admin/logging/loggers").with(httpBasic(PRINCIPAL_SOLO_API_PENDENZE, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("senza autenticazione: 401")
    void loggersRifiutaConUnauthorizedSenzaCredenziali() throws Exception {
        mockMvc.perform(get("/admin/logging/loggers")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("impostazione e ripristino del livello di un logger gestito")
    void impostaERipristinaLivello() throws Exception {
        mockMvc.perform(put("/admin/logging/loggers/it.govpay")
                        .with(httpBasic(PRINCIPAL_AUTORIZZATO, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"livello\":\"DEBUG\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/admin/logging/loggers/it.govpay")
                        .with(httpBasic(PRINCIPAL_AUTORIZZATO, PASSWORD)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("impostazione del livello rifiutata con diritti insufficienti: 403, nessuna modifica")
    void impostaLivelloRifiutaConForbiddenSeSoloApiPendenze() throws Exception {
        mockMvc.perform(put("/admin/logging/loggers/it.govpay")
                        .with(httpBasic(PRINCIPAL_SOLO_API_PENDENZE, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"livello\":\"DEBUG\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("logger fuori ambito gestibile: 400")
    void impostaLivelloRifiutaConBadRequestSeLoggerNonGestito() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/admin/logging/loggers/org.springframework")
                        .with(httpBasic(PRINCIPAL_AUTORIZZATO, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"livello\":\"DEBUG\"}"))
                .andExpect(status().isBadRequest());
    }
}
