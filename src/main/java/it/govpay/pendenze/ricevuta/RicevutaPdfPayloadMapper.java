package it.govpay.pendenze.ricevuta;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import it.gov.digitpa.schemas._2011.pagamenti.RPT;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioLogoRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.api.model.RTAnagrafica;
import it.govpay.pendenze.api.model.RTDatiSingoloPagamento;
import it.govpay.pendenze.api.model.RTSubject;
import it.govpay.pendenze.api.model.RTTransfer;
import it.govpay.pendenze.api.model.RTTransferV2;
import it.govpay.pendenze.api.model.Ricevuta;
import it.govpay.pendenze.api.model.RicevutaCtReceipt;
import it.govpay.pendenze.api.model.RicevutaCtReceiptV2;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematica;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.RptXmlRptRepository;
import it.govpay.pendenze.ricevuta.pagopa.JaxbUtils;
import it.govpay.stampe.client.model.Payer;
import it.govpay.stampe.client.model.Receipt;
import it.govpay.stampe.client.model.ReceiptItem;
import it.govpay.stampe.client.model.ReceiptItemStatus;
import it.govpay.stampe.client.model.ReceiptOrganization;
import it.govpay.stampe.client.model.ReceiptStatus;
import it.govpay.stampe.client.model.ReceiptVersion;
import jakarta.xml.bind.JAXBException;

/**
 * Mapping {@code Pendenza}+{@code Rpt}+{@link Ricevuta} → {@link Receipt} per la generazione
 * PDF via microservizio {@code govpay-stampe}, per {@code GET
 * /pendenze/{idA2A}/{idPendenza}/ricevute/{iur}/stampa}.
 *
 * <p>A differenza di {@code AvvisoPdfPayloadMapper}, qui quasi tutto il contenuto viene dalla
 * ricevuta storica (RT), non dalla pendenza corrente: soggetto pagatore, voci, importo ed esito
 * sono quelli che pagoPA ha certificato al momento del pagamento (letti dal DTO {@link Ricevuta}
 * gia' costruito da {@link RicevutaMapper#toRicevuta}), non i dati attuali di
 * {@code Versamento}/{@code SingoliVersamenti} — stessa fedelta' del legacy
 * {@code RicevutaTelematica.java}, verificata esplicitamente contro quel codice.</p>
 *
 * <p><b>Indirizzo ente creditore</b>: letto dal vivo da {@link DominioEntity} +
 * {@link UnitaOperativaEntity} con {@code cod_uo='EC'} — mai da {@code enteBeneficiario} della
 * RT (che per {@code ctReceipt}/{@code ctReceiptV2} non esiste nemmeno nello schema pagoPA).
 * Stesso principio di {@code impostaAnagraficaEnteCreditore} nel legacy: una ricevuta ristampata
 * dopo una modifica dell'anagrafica del dominio mostra l'indirizzo aggiornato, non quello
 * storico.</p>
 *
 * <p><b>Data operazione per SANP_230</b>: l'unico campo per cui serve l'RPT originale, non la
 * RT — letto da {@link RptXmlRptRepository} quando disponibile, con fallback su
 * {@code Rpt.dataMsgRichiesta} (stessa colonna da cui il legacy popola il campo omonimo
 * dell'XML in uscita, quindi equivalente) per le transazioni acquisite in standin, dove
 * {@code xml_rpt} non e' mai stato ricostruito.</p>
 */
@Component
public class RicevutaPdfPayloadMapper {

    /** Limiti del contratto {@code govpay-stampe.yaml} su {@code Receipt}/{@code Payer}/{@code ReceiptOrganization}. */
    private static final int MAX_FISCAL_CODE = 16;
    private static final int MAX_NOME_INDIRIZZO = 70;
    private static final int MAX_PSP = 70;
    private static final int MAX_OGGETTO = 140;
    private static final int MAX_IDENTIFICATIVO = 35;

    private static final DateTimeFormatter DATE_DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_DDMMYYYY_HHMM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Stesso codice dell'unita' operativa con cui il legacy (costante {@code Dominio.EC}) identifica l'anagrafica dell'ente. */
    private static final String COD_UO_ENTE_CREDITORE = "EC";

