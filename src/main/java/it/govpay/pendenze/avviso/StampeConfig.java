package it.govpay.pendenze.avviso;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Un solo bean, deliberatamente: il client generato dall'OpenAPI Generator per
 * govpay-stampe.yaml ({@code ApiClient}/{@code PaymentNoticeApi}) non e' cablato qui perche'
 * {@link StampeClient} lo bypassa (streaming diretto via {@link StampeRawClient}, vedi
 * Javadoc di classe) — solo i model generati (package {@code it.govpay.stampe.client.model})
 * servono davvero.
 */
@Configuration
public class StampeConfig {

    @Bean
    public RestTemplate stampeRestTemplate(RestTemplateBuilder builder,
            @Value("${app.stampe.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.stampe.read-timeout-ms:30000}") int readTimeoutMs) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
