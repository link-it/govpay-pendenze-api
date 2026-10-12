package it.govpay.pendenze.gde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import it.govpay.common.gde.GdeEventInfo;

/**
 * Verifica l'ordinamento REALE tra {@code TransactionIdFilter} (govpay-common) e
 * {@link GdeEventFilter} attraverso l'intera catena servlet registrata da Spring — a
 * differenza di {@link GdeEventFilterTest}, che chiama {@code doFilter} direttamente sul solo
 * {@code GdeEventFilter} e percio' non avrebbe mai potuto rilevare un problema di ordinamento
 * tra filtri diversi. Qui {@code PendenzeGdeService} e' mockato, quindi non serve un connettore
 * GDE configurato: interessa solo l'evento passato al mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GdeEventFilterOrdineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PendenzeGdeService pendenzeGdeService;

    @Test
    void eventoDellaCatenaRealePortaLoStessoTransactionIdRestituitoAlClient() throws Exception {
        MvcResult result = mockMvc.perform(get("/posizioni-debitorie/A2A-TEST")).andReturn();

        String transactionIdRisposta = result.getResponse().getHeader("X-Transaction-ID");
        assertThat(transactionIdRisposta).isNotBlank();

        ArgumentCaptor<GdeEventInfo> captor = ArgumentCaptor.forClass(GdeEventInfo.class);
        verify(pendenzeGdeService).inviaEventoAsync(captor.capture());
        assertThat(captor.getValue().getTransactionId()).isEqualTo(transactionIdRisposta);
    }
}
