package it.govpay.pendenze.stato;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /status} con una dipendenza non disponibile: un {@link HealthIndicator} di test
 * sempre {@code DOWN} porta lo stato aggregato a {@code DOWN}, e il servizio risponde 503
 * problem+json con {@code Retry-After} ({@code RAC_REST_NAME_011}, {@code RAC_ROBUSTEZZA_002}).
 * Contesto separato da {@link StatoControllerTest} per non rendere {@code DOWN} lo stato degli
 * altri test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatoControllerNonOperativoTest {

    @TestConfiguration
    static class DipendenzaNonDisponibile {

        @Bean
        HealthIndicator dipendenzaDiTest() {
            return () -> Health.down().build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /status con una dipendenza DOWN risponde 503 con Retry-After")
    void statusConDipendenzaDownRisponde503() throws Exception {
        mockMvc.perform(get("/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.status").value(503));
    }
}
