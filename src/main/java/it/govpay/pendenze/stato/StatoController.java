package it.govpay.pendenze.stato;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.pendenze.api.model.Problem;
import it.govpay.pendenze.api.rest.StatoApi;

/**
 * Implementa {@link StatoApi}: {@code GET /status} richiesto da {@code RAC_REST_NAME_011}
 * delle Linee Guida AgID. Riusa lo stato aggregato dell'health actuator (lo stesso di
 * {@code /actuator/health} sulla porta management, database compreso) per esporlo anche sulla
 * porta delle API, senza autenticazione (vedi {@code permit-all-paths} in
 * {@code application.properties}). Se lo stato non e' {@code UP} risponde 503 con
 * {@code Retry-After} tramite {@link ServizioNonDisponibileException}.
 */
@RestController
@RequestMapping("${govpay.pendenze.api.v3.base-path:}")
public class StatoController implements StatoApi {

    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");

    private final HealthEndpoint healthEndpoint;

    public StatoController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Override
    public ResponseEntity<Problem> getStatus() {
        Status stato = healthEndpoint.health().getStatus();
        if (!Status.UP.equals(stato)) {
            throw new ServizioNonDisponibileException("Il servizio non e' operativo: stato " + stato.getCode() + ".");
        }
        Problem problem = new Problem(HttpStatus.OK.value()).title(HttpStatus.OK.getReasonPhrase());
        return ResponseEntity.ok().contentType(PROBLEM_JSON).body(problem);
    }
}
