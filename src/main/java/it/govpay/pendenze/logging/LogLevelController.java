package it.govpay.pendenze.logging;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.common.logging.level.AbstractLogLevelController;
import it.govpay.common.logging.level.DynamicLogLevelService;
import it.govpay.common.logging.level.LivelloLogRequest;
import it.govpay.common.logging.level.LivelloLoggerInfo;
import it.govpay.pendenze.security.AclAuthorizer;

/**
 * Gestione a runtime dei livelli di log.
 * <p>
 * La modifica ha effetto immediato su questo nodo ed e' persistita sulla riga
 * {@code log_level} della tabella {@code configurazione}: gli altri nodi del
 * cluster la applicano al proprio refresh periodico.
 * <p>
 * Gli endpoint non fanno parte del contratto OpenAPI v3 di questo servizio:
 * sono di amministrazione e, oltre all'autenticazione garantita dalla catena
 * di sicurezza (default deny), richiedono il diritto ACL sul servizio
 * "Configurazione e manutenzione" — lettura per le consultazioni, scrittura
 * per le modifiche.
 */
@RestController
@RequestMapping("/admin/logging")
public class LogLevelController extends AbstractLogLevelController {

    private final AclAuthorizer aclAuthorizer;

    public LogLevelController(DynamicLogLevelService logLevelService, AclAuthorizer aclAuthorizer) {
        super(logLevelService);
        this.aclAuthorizer = aclAuthorizer;
    }

    @GetMapping("/loggers")
    public ResponseEntity<List<LivelloLoggerInfo>> loggers() {
        aclAuthorizer.richiedeLetturaConfigurazioneEManutenzione();
        return getLoggers();
    }

    @GetMapping("/loggers/dinamici")
    public ResponseEntity<Map<String, String>> dinamici() {
        aclAuthorizer.richiedeLetturaConfigurazioneEManutenzione();
        return getLivelliDinamici();
    }

    @PutMapping("/loggers/{logger}")
    public ResponseEntity<Object> impostaLivello(@PathVariable String logger,
            @RequestBody LivelloLogRequest richiesta) {
        aclAuthorizer.richiedeScritturaConfigurazioneEManutenzione();
        return setLivello(logger, richiesta);
    }

    @DeleteMapping("/loggers/{logger}")
    public ResponseEntity<Object> ripristinaLivello(@PathVariable String logger) {
        aclAuthorizer.richiedeScritturaConfigurazioneEManutenzione();
        return rimuoviLivello(logger);
    }

    @DeleteMapping("/loggers")
    public ResponseEntity<Object> ripristinaTutti() {
        aclAuthorizer.richiedeScritturaConfigurazioneEManutenzione();
        return resetLivelli();
    }

    @PostMapping("/loggers/refresh")
    public ResponseEntity<Map<String, String>> refresh() {
        aclAuthorizer.richiedeScritturaConfigurazioneEManutenzione();
        return refreshLivelli();
    }
}
