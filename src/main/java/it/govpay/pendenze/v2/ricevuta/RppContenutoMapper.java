package it.govpay.pendenze.v2.ricevuta;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.ricevuta.RicevutaMapper;
import it.govpay.pendenze.ricevuta.RicevutaNonLeggibileException;
import it.govpay.pendenze.ricevuta.pagopa.JaxbUtils;
import it.govpay.pendenze.ricevuta.pagopa.RicostruttoreRichiestaPagamento;
import it.govpay.pendenze.service.RicevutaRendicontazioneService;
import jakarta.xml.bind.JAXBException;

/**
 * Contenuto dei campi {@code rpt}/{@code rt} di {@code Rpp} ({@code GET
 * /pendenze/{idA2A}/{idPendenza}} dello YAML v2) — entrambi {@code type: object} generico
 * nello schema (verificato: nessun {@code $ref}, solo un {@code example} descrittivo), lo
 * stesso approccio del legacy ({@code RptConverter}/{@code ConverterUtils}: JSON opaco,
 * ottenuto serializzando il bean JAXB con le sue stesse annotazioni, non un mapping
 * campo-per-campo).
 *
 * <p><b>Conversione verso il vecchio formato V1</b> ({@code RPT}/{@code RT}, lo stesso di
 * {@code ctRichiestaPagamentoTelematico}/{@code ctRicevutaTelematica}): governata da
 * {@link #conversioneV1Abilitata} ({@code govpay.pendenze.v2.conversione-pagopa-v1.enable}),
 * <b>spento di default</b> — stesso comportamento del legacy
 * ({@code it.govpay.retrocompatibilitaMessaggiPagoPA.v1.enable}, confermato spento di
 * default in {@code GovpayConfig}): a conversione disattivata, {@code ctReceipt}/
 * {@code ctReceiptV2} vengono restituiti nel loro formato nativo, non riformattati. Per
 * SANP_230 non si applica (e' gia' quel formato, nessuna alternativa nativa da preservare):
 * {@link JaxbUtils#toRPT}/{@link JaxbUtils#toRT} restano sempre passthrough.</p>
 *
 * <p><b>Il formato della RPT si determina separatamente da quello della RT</b>: per le tre
 * versioni "miste" del legacy ({@code RPTSANP230_RTV2}/{@code RPTV1_RTV2}/{@code RPTV2_RTV1})
 * richiesta e ricevuta sono in formati pagoPA diversi — {@link #mapVersioneATipoRpt} replica
 * {@code MessaggiPagoPARptUtils.getMessaggioRPT}, {@link RicevutaMapper#mapVersioneATipo}
 * (usato solo per {@code rt}) replica {@code MessaggiPagoPARtUtils.getMessaggioRT}. Usare
 * l'uno al posto dell'altro fa leggere l'XML sbagliato (es. una RPT SANP_230 letta come
 * {@code ctReceiptV2} → {@code data} nullo).</p>
 */
@Component
public class RppContenutoMapper {

    private final RicevutaRendicontazioneService ricevutaRendicontazioneService;
    private final RichiestaPagamentoV1Mapper richiestaPagamentoV1Mapper;
    private final RicevutaJsonConverter jsonConverter;
    private final boolean conversioneV1Abilitata;
    private final Integer numeroGiorniValiditaPendenzaSenzaData;

    public RppContenutoMapper(RicevutaRendicontazioneService ricevutaRendicontazioneService,
            RichiestaPagamentoV1Mapper richiestaPagamentoV1Mapper, RicevutaJsonConverter jsonConverter,
            @Value("${govpay.pendenze.v2.conversione-pagopa-v1.enable:false}") boolean conversioneV1Abilitata,
            @Value("${govpay.pendenze.v2.giorni-validita-pendenza-senza-data:#{null}}")
            Integer numeroGiorniValiditaPendenzaSenzaData) {
        this.ricevutaRendicontazioneService = ricevutaRendicontazioneService;
        this.richiestaPagamentoV1Mapper = richiestaPagamentoV1Mapper;
        this.jsonConverter = jsonConverter;
        this.conversioneV1Abilitata = conversioneV1Abilitata;
        this.numeroGiorniValiditaPendenzaSenzaData = numeroGiorniValiditaPendenzaSenzaData;
    }

