package it.govpay.pendenze.stato;

/**
 * Il servizio non e' operativo (stato health diverso da {@code UP}). Mappata a 503
 * problem+json con {@code Retry-After}.
 */
public class ServizioNonDisponibileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServizioNonDisponibileException(String message) {
        super(message);
    }
}
