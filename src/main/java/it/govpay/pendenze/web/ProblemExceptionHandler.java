package it.govpay.pendenze.web;

import java.net.URI;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import it.govpay.pendenze.api.model.Problem;
import it.govpay.pendenze.exception.RisorsaGiaEsistenteException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Traduce le eccezioni sollevate dai controller/converter nel formato {@code Problem} (RFC
 * 7807) richiesto dallo YAML v3 — stesso pattern gia' consolidato in
 * {@code govpay-console-api} ({@code it.govpay.console.web.ProblemExceptionHandler}), qui
 * ridotto alle sole eccezioni che gli endpoint finora implementati possono sollevare: va
 * ampliato mano a mano che nuovi endpoint vengono aggiunti (es. {@code TransizioneStatoNonAmmessaException}
 * quando arrivera' l'annullamento di un'opzione di pagamento).
 */
@RestControllerAdvice
public class ProblemExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail, request, ex);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, detail, request, ex);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Body della richiesta non leggibile.", request, ex);
    }

    /**
     * Bug del lead, 2026-09-27: un parametro di query obbligatorio mancante (es. {@code idDebitore})
     * o non convertibile al tipo atteso (es. {@code offset=abc}) finivano nel gestore generico
     * (500) — sono invece errori della richiesta, non del servizio.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Problem> handleMissingParameter(MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Parametro obbligatorio mancante: '" + ex.getParameterName() + "'.", request, ex);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Valore non valido per il parametro '" + ex.getName() + "': "
                        + (ex.getValue() != null ? ex.getValue() : "<assente>") + ".",
                request, ex);
    }

    @ExceptionHandler(ValidazioneNonSuperataException.class)
    public ResponseEntity<Problem> handleValidazioneNonSuperata(ValidazioneNonSuperataException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex);
    }

    @ExceptionHandler({ AnagraficaNonTrovataException.class, RisorsaNonTrovataException.class })
    public ResponseEntity<Problem> handleNonTrovata(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(RisorsaGiaEsistenteException.class)
    public ResponseEntity<Problem> handleGiaEsistente(RisorsaGiaEsistenteException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleGeneric(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Errore interno del servizio.", request, ex);
    }

    private ResponseEntity<Problem> build(HttpStatus status, String detail, HttpServletRequest request,
            Exception ex) {
        Problem problem = new Problem(status.value())
                .title(status.getReasonPhrase())
                .detail(detail)
                .instance(URI.create(request.getRequestURI()));
        logException(status, request, ex);
        return ResponseEntity.status(status).contentType(PROBLEM_JSON).body(problem);
    }

    private void logException(HttpStatus status, HttpServletRequest request, Exception ex) {
        String msg = "{} {} -> {} {}";
        if (status.is5xxServerError()) {
            log.error(msg, request.getMethod(), request.getRequestURI(), status.value(), status.getReasonPhrase(),
                    ex);
        } else if (log.isWarnEnabled()) {
            log.warn(msg, request.getMethod(), request.getRequestURI(), status.value(), status.getReasonPhrase(),
                    ex.toString());
        }
    }
}