    private final DominioRepository dominioRepository;
    private final DominioLogoRepository dominioLogoRepository;
    private final UnitaOperativaRepository unitaOperativaRepository;
    private final RptXmlRptRepository rptXmlRptRepository;
    private final String pagopaLogoBase64;

    public RicevutaPdfPayloadMapper(DominioRepository dominioRepository,
            DominioLogoRepository dominioLogoRepository, UnitaOperativaRepository unitaOperativaRepository,
            RptXmlRptRepository rptXmlRptRepository,
            @Value("${govpay.stampe.logo.pagopa:}") String pagopaLogoBase64) {
        this.dominioRepository = dominioRepository;
        this.dominioLogoRepository = dominioLogoRepository;
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.rptXmlRptRepository = rptXmlRptRepository;
        this.pagopaLogoBase64 = pagopaLogoBase64;
    }

    public Receipt toReceipt(Pendenza pendenza, Rpt rpt, Ricevuta ricevuta) {
        DominioEntity dominio = dominioRepository.findById(pendenza.getIdDominio())
                .orElseThrow(() -> new IllegalStateException(
                        "idDominio [" + pendenza.getIdDominio() + "] della pendenza non risolve a nessun dominio "
                                + "censito: incoerenza del dato, il vincolo di integrita' referenziale dovrebbe "
                                + "impedirlo"));

        DatiStoriciRicevuta dati = switch (ricevuta.getTipo()) {
            case CT_RICEVUTA_TELEMATICA -> datiDaCtRicevutaTelematica((RicevutaCtRicevutaTelematica) ricevuta, rpt);
            case CT_RECEIPT -> datiDaCtReceipt((RicevutaCtReceipt) ricevuta);
            case CT_RECEIPT_V2 -> datiDaCtReceiptV2((RicevutaCtReceiptV2) ricevuta);
        };

        Receipt receipt = new Receipt();
        receipt.setCreditorLogo(creditorLogoOf(dominio));
        receipt.setPagopaLogo(pagopaLogoBase64);
        receipt.setPaymentSubject(tronca(descrizioneDi(pendenza), MAX_OGGETTO));
        receipt.setOrganization(mapOrganization(dominio));
        receipt.setPayer(dati.payer());
        receipt.setPsp(tronca(dati.psp(), MAX_PSP));
        receipt.setAmount(dati.amount());
        receipt.setOperationDate(dati.operationDate());
        receipt.setApplicationDate(dati.applicationDate());
        receipt.setStatus(dati.status());
        receipt.setCreditorReferenceId(tronca(rpt.getIuv(), MAX_IDENTIFICATIVO));
        receipt.setReceiptId(tronca(rpt.getIur(), MAX_IDENTIFICATIVO));
        receipt.setObjectVersion(mapObjectVersion(ricevuta.getTipo()));
        receipt.setItems(dati.items());
        return receipt;
    }

    private static String descrizioneDi(Pendenza pendenza) {
        return pendenza.getOpzionePagamento().getPosizioneDebitoria().getDescrizione();
    }

    /** Magic bytes PNG (8 byte) — stesso formato riconosciuto da {@code LogoMimeDetector} in govpay-console-api. */
    private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    /** Magic bytes JPEG (SOI + primo marker). */
    private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

    /**
     * La colonna {@code domini.logo} memorizza il testo Base64 dell'immagine (non i byte
     * grezzi, vedi {@code govpay-console-api.DominioLogoCodec}): per costruire il data-URI
     * serve quindi (a) leggere i byte come stringa UTF-8 per ottenere il testo Base64, (b)
     * decodificarlo per riconoscere il content-type dai magic bytes, (c) riusare il testo
     * Base64 originale — mai ri-codificato — col prefisso corretto. Stesso identico principio
     * di {@code DominioLogoCodec.toDataUri}, qui senza la dipendenza da {@code eu.medsea.mimeutil}
     * (non la introduco in govpay-pendenze-api solo per questo): il riconoscimento e' limitato
     * ai due formati che quella libreria riconosce comunque (PNG/JPEG), con lo stesso fallback
     * a {@code image/png} se il contenuto non corrisponde a nessuno dei due.
     */
    private String creditorLogoOf(DominioEntity dominio) {
        byte[] logo = dominioLogoRepository.findLogoByCodDominio(dominio.getCodDominio()).orElse(null);
        if (logo == null || logo.length == 0) {
            return "";
        }
        String base64 = base64Di(logo);
        if (base64.isEmpty()) {
            return "";
        }
        byte[] immagine = Base64.getDecoder().decode(base64);
        return "data:" + contentTypeDi(immagine) + ";base64," + base64;
    }

