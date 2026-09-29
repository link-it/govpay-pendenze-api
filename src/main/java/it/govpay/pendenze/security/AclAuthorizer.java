package it.govpay.pendenze.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.pendenze.web.AccessoNegatoException;

/**
 * Verifica i diritti ACL (tabella condivisa {@code acl}) dell'applicazione autenticata sul
 * servizio "API Pendenze" — mirror di {@code it.govpay.console.security.AclAuthorizer} in
 * govpay-console-api, stesso schema/tabella condivisa col core v1/v2
 * ({@code it.govpay.model.Acl}, enum {@code Servizio.API_PENDENZE}/{@code Diritti.LETTURA,
 * SCRITTURA}), verificato in {@code AuthorizationManager.isAuthorized(Utenza, ...)}: un
 * diritto e' concesso se compare in una ACL diretta dell'utenza (id_utenza = quella
 * dell'applicazione) oppure in una ACL di definizione di un ruolo ({@code id_utenza IS NULL})
 * elencato nella colonna CSV {@code utenze.ruoli} dell'utenza.
 *
 * <p>Non replica {@code aclRuoliEsterni} del legacy (ruoli risolti da un provider esterno,
 * es. LDAP): non applicabile qui, dove l'unico metodo di autenticazione e' BASIC su
 * credenziali applicative (M2M), mai un operatore con identita' esterna.</p>
 */
@Service
public class AclAuthorizer {

    /** Codifica di {@code it.govpay.model.Acl.Servizio.API_PENDENZE} nel legacy. */
    private static final String SERVIZIO_API_PENDENZE = "API Pendenze";

    private enum Diritto {
        LETTURA("R", "lettura"),
        SCRITTURA("W", "scrittura");

        private final String codifica;
        private final String label;

        Diritto(String codifica, String label) {
            this.codifica = codifica;
            this.label = label;
        }
    }

    private final UtenzaRepository utenzaRepository;
    private final AclRepository aclRepository;

    public AclAuthorizer(UtenzaRepository utenzaRepository, AclRepository aclRepository) {
        this.utenzaRepository = utenzaRepository;
        this.aclRepository = aclRepository;
    }

    /**
     * Verifica che l'applicazione autenticata abbia il diritto di lettura su "API Pendenze";
     * altrimenti lancia {@link AccessoNegatoException} (403 problem+json).
     */
    @Transactional(readOnly = true)
    public void richiedeLettura() {
        richiede(Diritto.LETTURA);
    }

    /**
     * Verifica che l'applicazione autenticata abbia il diritto di scrittura su "API Pendenze";
     * altrimenti lancia {@link AccessoNegatoException} (403 problem+json).
     */
    @Transactional(readOnly = true)
    public void richiedeScrittura() {
        richiede(Diritto.SCRITTURA);
    }

    private void richiede(Diritto diritto) {
        UtenzaEntity utenza = utenzaAutenticata();
        boolean autorizzata = aclDellUtenza(utenza).stream()
                .anyMatch(acl -> SERVIZIO_API_PENDENZE.equals(acl.getServizio())
                        && contieneDiritto(acl.getDiritti(), diritto));
        if (!autorizzata) {
            throw new AccessoNegatoException("l'applicazione '" + utenza.getPrincipal()
                    + "' non dispone del diritto di " + diritto.label + " sul servizio '"
                    + SERVIZIO_API_PENDENZE + "'.");
        }
    }

    /**
     * Tollerante come la lettura del legacy ({@code Acl.Diritti.toEnum} per contenimento sulla
     * stringa grezza, non un valore enumerato esatto): {@code diritti} puo' valere "R", "W",
     * "RW" o "WR" indifferentemente.
     */
    private static boolean contieneDiritto(String diritti, Diritto diritto) {
        return diritti != null && diritti.toUpperCase().contains(diritto.codifica);
    }

    /**
     * {@link IllegalStateException} (500) solo se manca del tutto l'autenticazione (vero
     * errore di programmazione, va invocato dietro la SecurityFilterChain — vedi Javadoc di
     * {@code CurrentApplicazioneService#get}, stesso principio). Se invece l'autenticazione
     * c'e' ma il principal non risolve a un'utenza, e' {@link AccessoNegatoException} (403 —
     * bug del lead, 2026-09-29: qui lanciava ancora {@code IllegalStateException}/500, stesso
     * bug corretto in {@code CurrentApplicazioneService#get}).
     */
    private UtenzaEntity utenzaAutenticata() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("Nessuna applicazione autenticata nel SecurityContext.");
        }
        return utenzaRepository.findByPrincipal(authentication.getName())
                .orElseThrow(() -> new AccessoNegatoException(
                        "principal autenticato non risolvibile a un'utenza: " + authentication.getName()));
    }

    private List<AclEntity> aclDellUtenza(UtenzaEntity utenza) {
        List<AclEntity> acls = new ArrayList<>(aclRepository.findByIdUtenza(utenza.getId()));
        String csv = utenza.getRuoli();
        if (csv != null && !csv.isBlank()) {
            for (String ruolo : csv.split(",")) {
                String trimmed = ruolo.trim();
                if (!trimmed.isEmpty()) {
                    acls.addAll(aclRepository.findByRuoloAndIdUtenzaIsNull(trimmed));
                }
            }
        }
        return acls;
    }
}
