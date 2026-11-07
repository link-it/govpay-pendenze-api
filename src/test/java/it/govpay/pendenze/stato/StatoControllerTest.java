package it.govpay.pendenze.stato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifica {@code GET /status} ({@code RAC_REST_NAME_011} AgID): raggiungibile senza
 * credenziali sulla porta delle API, 200 problem+json con il servizio operativo. Il caso
 * non operativo e' in {@link StatoControllerNonOperativoTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /status senza credenziali risponde 200 problem+json")
    void statusSenzaCredenzialiRisponde200() throws Exception {
        // Nel profilo di test il base path v3 e' vuoto: /status invece di /v3/status.
        mockMvc.perform(get("/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.title").value("OK"));
    }
}
