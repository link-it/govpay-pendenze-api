package it.govpay.pendenze.metrics;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import it.govpay.common.metrics.ExternalCallMetricsRecorder;
import it.govpay.common.metrics.GovpayMetricsAutoConfiguration;

/**
 * Garantisce che {@link ExternalCallMetricsRecorder} esista sempre come bean,
 * anche quando {@code GovpayMetricsAutoConfiguration} (govpay-common) non si
 * attiva (metriche disattivate): il client GDE lo dichiara come dipendenza
 * obbligatoria nel costruttore.
 * <p>
 * {@code @AutoConfigureAfter(GovpayMetricsAutoConfiguration.class)} e'
 * load-bearing: senza, {@code @ConditionalOnMissingBean} verrebbe valutato
 * prima che l'autoconfigurazione di common abbia (eventualmente) registrato
 * il bean reale, creando un doppio bean quando le metriche sono attive
 * (ambiguita' all'iniezione). Una {@code @Configuration} qualunque non
 * basterebbe: l'ordinamento rispetto ad un'altra autoconfigurazione vale
 * solo tra classi che partecipano entrambe al meccanismo di
 * auto-configurazione (qui via {@code AutoConfiguration.imports}).
 */
@AutoConfiguration
@AutoConfigureAfter(GovpayMetricsAutoConfiguration.class)
public class FallbackMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ExternalCallMetricsRecorder.class)
    public ExternalCallMetricsRecorder noopExternalCallMetricsRecorder() {
        return new NoopExternalCallMetricsRecorder();
    }
}
