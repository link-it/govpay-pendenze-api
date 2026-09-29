package it.govpay.pendenze.web;

/**
 * Sollevata quando le credenziali della richiesta sono valide ma il chiamante non ha il
 * permesso per l'operazione richiesta — a differenza di 401 (nessuna autenticazione o
 * credenziali non valide, gestito dal filtro di govpay-common-auth). Tre casi, tutti mappati
 * su 403:
 * <ul>
 * <li>l'applicazione autenticata non coincide con l'{@code idA2A} indicato nel path — v2 la
 * applica su ogni operazione che prende {@code idA2A} (rifiuto esplicito,
 * {@code AutorizzazioneUtils}/{@code EsitoOperazione.APP_002} in
 * {@code wars/api-pendenze/.../v2/controller/PendenzeController.java} del monorepo legacy);</li>
 * <li>l'applicazione autenticata non ha il diritto ACL richiesto sul servizio "API Pendenze"
 * (vedi {@link it.govpay.pendenze.security.AclAuthorizer});</li>
 * <li>l'utenza autenticata (credenziali valide, {@code abilitato=true}) non e' associata a
 * nessuna {@code ApplicazioneEntity} — bug del lead, 2026-09-29: {@code CurrentApplicazioneService#get}
 * lanciava {@code IllegalStateException} in questo caso (500 invece di 403): un'utenza senza
 * applicazione e' una condizione di dato reale (configurazione incompleta), non un errore di
 * programmazione — vedi Javadoc di {@link it.govpay.pendenze.security.CurrentApplicazioneService#get}.</li>
 * </ul>
 */
public class AccessoNegatoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccessoNegatoException(String messaggio) {
        super(messaggio);
    }
}
