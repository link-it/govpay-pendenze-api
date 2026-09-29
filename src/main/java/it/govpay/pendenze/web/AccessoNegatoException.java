package it.govpay.pendenze.web;

/**
 * Sollevata quando l'applicazione autenticata (risolta dalle credenziali della richiesta)
 * non coincide con l'{@code idA2A} indicato nel path — v2 la applica su ogni operazione che
 * prende {@code idA2A} (rifiuto esplicito, {@code AutorizzazioneUtils}/{@code EsitoOperazione.APP_002}
 * in {@code wars/api-pendenze/.../v2/controller/PendenzeController.java} del monorepo
 * legacy): un'applicazione autenticata come "A" non puo' vedere/modificare dati di "B" solo
 * perche' ne indovina l'idA2A nel path.
 */
public class AccessoNegatoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccessoNegatoException(String messaggio) {
        super(messaggio);
    }
}
