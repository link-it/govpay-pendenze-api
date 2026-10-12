package it.govpay.pendenze.avviso;

/**
 * Lanciata quando la chiamata al microservizio {@code govpay-stampe} fallisce (timeout,
 * errore HTTP, circuito aperto, errore IO). Mappata a 502 problem+json.
 */
public class StampeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StampeUnavailableException(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