    /**
     * @throws RicevutaNonLeggibileException se l'XML (reale o ricostruito) non e' interpretabile
     */
    public Object toRpt(Rpt rpt, Pendenza pendenza) {
        TipoRicevuta tipoRpt = mapVersioneATipoRpt(rpt.getVersione());
        byte[] xmlRpt = ricevutaRendicontazioneService.trovaXmlRichiestaPagamento(rpt.getId()).orElse(null);
        try {
            Object contenutoRpt = switch (tipoRpt) {
                case CT_RICEVUTA_TELEMATICA ->
                    // Gia' nel formato V1: nessuna conversione, richiede comunque xml_rpt (per
                    // questo formato e' sempre presente, mai ricostruito — vedi Javadoc di Rpt).
                    JaxbUtils.toRPT(xmlRpt);
                case CT_RECEIPT -> {
                    var data = xmlRpt != null
                            ? JaxbUtils.toPaGetPaymentResRPT(xmlRpt).getData()
                            : RicostruttoreRichiestaPagamento.ricostruisci(JaxbUtils.toPaSendRTReqRT(rpt.getXmlRt()),
                                    pendenza, numeroGiorniValiditaPendenzaSenzaData);
                    yield conversioneV1Abilitata ? richiestaPagamentoV1Mapper.toRpt(data, rpt, pendenza) : data;
                }
                case CT_RECEIPT_V2 -> {
                    var data = xmlRpt != null
                            ? JaxbUtils.toPaGetPaymentV2ResponseRPT(xmlRpt).getData()
                            : RicostruttoreRichiestaPagamento.ricostruisci(JaxbUtils.toPaSendRTV2RequestRT(rpt.getXmlRt()),
                                    pendenza, numeroGiorniValiditaPendenzaSenzaData);
                    yield conversioneV1Abilitata ? richiestaPagamentoV1Mapper.toRpt(data, rpt, pendenza) : data;
                }
            };
            return jsonConverter.toAlberoGenerico(contenutoRpt);
        } catch (JAXBException e) {
            throw new RicevutaNonLeggibileException("La richiesta di pagamento [iur:" + rpt.getIur()
                    + "] non e' interpretabile come XML " + tipoRpt.getValue() + " (versione legacy ["
                    + rpt.getVersione() + "]).", e);
        }
    }

    public Object toRt(Rpt rpt, Pendenza pendenza) {
        TipoRicevuta tipoRt = RicevutaMapper.mapVersioneATipo(rpt.getVersione());
        if (rpt.getXmlRt() == null) {
            throw new RicevutaNonLeggibileException(
                    "La ricevuta [iur:" + rpt.getIur() + "] non ha ancora una RT acquisita.", null);
        }
        try {
            Object contenutoRt = switch (tipoRt) {
                // Gia' nel formato V1: nessuna conversione.
                case CT_RICEVUTA_TELEMATICA -> JaxbUtils.toRT(rpt.getXmlRt());
                case CT_RECEIPT -> {
                    var receipt = JaxbUtils.toPaSendRTReqRT(rpt.getXmlRt()).getReceipt();
                    yield conversioneV1Abilitata ? richiestaPagamentoV1Mapper.toRt(receipt, rpt, pendenza) : receipt;
                }
                case CT_RECEIPT_V2 -> {
                    var receipt = JaxbUtils.toPaSendRTV2RequestRT(rpt.getXmlRt()).getReceipt();
                    yield conversioneV1Abilitata ? richiestaPagamentoV1Mapper.toRt(receipt, rpt, pendenza) : receipt;
                }
            };
            return jsonConverter.toAlberoGenerico(contenutoRt);
        } catch (JAXBException e) {
            throw new RicevutaNonLeggibileException("La ricevuta [iur:" + rpt.getIur()
                    + "] non e' interpretabile come XML " + tipoRt.getValue() + " (versione legacy ["
                    + rpt.getVersione() + "]).", e);
        }
    }

    /**
     * Formato della RPT (richiesta), separato da quello della RT (ricevuta, vedi
     * {@link RicevutaMapper#mapVersioneATipo}) — porting di
     * {@code MessaggiPagoPARptUtils.getMessaggioRPT}. Coincide con quello della RT per le tre
     * versioni "pure" ({@code SANP_230}/{@code SANP_240}/{@code SANP_321_V2}), diverge per le
     * tre versioni "miste": {@code RPTSANP230_RTV2} (RPT SANP_230, RT V2),
     * {@code RPTV1_RTV2} (RPT {@code ctReceipt}, RT {@code ctReceiptV2}),
     * {@code RPTV2_RTV1} (RPT {@code ctReceiptV2}, RT {@code ctReceipt}).
     *
     * @param versione {@code Rpt.versione}, {@code null} trattato come SANP_230 (stessa
     *                 convenzione di {@link RicevutaMapper#mapVersioneATipo})
     */
    public static TipoRicevuta mapVersioneATipoRpt(String versione) {
        if (versione == null) {
            return TipoRicevuta.CT_RICEVUTA_TELEMATICA;
        }
        return switch (versione) {
            case "SANP_240", "RPTV1_RTV2" -> TipoRicevuta.CT_RECEIPT;
            case "SANP_321_V2", "RPTV2_RTV1" -> TipoRicevuta.CT_RECEIPT_V2;
            default -> TipoRicevuta.CT_RICEVUTA_TELEMATICA;
        };
    }
}
