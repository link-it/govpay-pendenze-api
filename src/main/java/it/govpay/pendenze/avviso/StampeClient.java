package it.govpay.pendenze.avviso;

import java.io.OutputStream;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.ObjectMapper;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.govpay.stampe.client.model.PaymentNotice;

/**
 * Facade verso il microservizio {@code govpay-stampe}: verifica che {@code app.stampe.base-url}
 * sia configurato ({@link StampeNotConfiguredException} se assente), fa <b>vero streaming</b>
 * della response PDF verso l'{@link OutputStream} fornito (nessun buffer in memoria), e
 * protegge la chiamata con circuit breaker (istanza {@code stampe}, {@link StampeRawClient}) —
 * senza retry, vedi Javadoc li'.
 *
 * <p>Bypassa il client generato dall'OpenAPI Generator (che ritorna {@code byte[]}
 * bufferizzato) e usa direttamente {@link StampeRawClient} per fare copy-through input →
 * output.
 */
@Service
public class StampeClient {

    private static final Logger log = LoggerFactory.getLogger(StampeClient.class);

    /** Path del microservizio sul base-url (allineato a govpay-stampe.yaml). */
    private static final String PAYMENT_NOTICE_PATH = "/standard";

    private final StampeRawClient rawClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public StampeClient(StampeRawClient rawClient, ObjectMapper objectMapper,
            @Value("${app.stampe.base-url:}") String baseUrl) {
        this.rawClient = rawClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public void streamPaymentNotice(PaymentNotice payload, OutputStream output) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new StampeNotConfiguredException();
        }
        URI url = URI.create(baseUrl + PAYMENT_NOTICE_PATH);
        try {
            rawClient.execute(url,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().setAccept(List.of(MediaType.APPLICATION_PDF));
                        objectMapper.writeValue(request.getBody(), payload);
                    },
                    response -> {
                        StreamUtils.copy(response.getBody(), output);
                        return null;
                    });
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker aperto sul client govpay-stampe: {}", e.getMessage());
            throw new StampeUnavailableException(
                    "Microservizio govpay-stampe momentaneamente non disponibile (circuit open).", e);
        } catch (RestClientException e) {
            log.warn("Chiamata al microservizio govpay-stampe fallita: {}", e.getMessage());
            throw new StampeUnavailableException("Chiamata al microservizio govpay-stampe fallita.", e);
        }
    }
}
