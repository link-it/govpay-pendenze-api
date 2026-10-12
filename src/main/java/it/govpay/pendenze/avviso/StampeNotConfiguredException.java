package it.govpay.pendenze.avviso;

/**
 * Lanciata quando il microservizio {@code govpay-stampe} e' richiesto (ramo PDF) ma
 * {@code app.stampe.base-url} non e' configurata. Mappata a 503 problem+json.
 */
public class StampeNotConfiguredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StampeNotConfiguredException() {
        super("Microservizio govpay-stampe non configurato (app.stampe.base-url mancante).");
    }
}
