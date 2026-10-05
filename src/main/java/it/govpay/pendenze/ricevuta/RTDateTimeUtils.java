package it.govpay.pendenze.ricevuta;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * I campi {@code xs:dateTime} dei tracciati PagoPA (RT) non richiedono un offset, e quando lo
 * portano l'adapter JAXB condiviso
 * ({@code it.govpay.pendenze.ricevuta.pagopa.adapter.DateTimeAdapter}) lo tronca comunque
 * (non lo converte — stesso comportamento, verificato, di
 * {@code DataTypeAdapterCXF.parseLocalDateTime} nel legacy e in govpay-console-api, fissato
 * anche da un loro test unitario), restituendo un {@link LocalDateTime} senza fuso.
 *
 * <p>Convertire sul tipo {@link OffsetDateTime} richiesto dallo schema v3 richiede quindi di
 * ASSUMERE un fuso per le cifre troncate. Si usa {@code ZoneId.systemDefault()}, stessa scelta
 * (non solo stesso risultato) di legacy e govpay-console-api: {@code DateUtils.toJavaDate} nel
 * legacy fa esattamente {@code LocalDateTime.atZone(ZoneId.systemDefault())}. E' il deployment
 * (Dockerfile, {@code TZ=Europe/Rome}) a garantire che sia Europe/Rome, non il codice — stessa
 * responsabilita' di configurazione gia' in capo a tutti gli altri servizi GovPay, qui non
 * duplicata con un fuso diverso cablato a parte.</p>
 */
final class RTDateTimeUtils {

    private RTDateTimeUtils() {
    }

    static OffsetDateTime toOffsetDateTimeConvenzionale(LocalDateTime dataOraLocale) {
        return dataOraLocale == null ? null : dataOraLocale.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
