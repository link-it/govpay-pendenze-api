package it.govpay.pendenze.metrics;

import it.govpay.common.metrics.ExternalCallMetricsRecorder;

/**
 * Fallback usato quando {@code govpay.metrics.enabled=false} (o assente,
 * default della libreria): il client GDE dipende da
 * {@link ExternalCallMetricsRecorder} nel costruttore, ma quella classe non
 * e' registrata come bean se l'autoconfigurazione di govpay-common non si
 * attiva.
 * <p>
 * {@link #record} esegue solo la chiamata, senza toccare alcun
 * {@code MeterRegistry}: la chiamata esterna avviene esattamente come prima,
 * semplicemente non viene misurata.
 */
public class NoopExternalCallMetricsRecorder extends ExternalCallMetricsRecorder {

    public NoopExternalCallMetricsRecorder() {
        super(null, null);
    }

    // Il nome del metodo e' imposto dalla superclasse ExternalCallMetricsRecorder
    // (govpay-common): non e' rinominabile da qui senza rompere l'override.
    @SuppressWarnings("java:S6213")
    @Override
    public void record(String client, String operation, ExternalCall call) {
        call.run();
    }
}
