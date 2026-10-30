package it.govpay.pendenze.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Configurazione delle metriche applicative.
 *
 * <p>Registra {@link TimedAspect}: qualunque metodo di un bean Spring puo'
 * essere misurato (timer con conteggio e distribuzione della durata)
 * annotandolo con {@code @Timed("nome.metrica")}. La metrica risultante e'
 * esposta, insieme a quelle automatiche (HTTP server/client, JVM, datasource),
 * dall'endpoint di scrape Prometheus sulla porta management.
 *
 * <p>Il breakdown API interno/esterno e il recorder delle chiamate esterne
 * sono forniti da {@code GovpayMetricsAutoConfiguration} (govpay-common).
 * Resilience4j e' sul classpath solo per il circuit breaker verso
 * govpay-stampe (vedi {@code StampeRawClient}): qui non si registra alcuna
 * classificazione di outcome aggiuntiva.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
