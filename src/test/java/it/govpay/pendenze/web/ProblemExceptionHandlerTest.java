package it.govpay.pendenze.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * Un {@code OptimisticLockingFailureException} non tradotto dal chiamante (es.
     * {@code PosizioneDebitoriaService#attiva}/{@code annulla}, che la lasciano propagare
     * cosi' come arriva dal commit implicito di fine transazione) deve comunque diventare un
     * 409 applicativo, non cadere nel gestore generico (500).
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

    @Test
    @DisplayName("NoResourceFoundException (URL inesistente) diventa 404, non 500")
    void noResourceFoundDiventa404() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/percorso-inesistente");

        ResponseEntity<Problem> risposta = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/percorso-inesistente", null), request);

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(risposta.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("NoHandlerFoundException (URL inesistente) diventa 404, non 500")
    void noHandlerFoundDiventa404() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/percorso-inesistente");

        ResponseEntity<Problem> risposta = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/percorso-inesistente", new HttpHeaders()), request);

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(risposta.getBody().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Content-Type non supportato diventa 415, non 500, con dettaglio dei tipi ammessi")
    void mediaTypeNotSupportedDiventa415() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/posizioni-debitorie/A2A-TEST");

        ResponseEntity<Problem> risposta = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                request);

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(risposta.getBody().getStatus()).isEqualTo(415);
        assertThat(risposta.getBody().getDetail()).contains("text/plain").contains("application/json");
    }

    @Test
    @DisplayName("Accept incompatibile diventa 406, non 500")
    void mediaTypeNotAcceptableDiventa406() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/posizioni-debitorie/A2A-TEST");

        ResponseEntity<Problem> risposta = handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), request);

        assertThat(risposta.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(risposta.getBody().getStatus()).isEqualTo(406);
    }
}
