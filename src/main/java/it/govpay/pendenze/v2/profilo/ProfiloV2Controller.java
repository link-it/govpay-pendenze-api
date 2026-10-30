package it.govpay.pendenze.v2.profilo;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.v2.api.model.Profilo;
import it.govpay.pendenze.v2.api.rest.ProfiloApi;
import it.govpay.pendenze.web.AccessoNegatoException;

/**
 * Implementa {@link ProfiloApi}: legacy {@code ProfiloController.profiloGET} (v2
 * {@code wars/api-pendenze}) risolve il profilo dall'{@code Authentication} senza nessun
 * controllo ACL aggiuntivo (nessuna chiamata a un equivalente di
 * {@code it.govpay.pendenze.security.AclAuthorizer} nel legacy) — stesso principio qui,
 * nessuna {@code richiedeLettura()}: l'endpoint riporta le autorizzazioni dell'utenza, non ne
 * richiede una specifica per essere letto. Vedi Javadoc di classe di {@link ProfiloMapper}
 * per il dettaglio della fedelta' al comportamento legacy, incluso il bug per cui {@code acl}
 * (required nello YAML) non e' mai popolato.
 */
@RestController
@RequestMapping("${govpay.pendenze.api.v2.base-path:}")
public class ProfiloV2Controller implements ProfiloApi {

    private final UtenzaRepository utenzaRepository;
    private final ProfiloMapper profiloMapper;

    public ProfiloV2Controller(UtenzaRepository utenzaRepository, ProfiloMapper profiloMapper) {
        this.utenzaRepository = utenzaRepository;
        this.profiloMapper = profiloMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Profilo> getProfilo() {
        UtenzaEntity utenza = utenzaAutenticata();
        return ResponseEntity.ok(profiloMapper.costruisciProfilo(utenza));
    }

    /**
     * {@link IllegalStateException} (500) solo se manca del tutto l'autenticazione — vero
     * errore di programmazione, stesso principio di
     * {@code CurrentApplicazioneService#get}/{@code AclAuthorizer#utenzaAutenticata}: va
     * invocato solo dietro la SecurityFilterChain, che avrebbe gia' rifiutato con 401.
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
}
