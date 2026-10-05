package it.govpay.pendenze.ricevuta;

import org.springframework.stereotype.Component;

import it.govpay.pendenze.api.model.Ricevuta;
import it.govpay.pendenze.api.model.RicevutaIndex;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.RicevutaElenco;
import it.govpay.pendenze.ricevuta.pagopa.JaxbUtils;
import jakarta.xml.bind.JAXBException;

/**
 * Mapping {@link RicevutaElenco} → {@link RicevutaIndex} per
 * {@code GET /pendenze/{idA2A}/{idPendenza}/ricevute}, e {@link Rpt} (XML grezzo) →
 * {@link Ricevuta} (uno dei tre formati, in base a {@link #mapVersioneATipo}) per
 * {@code GET /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}}.
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
     * Despecializza l'XML grezzo ({@code Rpt.xmlRt}) nel bean JAXB corrispondente al formato
     * indicato da {@code Rpt.versione} ({@link #mapVersioneATipo}), poi lo mappa sul DTO v3
     * tipizzato con discriminator. {@code ctReceipt}/{@code ctReceiptV2} arrivano incapsulati
     * nel rispettivo wrapper SOAP ({@code paSendRTReq}/{@code paSendRTV2Request}): si estrae
     * sempre {@code .getReceipt()}, scartando il wrapper — stesso pattern di
     * {@code govpay-console-api.RptRtJsonConverter}.
     *
     * @throws RicevutaNonLeggibileException se l'XML non e' interpretabile come il formato
     *                                        atteso, o e' assente — non dovrebbe mai scattare
     *                                        in pratica, vedi Javadoc di classe dell'eccezione
     */
    public Ricevuta toRicevuta(Rpt rpt) {
        TipoRicevuta tipo = mapVersioneATipo(rpt.getVersione());
        if (rpt.getXmlRt() == null) {
            // RptRepository.findByIdVersamentoAndIurAndDataMsgRicevutaIsNotNull filtra solo su
            // dataMsgRicevuta: non garantisce xmlRt non nullo. Nel flusso reale i due campi
            // arrivano insieme (vedi Javadoc di classe di Rpt), quindi non dovrebbe scattare —
            // ma senza questo controllo un'incoerenza del dato produrrebbe una
            // NullPointerException grezza invece di un errore leggibile.
            throw new RicevutaNonLeggibileException(
                    "La ricevuta [iur:" + rpt.getIur() + "] ha dataMsgRicevuta valorizzata ma xmlRt assente: "
                            + "incoerenza del dato.", null);
        }
        try {
            return switch (tipo) {
                case CT_RICEVUTA_TELEMATICA ->
                    RicevutaCtRicevutaTelematicaMapper.map(JaxbUtils.toRT(rpt.getXmlRt()));
                case CT_RECEIPT ->
                    RicevutaCtReceiptMapper.mapCtReceipt(JaxbUtils.toPaSendRTReqRT(rpt.getXmlRt()).getReceipt());
                case CT_RECEIPT_V2 ->
                    RicevutaCtReceiptMapper.mapCtReceiptV2(JaxbUtils.toPaSendRTV2RequestRT(rpt.getXmlRt()).getReceipt());
            };
        } catch (JAXBException e) {
            throw new RicevutaNonLeggibileException(
                    "La ricevuta [iur:" + rpt.getIur() + "] non e' interpretabile come XML " + tipo.getValue()
                            + " (versione legacy [" + rpt.getVersione() + "]).", e);
        }
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
