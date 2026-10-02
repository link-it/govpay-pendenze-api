package it.govpay.pendenze.avviso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import it.govpay.stampe.client.model.PaymentNotice;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifica lo streaming copy-through (nessun buffer in memoria), la mappatura degli errori e
 * che {@link StampeRawClient} non catturi eccezioni internamente (nessun {@code @Retry}: vedi
 * Javadoc li').
 */
class StampeClientTest {

    private static final String BASE_URL = "http://fake-stampe";

    @Test
    void streamPaymentNoticeCopiaLaResponseSullOutputStream() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        StampeClient client = new StampeClient(new StampeRawClient(restTemplate), JsonMapper.shared(), BASE_URL);
        server.expect(requestTo(BASE_URL + "/standard"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("%PDF-finto".getBytes(), MediaType.APPLICATION_PDF));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        client.streamPaymentNotice(new PaymentNotice(), output);

        assertThat(output.toByteArray()).isEqualTo("%PDF-finto".getBytes());
        server.verify();
    }

    @Test
    void streamPaymentNoticeSenzaBaseUrlLanciaStampeNotConfigured() {
        RestTemplate restTemplate = new RestTemplate();
        StampeClient client = new StampeClient(new StampeRawClient(restTemplate), JsonMapper.shared(), "");

        assertThatThrownBy(() -> client.streamPaymentNotice(new PaymentNotice(), new ByteArrayOutputStream()))
                .isInstanceOf(StampeNotConfiguredException.class);
    }

    @Test
    void streamPaymentNoticeErroreHttpDiventaStampeUnavailable() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        StampeClient client = new StampeClient(new StampeRawClient(restTemplate), JsonMapper.shared(), BASE_URL);
        server.expect(requestTo(BASE_URL + "/standard")).andRespond(withServerError());

        assertThatThrownBy(() -> client.streamPaymentNotice(new PaymentNotice(), new ByteArrayOutputStream()))
                .isInstanceOf(StampeUnavailableException.class);
    }

    @Test
    void streamPaymentNoticeCircuitoApertoDiventaStampeUnavailable() {
        StampeRawClient rawClient = mock(StampeRawClient.class);
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("stampe-test");
        doThrow(CallNotPermittedException.createCallNotPermittedException(circuitBreaker))
                .when(rawClient).execute(any(), any(), any());
        StampeClient client = new StampeClient(rawClient, JsonMapper.shared(), BASE_URL);

        assertThatThrownBy(() -> client.streamPaymentNotice(new PaymentNotice(), new ByteArrayOutputStream()))
                .isInstanceOf(StampeUnavailableException.class);
    }

    /** Nessun catch interno in {@link StampeRawClient}: l'eccezione attraversa intatta. */
    @Test
    void rawClientNonConverteLeEccezioniRestClient() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        StampeRawClient rawClient = new StampeRawClient(restTemplate);
        server.expect(requestTo(BASE_URL + "/standard")).andRespond(withServerError());

        assertThatThrownBy(() -> rawClient.execute(URI.create(BASE_URL + "/standard"),
                request -> { }, response -> null))
                .isInstanceOf(RestClientException.class)
                .isNotInstanceOf(StampeUnavailableException.class);
    }
}