    /** Spoglia un eventuale prefisso data-URI gia' presente in colonna (defensive, stessa logica di {@code DominioLogoCodec.base64Of}). */
    private static String base64Di(byte[] stored) {
        String testo = new String(stored, StandardCharsets.UTF_8).trim();
        int virgola = testo.indexOf(',');
        if (testo.startsWith("data:") && virgola >= 0) {
            return testo.substring(virgola + 1);
        }
        return testo;
    }

    private static String contentTypeDi(byte[] immagine) {
        if (iniziaCon(immagine, PNG_MAGIC)) {
            return "image/png";
        }
        if (iniziaCon(immagine, JPEG_MAGIC)) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private static boolean iniziaCon(byte[] dati, byte[] prefisso) {
        if (dati.length < prefisso.length) {
            return false;
        }
        for (int i = 0; i < prefisso.length; i++) {
            if (dati[i] != prefisso[i]) {
                return false;
            }
        }
        return true;
    }

    private ReceiptOrganization mapOrganization(DominioEntity dominio) {
        ReceiptOrganization o = new ReceiptOrganization();
        o.setFiscalCode(tronca(dominio.getCodDominio(), MAX_FISCAL_CODE));
        o.setBusinessName(tronca(dominio.getRagioneSociale(), MAX_NOME_INDIRIZZO));
        UnitaOperativaEntity enteCreditore = unitaOperativaRepository
                .findByCodUoAndDominioId(COD_UO_ENTE_CREDITORE, dominio.getId()).orElse(null);
        if (enteCreditore != null) {
            o.setAddress(tronca(buildAddressLine(enteCreditore.getUoIndirizzo(), enteCreditore.getUoCivico()),
                    MAX_NOME_INDIRIZZO));
            o.setLocation(tronca(buildLocationLine(enteCreditore.getUoCap(), enteCreditore.getUoLocalita(),
                    enteCreditore.getUoProvincia()), MAX_NOME_INDIRIZZO));
        } else {
            o.setAddress("");
            o.setLocation("");
        }
        return o;
    }

    /** Esito/importo/voci/pagatore/date gia' pronti per {@link Receipt}, specifici del formato RT. */
    private record DatiStoriciRicevuta(String operationDate, String applicationDate, Payer payer, String psp,
            Double amount, ReceiptStatus status, List<ReceiptItem> items) {
    }

    private DatiStoriciRicevuta datiDaCtRicevutaTelematica(RicevutaCtRicevutaTelematica ricevuta, Rpt rpt) {
        String operationDate = formatDataOperazione(dataOperazioneSanp230(rpt));
        String applicationDate = formatDataApplicativa(ricevuta.getDataOraMessaggioRicevuta());
        Payer payer = payerDaRTAnagrafica(ricevuta.getSoggettoPagatore());
        String psp = pspDaRTAnagrafica(ricevuta.getIstitutoAttestante());
        Double amount = ricevuta.getDatiPagamento().getImportoTotalePagato() != null
                ? ricevuta.getDatiPagamento().getImportoTotalePagato().doubleValue() : 0.0;
        ReceiptStatus status = mapStatusSanp230(ricevuta.getDatiPagamento().getCodiceEsitoPagamento());
        List<ReceiptItem> items = itemsDaSanp230(ricevuta.getDatiPagamento().getDatiSingoloPagamento());
        return new DatiStoriciRicevuta(operationDate, applicationDate, payer, psp, amount, status, items);
    }

    /**
     * Legge {@code dataOraMessaggioRichiesta} dall'RPT originale ({@code RptXmlRptRepository}).
     * Se {@code xml_rpt} non e' disponibile (transazioni in standin, mai ricostruite — stesso
     * caso di {@code govpay-console-api.RptRtJsonConverter}), ripiega su
     * {@code Rpt.dataMsgRichiesta}.
     */
    private OffsetDateTime dataOperazioneSanp230(Rpt rpt) {
        byte[] xmlRpt = rptXmlRptRepository.findById(rpt.getId()).map(e -> e.getXmlRpt()).orElse(null);
        if (xmlRpt != null) {
            try {
                RPT rpTelematico = JaxbUtils.toRPT(xmlRpt);
                return RTDateTimeUtils.toOffsetDateTimeConvenzionale(rpTelematico.getDataOraMessaggioRichiesta());
            } catch (JAXBException e) {
                throw new RicevutaNonLeggibileException(
                        "L'RPT originale della ricevuta [iur:" + rpt.getIur() + "] non e' interpretabile come XML.",
                        e);
            }
        }
        return rpt.getDataMsgRichiesta();
    }

    private DatiStoriciRicevuta datiDaCtReceipt(RicevutaCtReceipt ricevuta) {
        String operationDate = formatDataOperazione(ricevuta.getPaymentDateTime());
        String applicationDate = formatDataApplicativa(ricevuta.getApplicationDate());
        Payer payer = payerDaRTSubject(ricevuta.getDebtor());
        String psp = concatenaIstituto(ricevuta.getPsPCompanyName(), ricevuta.getPspFiscalCode());
        Double amount = ricevuta.getPaymentAmount() != null ? ricevuta.getPaymentAmount().doubleValue() : 0.0;
        ReceiptStatus status = switch (ricevuta.getOutcome()) {
            case OK -> ReceiptStatus.EXECUTED;
            case KO -> ReceiptStatus.NOT_EXECUTED;
        };
        List<ReceiptItem> items = itemsDaCtReceipt(ricevuta.getReceiptId(), ricevuta.getTransferList());
        return new DatiStoriciRicevuta(operationDate, applicationDate, payer, psp, amount, status, items);
    }

    private DatiStoriciRicevuta datiDaCtReceiptV2(RicevutaCtReceiptV2 ricevuta) {
        String operationDate = formatDataOperazione(ricevuta.getPaymentDateTime());
        String applicationDate = formatDataApplicativa(ricevuta.getApplicationDate());
        Payer payer = payerDaRTSubject(ricevuta.getDebtor());
        String psp = concatenaIstituto(ricevuta.getPsPCompanyName(), ricevuta.getPspFiscalCode());
        Double amount = ricevuta.getPaymentAmount() != null ? ricevuta.getPaymentAmount().doubleValue() : 0.0;
        ReceiptStatus status = switch (ricevuta.getOutcome()) {
            case OK -> ReceiptStatus.EXECUTED;
            case KO -> ReceiptStatus.NOT_EXECUTED;
        };
        List<ReceiptItem> items = itemsDaCtReceiptV2(ricevuta.getReceiptId(), ricevuta.getTransferList());
        return new DatiStoriciRicevuta(operationDate, applicationDate, payer, psp, amount, status, items);
    }

    private static ReceiptStatus mapStatusSanp230(
            it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDatiPagamento.CodiceEsitoPagamentoEnum esito) {
        if (esito == null) {
            return ReceiptStatus.NOT_EXECUTED;
        }
        return switch (esito) {
            case NUMBER_0 -> ReceiptStatus.EXECUTED;
            case NUMBER_1 -> ReceiptStatus.NOT_EXECUTED;
            case NUMBER_2 -> ReceiptStatus.PARTIALLY_EXECUTED;
            case NUMBER_3 -> ReceiptStatus.EXPIRED;
            case NUMBER_4 -> ReceiptStatus.PARTIALLY_EXPIRED;
        };
    }

    /** {@code istitutoAttestante}/{@code psPCompanyName}+{@code pspFiscalCode}: denominazione, codice. */
    private static String pspDaRTAnagrafica(RTAnagrafica istituto) {
        if (istituto == null) {
            return "";
        }
        String codice = istituto.getIdentificativoUnivoco() != null
                ? istituto.getIdentificativoUnivoco().getCodiceIdentificativoUnivoco() : null;
        return concatenaIstituto(istituto.getDenominazione(), codice);
    }

    private static String concatenaIstituto(String denominazione, String codice) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(denominazione)) {
            sb.append(denominazione);
        }
        if (StringUtils.hasText(codice)) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(codice);
        }
        return sb.toString();
    }

    private static Payer payerDaRTAnagrafica(RTAnagrafica soggetto) {
        Payer p = new Payer();
        if (soggetto == null) {
            p.setFiscalCode("");
            p.setFullName("");
            p.setAddress("");
            p.setLocation("");
            return p;
        }
        String cf = soggetto.getIdentificativoUnivoco() != null
                ? soggetto.getIdentificativoUnivoco().getCodiceIdentificativoUnivoco() : null;
        p.setFiscalCode(tronca(cf, MAX_FISCAL_CODE));
        p.setFullName(tronca(soggetto.getDenominazione(), MAX_NOME_INDIRIZZO));
        p.setAddress(tronca(buildAddressLine(soggetto.getIndirizzo(), soggetto.getCivico()), MAX_NOME_INDIRIZZO));
        p.setLocation(tronca(buildLocationLine(soggetto.getCap(), soggetto.getLocalita(), soggetto.getProvincia()),
                MAX_NOME_INDIRIZZO));
        return p;
    }

    /** {@code debtor}, non {@code payer}: il legacy stampa sempre il debitore come "soggetto pagatore". */
    private static Payer payerDaRTSubject(RTSubject soggetto) {
        Payer p = new Payer();
        if (soggetto == null) {
            p.setFiscalCode("");
            p.setFullName("");
            p.setAddress("");
            p.setLocation("");
            return p;
        }
        String cf = soggetto.getUniqueIdentifier() != null
                ? soggetto.getUniqueIdentifier().getEntityUniqueIdentifierValue() : null;
        p.setFiscalCode(tronca(cf, MAX_FISCAL_CODE));
        p.setFullName(tronca(soggetto.getFullName(), MAX_NOME_INDIRIZZO));
        p.setAddress(tronca(buildAddressLine(soggetto.getStreetName(), soggetto.getCivicNumber()), MAX_NOME_INDIRIZZO));
        p.setLocation(tronca(buildLocationLine(soggetto.getPostalCode(), soggetto.getCity(),
                soggetto.getStateProvinceRegion()), MAX_NOME_INDIRIZZO));
        return p;
    }

    private static String buildAddressLine(String indirizzo, String civico) {
        if (!StringUtils.hasText(indirizzo)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(indirizzo);
        if (StringUtils.hasText(civico)) {
            sb.append(", ").append(civico);
        }
        return sb.toString();
    }

    private static String buildLocationLine(String cap, String localita, String provincia) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(cap)) {
            sb.append(cap).append(' ');
        }
        if (StringUtils.hasText(localita)) {
            sb.append(localita);
        }
        if (StringUtils.hasText(provincia)) {
            sb.append(" (").append(provincia).append(')');
        }
        return sb.toString().trim();
    }

    /**
     * Stato per singola voce: importo zero → {@code NOT_EXECUTED}, altrimenti
     * {@code EXECUTED} — stesso criterio del legacy, indipendente dall'esito complessivo.
     */
    private static ReceiptItemStatus statusDaImporto(double importo) {
        return importo == 0.0 ? ReceiptItemStatus.NOT_EXECUTED : ReceiptItemStatus.EXECUTED;
    }

    private static List<ReceiptItem> itemsDaSanp230(List<RTDatiSingoloPagamento> voci) {
        List<ReceiptItem> items = new ArrayList<>();
        if (voci == null) {
            return items;
        }
        for (RTDatiSingoloPagamento voce : voci) {
            BigDecimal importoBD = voce.getSingoloImportoPagato();
            double importo = importoBD != null ? importoBD.doubleValue() : 0.0;
            ReceiptItem item = new ReceiptItem();
            item.setDescription(
                    tronca(StringUtils.hasText(voce.getCausaleVersamento()) ? voce.getCausaleVersamento()
                            : "Voce di pagamento", MAX_OGGETTO));
            item.setIur(tronca(voce.getIdentificativoUnivocoRiscossione(), MAX_IDENTIFICATIVO));
            item.setAmount(importo);
            item.setStatus(statusDaImporto(importo));
            items.add(item);
        }
        return items;
    }

    /** {@code receiptId} come IUR per tutte le voci: stesso appiattimento del legacy per questo formato. */
    private static List<ReceiptItem> itemsDaCtReceipt(String receiptId, List<RTTransfer> voci) {
        List<ReceiptItem> items = new ArrayList<>();
        if (voci == null) {
            return items;
        }
        for (RTTransfer voce : voci) {
            BigDecimal importoBD = voce.getTransferAmount();
            double importo = importoBD != null ? importoBD.doubleValue() : 0.0;
            ReceiptItem item = new ReceiptItem();
            item.setDescription(
                    tronca(StringUtils.hasText(voce.getRemittanceInformation()) ? voce.getRemittanceInformation()
                            : "Voce di pagamento", MAX_OGGETTO));
            item.setIur(tronca(receiptId, MAX_IDENTIFICATIVO));
            item.setAmount(importo);
            item.setStatus(statusDaImporto(importo));
            items.add(item);
        }
        return items;
    }

    private static List<ReceiptItem> itemsDaCtReceiptV2(String receiptId, List<RTTransferV2> voci) {
        List<ReceiptItem> items = new ArrayList<>();
        if (voci == null) {
            return items;
        }
        for (RTTransferV2 voce : voci) {
            BigDecimal importoBD = voce.getTransferAmount();
            double importo = importoBD != null ? importoBD.doubleValue() : 0.0;
            ReceiptItem item = new ReceiptItem();
            item.setDescription(
                    tronca(StringUtils.hasText(voce.getRemittanceInformation()) ? voce.getRemittanceInformation()
                            : "Voce di pagamento", MAX_OGGETTO));
            item.setIur(tronca(receiptId, MAX_IDENTIFICATIVO));
            item.setAmount(importo);
            item.setStatus(statusDaImporto(importo));
            items.add(item);
        }
        return items;
    }

    private static ReceiptVersion mapObjectVersion(TipoRicevuta tipo) {
        return switch (tipo) {
            case CT_RICEVUTA_TELEMATICA -> ReceiptVersion.SANP_230;
            case CT_RECEIPT -> ReceiptVersion.SANP_240;
            case CT_RECEIPT_V2 -> ReceiptVersion.SANP_240_V2;
        };
    }

    /**
     * {@code dataOperazione}/{@code dataApplicativa}: {@code "dd/MM/yyyy HH:mm"}, salvo
     * mezzanotte esatta (solo data) per la prima, sempre e solo data per la seconda — stessa
     * convenzione di {@code RicevutaTelematica.setDataOperazione}/{@code setDataApplicativa}
     * nel legacy.
     */
    private static String formatDataOperazione(OffsetDateTime d) {
        if (d == null) {
            return "N/D";
        }
        boolean mezzanotteEsatta = d.getHour() == 0 && d.getMinute() == 0 && d.getSecond() == 0;
        return mezzanotteEsatta ? d.format(DATE_DDMMYYYY) : d.format(DATETIME_DDMMYYYY_HHMM);
    }

    private static String formatDataApplicativa(LocalDate d) {
        return d != null ? d.format(DATE_DDMMYYYY) : "N/D";
    }

    private static String formatDataApplicativa(OffsetDateTime d) {
        return formatDataApplicativa(d != null ? d.toLocalDate() : null);
    }

    /**
     * Le colonne sorgente sono spesso piu' larghe del limite del contratto stampe
     * ({@code @Size}): troncare un campo di sola resa grafica stampa una ricevuta con
     * l'intestazione accorciata, non troncarlo farebbe fallire l'intero PDF con un 400 — stesso
     * principio di {@code AvvisoPdfPayloadMapper.tronca}.
     */
    private static String tronca(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
