package it.govpay.pendenze.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import it.govpay.common.auth.GovpayPasswordEncoder;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;

/**
 * Errori del client rilevati da Spring MVC prima di arrivare al controller: con una richiesta
 * autenticata devono diventare il 4xx corrispondente in formato problem+json, mai un 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErroriClientIntegrationTest {

    private static final String PRINCIPAL = "ERRORI-CLIENT-TEST";
    private static final String PASSWORD = "test-password";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UtenzaRepository utenzaRepository;

    @Autowired
    private GovpayPasswordEncoder passwordEncoder;

    @BeforeEach
    void preparaUtenza() {
        UtenzaEntity utenza = new UtenzaEntity();
        utenza.setPrincipal(PRINCIPAL);
        utenza.setPrincipalOriginale(PRINCIPAL);
        utenza.setAbilitato(true);
        utenza.setAutorizzazioneTipiVersStar(false);
        utenza.setAutorizzazioneDominiStar(false);
        utenza.setPassword(passwordEncoder.encode(PASSWORD));
        utenzaRepository.save(utenza);
    }

    @AfterEach
    void pulisci() {
        utenzaRepository.deleteAll();
    }

    @Test
    @DisplayName("metodo non ammesso sul path: 405 con header Allow")
    void metodoNonAmmesso405() throws Exception {
        mockMvc.perform(get("/admin/logging/loggers/refresh").with(httpBasic(PRINCIPAL, PASSWORD)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("Content-Type non supportato: 415")
    void mediaTypeNonSupportato415() throws Exception {
        mockMvc.perform(put("/admin/logging/loggers/it.govpay").with(httpBasic(PRINCIPAL, PASSWORD))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("DEBUG"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("parametro di query obbligatorio mancante: 400")
    void parametroMancante400() throws Exception {
        mockMvc.perform(get("/pendenze/{idA2A}", "A2A-QUALSIASI").with(httpBasic(PRINCIPAL, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Parametro obbligatorio mancante: 'numeroAvviso'."));
    }

    @Test
    @DisplayName("path inesistente: 404")
    void pathInesistente404() throws Exception {
        mockMvc.perform(get("/percorso-inesistente").with(httpBasic(PRINCIPAL, PASSWORD)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
