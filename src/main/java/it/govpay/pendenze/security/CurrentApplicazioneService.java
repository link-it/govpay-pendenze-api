package it.govpay.pendenze.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.pendenze.web.AccessoNegatoException;

/**
 * Risolve l'applicazione autenticata corrente — stesso pattern di {@code CurrentOperatorService}
 * in govpay-console-api, ma risolve {@link ApplicazioneEntity} invece di un operatore
 * (Utenza→Applicazione via {@code idUtenza}, FK piatta, invece di Utenza→Operatore).
 */
@Service
public class CurrentApplicazioneService {

    private final UtenzaRepository utenzaRepository;
    private final ApplicazioneRepository applicazioneRepository;

    public CurrentApplicazioneService(UtenzaRepository utenzaRepository,
            ApplicazioneRepository applicazioneRepository) {
        this.utenzaRepository = utenzaRepository;
        this.applicazioneRepository = applicazioneRepository;
    }

    /**
     * Risolve il principal corrente in {@link ApplicazioneEntity}. Lancia
     * {@link IllegalStateException} se non c'e' un'utenza autenticata (va invocato da codice
     * dietro la SecurityFilterChain) o se il principal autenticato non corrisponde a
     * un'applicazione (utenza operatore/altro tipo, mai atteso su questa API M2M).
     */
    @Transactional(readOnly = true)
    public ApplicazioneEntity get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("Nessuna applicazione autenticata nel SecurityContext.");
        }
        String principal = authentication.getName();
        UtenzaEntity utenza = utenzaRepository.findByPrincipal(principal)
                .orElseThrow(() -> new IllegalStateException(
                        "Principal autenticato non trovato in utenze: " + principal));
        return applicazioneRepository.findByIdUtenza(utenza.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Utenza autenticata [" + principal + "] non e' associata a nessuna applicazione."));
    }

    /**
     * Rifiuta con {@link AccessoNegatoException} (403) se l'applicazione autenticata non
     * coincide con {@code idA2A} — vedi Javadoc di classe di {@link AccessoNegatoException}.
     * Da chiamare a inizio di ogni metodo controller che prende {@code idA2A}.
     */
    @Transactional(readOnly = true)
    public void verificaIdA2A(String idA2A) {
        String codApplicazioneCorrente = get().getCodApplicazione();
        if (!codApplicazioneCorrente.equals(idA2A)) {
            throw new AccessoNegatoException("l'applicazione autenticata [" + codApplicazioneCorrente
                    + "] non corrisponde a idA2A [" + idA2A + "]");
        }
    }
}
