package it.govpay.pendenze.avviso;

/**
 * Soggetto debitore senza anagrafica (solo {@code tipo}+{@code identificativo}, caso ammesso
 * dallo schema v3 {@code Soggetto}, dove {@code anagrafica} non e' {@code required}): l'avviso
 * PDF non si puo' generare, perche' {@code Debtor.full_name} e' invece obbligatorio nel
 * contratto di govpay-stampe-api. Nessun precedente legacy applicabile (il PDF legacy e' un
 * template interno privo di validazione di schema) — vincolo introdotto dal nuovo contratto
 * REST. Mappata a 422 problem+json, stesso trattamento di {@link AvvisoMbtException}.
 */
public class AvvisoAnagraficaAssenteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AvvisoAnagraficaAssenteException(String messaggio) {
        super(messaggio);
    }
}
