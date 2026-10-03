package it.govpay.pendenze.rendicontazione;

/**
 * Errore non recuperabile nella conversione entity→DTO di una rendicontazione: {@code esito}
 * nullo. Caso reale, non un'ipotesi — verificato contro il legacy
 * ({@code it.govpay.core.business.Rendicontazioni#salva}): se pagoPA invia un codice esito non
 * previsto da {@code EsitoRendicontazione}, il legacy intercetta la
 * {@code CodificaInesistenteException}, registra un'anomalia (007110) e persiste comunque la
 * riga con {@code esito} nullo, invece di interrompere l'elaborazione del flusso. Nessun
 * handler dedicato: ricade sul gestore generico di {@code ProblemExceptionHandler} (500),
 * stesso trattamento di {@code RicevutaNonLeggibileException}.
 */
public class RendicontazioneNonLeggibileException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RendicontazioneNonLeggibileException(String message) {
        super(message);
    }
}
