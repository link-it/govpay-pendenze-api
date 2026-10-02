package it.govpay.pendenze.avviso;

import java.net.URI;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

/**
 * Chiamata HTTP grezza verso govpay-stampe, isolata in un bean a se' perche'
 * {@code @CircuitBreaker} e' AOP (un self-invocation da {@link StampeClient} allo stesso
 * bean non passerebbe dal proxy).
 *
 * <p><b>Nessun {@code @Retry}</b>, deliberatamente: {@code responseExtractor} copia la
 * response in streaming diretto sull'{@link java.io.OutputStream} di destinazione fornito
 * dal chiamante (nessun buffer in memoria). Se il primo tentativo scrive parte del PDF prima
 * di fallire, un retry accoderebbe altri byte sullo stesso stream, corrompendo l'output.
 * Bufferizzare per rendere il retry sicuro e' scartato deliberatamente per non rinunciare
 * allo streaming diretto.
 */
@Service
public class StampeRawClient {

    private final RestTemplate restTemplate;

    public StampeRawClient(RestTemplate stampeRestTemplate) {
        this.restTemplate = stampeRestTemplate;
    }

    @CircuitBreaker(name = "stampe")
    public void execute(URI url, RequestCallback requestCallback, ResponseExtractor<Void> responseExtractor) {
        restTemplate.execute(url, HttpMethod.POST, requestCallback, responseExtractor);
    }
}
