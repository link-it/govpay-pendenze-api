package it.govpay.pendenze.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import it.govpay.pendenze.api.model.Problem;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Verifica isolata (nessun contesto Spring): riprodurre un vero conflitto di lock
 * ottimistico attraverso l'intero stack REST richiederebbe due transazioni davvero
 * concorrenti (thread reali) — fragile/flaky in una suite automatica. Qui si verifica solo
 * che l'handler traduca correttamente il tipo di eccezione nel {@code Problem} atteso,
 * chiamandolo direttamente.
 */
class ProblemExceptionHandlerTest {

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler();

    /**
     * Bug del lead, 2026-09-29: prima di questo handler, un {@code OptimisticLockingFailureException}
     * non tradotto da nessun chiamante (es. {@code PosizioneDebitoriaService#attiva}/
     * {@code annulla}, verificate dal lead con una prova reale a due transazioni sovrapposte)
     * sarebbe caduto nel gestore generico (500) invece che in un 409 applicativo.
     */
    @Test
    @DisplayName("un conflitto di lock ottimistico non tradotto dal chiamante diventa comunque 409, non 500")
    void optimisticLockingFailureDiventa409() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("PATCH");
        when(request.getRequestURI()).thenReturn("/posizioni-debitorie/A2A-TEST/pos-1/opzioni-pagamento/"
                + "3fa85f64-5717-4562-b3fc-2c963f66afa6");

        ResponseEntity<Problem> risposta = handler.handleOptimisticLockingFailure(
                new OptimisticLockingFailureException("conflitto simulato"), request);

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(risposta.getBody().getStatus()).isEqualTo(409);
        assertThat(risposta.getBody().getDetail()).contains("modificata concorrentemente");
    }
}
