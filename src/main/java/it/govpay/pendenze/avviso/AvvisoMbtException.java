package it.govpay.pendenze.avviso;

/**
 * Pendenza con Marca da Bollo Telematica: l'avviso di pagamento PDF non le si applica.
 * Mappata a 422 problem+json.
 */
public class AvvisoMbtException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AvvisoMbtException(String messaggio) {
        super(messaggio);
    }
}
