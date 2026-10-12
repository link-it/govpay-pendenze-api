package it.govpay.pendenze.avviso;

import java.math.BigDecimal;
import java.time.LocalDate;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.utils.IuvUtils;
import it.govpay.pendenze.entity.Pendenza;

/**
 * Qrcode e data di scadenza dell'avviso, riusati da {@link AvvisoPdfPayloadMapper} per il PDF
 * (unico content-type prodotto da {@code GET /pendenze/{idA2A}/{idPendenza}/stampa}).
 */
final class AvvisoMapper {

    private AvvisoMapper() {
    }

    /**
     * Se assente sulla pendenza, si usa la scadenza dell'opzione di pagamento (semantica dello
     * YAML v3).
     */
    static LocalDate resolveDataScadenza(Pendenza pendenza) {
        if (pendenza.getDataScadenzaAvviso() != null) {
            return pendenza.getDataScadenzaAvviso().toLocalDate();
        }
        return pendenza.getOpzionePagamento().getDataScadenza();
    }

    static String buildQrcode(Pendenza pendenza, DominioEntity dominio) {
        return IuvUtils.buildQrCode002(dominio.getCodDominio(), dominio.getAuxDigit(), applicationCodeDi(dominio),
                pendenza.getIuv(), BigDecimal.valueOf(pendenza.getImporto()), pendenza.getNumeroAvviso());
    }

    private static Integer applicationCodeDi(DominioEntity dominio) {
        StazioneEntity stazione = dominio.getStazione();
        return stazione != null ? stazione.getApplicationCode() : null;
    }
}
