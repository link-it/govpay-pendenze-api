package it.govpay.pendenze.web;

import java.net.URI;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import it.govpay.pendenze.api.model.Problem;
import it.govpay.pendenze.avviso.AvvisoAnagraficaAssenteException;
import it.govpay.pendenze.avviso.AvvisoMbtException;
import it.govpay.pendenze.avviso.StampeNotConfiguredException;
import it.govpay.pendenze.avviso.StampeUnavailableException;
import it.govpay.pendenze.exception.ModificaConcorrenteException;
import it.govpay.pendenze.exception.RisorsaGiaEsistenteException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Traduce le eccezioni sollevate dai controller/converter nel formato {@code Problem} (RFC
 * 7807) richiesto dallo YAML v3 — stesso pattern gia' consolidato in
 * {@code govpay-console-api} ({@code it.govpay.console.web.ProblemExceptionHandler}), qui
 * ridotto alle sole eccezioni che gli endpoint finora implementati possono sollevare: va
 * ampliato mano a mano che nuovi endpoint vengono aggiunti.
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
     * Un parametro di query obbligatorio mancante (es. {@code idDebitore}) o non convertibile
     * al tipo atteso (es. {@code offset=abc}) e' un errore della richiesta, non del servizio:
     * senza questo handler finirebbe nel gestore generico (500).
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

    /**
     * URL non mappato a nessun controller (path sbagliato, non una risorsa applicativa
     * mancante come {@link RisorsaNonTrovataException}) — senza questo handler cadrebbe nel
     * gestore generico (500). Entrambe le eccezioni gestite insieme, come in
     * {@code govpay-console-api}: quale delle due venga effettivamente sollevata dipende da
     * dettagli di versione/configurazione di Spring, non da qualcosa che questo servizio
     * controlla.
     */
    @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
    public ResponseEntity<Problem> handleNotFound(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Problem> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {
        String detail = "Content-Type non supportato"
                + (ex.getContentType() != null ? " (" + ex.getContentType() + ")" : "")
                + ": tipi ammessi " + ex.getSupportedMediaTypes() + ".";
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail, request, ex);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Problem> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex,
            HttpServletRequest request) {
        return build(HttpStatus.NOT_ACCEPTABLE, "Accept header non compatibile coi content-type supportati.",
                request, ex);
    }

    /**
     * Stessa risposta di {@link #handleMediaTypeNotAcceptable}, per un endpoint che sceglie il
     * content-type a mano invece di usare la negoziazione automatica di Spring MVC — vedi
     * Javadoc di {@link NotAcceptableMediaTypeException}.
     */
    @ExceptionHandler(NotAcceptableMediaTypeException.class)
    public ResponseEntity<Problem> handleNotAcceptableMediaType(NotAcceptableMediaTypeException ex,
            HttpServletRequest request) {
        return build(HttpStatus.NOT_ACCEPTABLE, ex.getMessage(), request, ex);
    }

    @ExceptionHandler({ AvvisoMbtException.class, AvvisoAnagraficaAssenteException.class })
    public ResponseEntity<Problem> handleAvvisoNonGenerabile(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(StampeUnavailableException.class)
    public ResponseEntity<Problem> handleStampeUnavailable(StampeUnavailableException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(StampeNotConfiguredException.class)
    public ResponseEntity<Problem> handleStampeNotConfigured(StampeNotConfiguredException ex,
            HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(AccessoNegatoException.class)
    public ResponseEntity<Problem> handleAccessoNegato(AccessoNegatoException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request, ex);
    }

    @ExceptionHandler(RisorsaGiaEsistenteException.class)
    public ResponseEntity<Problem> handleGiaEsistente(RisorsaGiaEsistenteException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
    }

    /**
     * Sollevata da {@code PosizioneDebitoriaController#addOpzionePagamento} quando rifiuta
     * l'aggiunta di un'alternativa a una posizione che ha gia' un'opzione ATTIVATA.
     */
    @ExceptionHandler(TransizioneStatoNonAmmessaException.class)
    public ResponseEntity<Problem> handleTransizioneStatoNonAmmessa(TransizioneStatoNonAmmessaException ex,
            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
    }

    /**
     * Conflitto di lock ottimistico su {@code PosizioneDebitoria} (vedi Javadoc di
     * {@code PosizioneDebitoriaService#aggiungiOpzionePagamento}) — 409 con invito a
     * riprovare, non un errore interno: la richiesta stessa era corretta, solo in corsa con
     * un'altra sulla stessa posizione.
     */
    @ExceptionHandler(ModificaConcorrenteException.class)
    public ResponseEntity<Problem> handleModificaConcorrente(ModificaConcorrenteException ex,
            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
    }

    /**
     * Rete di sicurezza al confine REST per {@code attiva}/{@code annulla}
     * (govpay-common-pendenze): a differenza di
     * {@code aggiungiOpzionePagamento}/{@code aggiorna}, questi due metodi non passano da
     * {@code saveAndFlush} con un {@code catch} dedicato — lanciano
     * {@code ObjectOptimisticLockingFailureException} cosi' come arriva dal commit implicito
     * di fine transazione, con un conflitto rilevato correttamente (l'attivazione perdente
     * viene annullata) ma non tradotto. Nessun endpoint REST li richiama ancora, ma quando
     * succedera' questo handler li protegge comunque, senza dover ricordarsi di aggiungere
     * una traduzione esplicita in ogni nuovo punto di chiamata — stesso status (409) di
     * {@link ModificaConcorrenteException}, il percorso esplicito resta preferibile dove
     * gia' presente (messaggio piu' specifico).
     *
     * <p><b>Non riguarda</b> il futuro processo che registrera' i pagamenti reali (es. da
     * notifica pagoPA) chiamando {@code attiva()}: quel chiamante non e' una richiesta REST
     * sincrona con un client che puo' "riprovare" — deve invece rileggere l'aggregato e
     * rieseguire l'intera transazione applicativa, con un numero limitato di tentativi
     * (retry-and-reread), non limitarsi a propagare un 409 a chi non può interpretarlo.</p>
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Problem> handleOptimisticLockingFailure(OptimisticLockingFailureException ex,
            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "La risorsa e' stata modificata concorrentemente: riprovare.", request,
                ex);
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
