package it.govpay.pendenze.ricevuta;

/**
 * Errore non recuperabile nella conversione XML→DTO di una ricevuta (RT): XML malformato
 * rispetto al formato atteso dal valore di {@code Rpt.versione}. Non dovrebbe mai scattare in
 * pratica — l'XML proviene da pagoPA, gia' validato a monte dal Nodo — e' una difesa
 * esplicita, non un caso d'uso previsto. Nessun handler dedicato: ricade sul gestore generico
 * di {@code ProblemExceptionHandler} (500), stesso trattamento di
 * {@code RptRtConversionException} in govpay-console-api.
 */
public class RicevutaNonLeggibileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RicevutaNonLeggibileException(String message, Throwable cause) {
        super(message, cause);
    }
}
