package it.govpay.pendenze.ricevuta;

import org.springframework.stereotype.Component;

import it.govpay.pendenze.api.model.RicevutaIndex;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.repository.RicevutaElenco;

/**
 * Mapping {@link RicevutaElenco} → {@link RicevutaIndex} per
 * {@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}.
 */
@Component
public class RicevutaMapper {

    public RicevutaIndex toRicevutaIndexDto(RicevutaElenco ricevuta) {
        RicevutaIndex dto = new RicevutaIndex();
        dto.setIur(ricevuta.getIur());
        dto.setTipo(mapVersioneATipo(ricevuta.getVersione()));
        dto.setData(ricevuta.getDataMsgRicevuta());
        return dto;
    }

    /**
     * Mappa il valore legacy grezzo di {@code Rpt.versione} (enum {@code VersioneRPT} del
     * monolite, persistito come stringa — mai un enum qui, vedi Javadoc di
     * {@link RicevutaElenco#getVersione()}) sul formato XML dichiarato dallo YAML v3
     * ({@link TipoRicevuta}). Non e' un'ipotesi: replica esattamente il dispatch reale del
     * legacy, {@code MessaggiPagoPARtUtils.getMessaggioRT} (jars/core), che per ciascun
     * valore di {@code VersioneRPT} istanzia uno dei tre tipi JAXB corrispondenti:
     *
     * <ul>
     * <li>{@code SANP_230} → {@code CtRicevutaTelematica} → {@link TipoRicevuta#CT_RICEVUTA_TELEMATICA}
     * (formato SANP storico);</li>
     * <li>{@code SANP_240}, {@code RPTV2_RTV1} → {@code CtReceipt} → {@link TipoRicevuta#CT_RECEIPT};</li>
     * <li>{@code SANP_321_V2}, {@code RPTV1_RTV2}, {@code RPTSANP230_RTV2} → {@code CtReceiptV2}
     * → {@link TipoRicevuta#CT_RECEIPT_V2}.</li>
     * </ul>
     *
     * <p>Un valore non riconosciuto (o {@code null}) ricade su
     * {@link TipoRicevuta#CT_RICEVUTA_TELEMATICA} — stesso fallback di
     * {@code VersioneRPT.toEnum(String)} nel legacy, che su un valore ignoto torna
     * silenziosamente a {@code SANP_230} invece di propagare un errore.</p>
     */
    static TipoRicevuta mapVersioneATipo(String versione) {
        if (versione == null) {
            return TipoRicevuta.CT_RICEVUTA_TELEMATICA;
        }
        return switch (versione) {
            case "SANP_240", "RPTV2_RTV1" -> TipoRicevuta.CT_RECEIPT;
            case "SANP_321_V2", "RPTV1_RTV2", "RPTSANP230_RTV2" -> TipoRicevuta.CT_RECEIPT_V2;
            default -> TipoRicevuta.CT_RICEVUTA_TELEMATICA;
        };
    }
}
