package it.govpay.pendenze.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.gov.digitpa.schemas._2011.pagamenti.RPT;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioLogoRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.api.model.RTAnagrafica;
import it.govpay.pendenze.api.model.RTDatiSingoloPagamento;
import it.govpay.pendenze.api.model.RTIdentificativoUnivoco;
import it.govpay.pendenze.api.model.RTSubject;
import it.govpay.pendenze.api.model.RTSubjectUniqueIdentifier;
import it.govpay.pendenze.api.model.RTTransfer;
import it.govpay.pendenze.api.model.RTTransferV2;
import it.govpay.pendenze.api.model.RicevutaCtReceipt;
import it.govpay.pendenze.api.model.RicevutaCtReceiptV2;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematica;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDatiPagamento;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDatiPagamento.CodiceEsitoPagamentoEnum;
import it.govpay.pendenze.api.model.TipoIdentificativoUnivocoRT;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.api.model.TipoSoggetto;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.entity.RptXmlRptEntity;
import it.govpay.pendenze.repository.RptXmlRptRepository;
import it.govpay.stampe.client.model.Receipt;
import it.govpay.stampe.client.model.ReceiptItemStatus;
import it.govpay.stampe.client.model.ReceiptStatus;
import it.govpay.stampe.client.model.ReceiptVersion;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

class RicevutaPdfPayloadMapperTest {

    private static final long ID_DOMINIO = 1L;

    private final DominioRepository dominioRepository = mock(DominioRepository.class);
    private final DominioLogoRepository dominioLogoRepository = mock(DominioLogoRepository.class);
    private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
    private final RptXmlRptRepository rptXmlRptRepository = mock(RptXmlRptRepository.class);
    private final RicevutaPdfPayloadMapper mapper = new RicevutaPdfPayloadMapper(dominioRepository,
            dominioLogoRepository, unitaOperativaRepository, rptXmlRptRepository, "data:image/png;base64,AAAA");

    private DominioEntity dominio() {
        return dominio("12345678901", "Comune di Test");
    }

    private DominioEntity dominio(String codDominio, String ragioneSociale) {
        DominioEntity dominio = DominioEntity.builder()
                .id(ID_DOMINIO)
                .codDominio(codDominio)
                .ragioneSociale(ragioneSociale)
                .auxDigit(0)
                .build();
        when(dominioRepository.findById(ID_DOMINIO)).thenReturn(Optional.of(dominio));
        when(dominioLogoRepository.findLogoByCodDominio(codDominio)).thenReturn(Optional.empty());
        when(unitaOperativaRepository.findByCodUoAndDominioId("EC", ID_DOMINIO)).thenReturn(Optional.empty());
        return dominio;
    }

    private static Pendenza pendenza() {
        PosizioneDebitoria posizioneDebitoria = new PosizioneDebitoria();
        posizioneDebitoria.setDescrizione("Diritti di segreteria");
        OpzionePagamento opzionePagamento = new OpzionePagamento();
        opzionePagamento.setPosizioneDebitoria(posizioneDebitoria);
        Pendenza pendenza = new Pendenza();
        pendenza.setOpzionePagamento(opzionePagamento);
        pendenza.setIdDominio(ID_DOMINIO);
        return pendenza;
    }

    private Rpt rpt(Long id, OffsetDateTime dataMsgRichiesta) {
        Rpt rpt = new Rpt();
        rpt.setId(id);
        rpt.setIuv("IUV" + id);
        rpt.setIur("CCP" + id);
        rpt.setDataMsgRichiesta(dataMsgRichiesta);
        when(rptXmlRptRepository.findById(id)).thenReturn(Optional.empty());
        return rpt;
    }

    // ---------- SANP_230 (ctRicevutaTelematica) ----------

    private static RTAnagrafica anagrafica(String tipo, String codice, String denominazione, String indirizzo,
            String civico, String cap, String localita, String provincia) {
        RTIdentificativoUnivoco id = new RTIdentificativoUnivoco();
        id.setTipoIdentificativoUnivoco(TipoIdentificativoUnivocoRT.fromValue(tipo));
        id.setCodiceIdentificativoUnivoco(codice);
        RTAnagrafica a = new RTAnagrafica();
        a.setIdentificativoUnivoco(id);
        a.setDenominazione(denominazione);
        a.setIndirizzo(indirizzo);
        a.setCivico(civico);
        a.setCap(cap);
        a.setLocalita(localita);
        a.setProvincia(provincia);
        return a;
    }

    private static RicevutaCtRicevutaTelematica ricevutaSanp230() {
        RicevutaCtRicevutaTelematica r = new RicevutaCtRicevutaTelematica();
        r.setTipo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
        r.setDataOraMessaggioRicevuta(OffsetDateTime.of(2026, 6, 10, 9, 32, 0, 0, ZoneOffset.ofHours(2)));
        r.setIstitutoAttestante(
                anagrafica("A", "01234567890", "Banca Attestante SpA", null, null, null, null, null));
        r.setSoggettoPagatore(anagrafica("F", "RSSMRA80A01H501U", "Mario Rossi (storico RT)", "Via Roma", "1",
                "00100", "Roma", "RM"));

        RicevutaCtRicevutaTelematicaDatiPagamento dp = new RicevutaCtRicevutaTelematicaDatiPagamento();
        dp.setCodiceEsitoPagamento(CodiceEsitoPagamentoEnum.NUMBER_2);
        dp.setImportoTotalePagato(new BigDecimal("16.00"));

        RTDatiSingoloPagamento eseguita = new RTDatiSingoloPagamento();
        eseguita.setIdentificativoUnivocoRiscossione("IUR900000004");
        eseguita.setCausaleVersamento("Diritti di segreteria");
        eseguita.setSingoloImportoPagato(new BigDecimal("16.00"));

        RTDatiSingoloPagamento nonEseguita = new RTDatiSingoloPagamento();
        nonEseguita.setIdentificativoUnivocoRiscossione("IUR900000005");
        nonEseguita.setCausaleVersamento("Sanzione non applicata");
        nonEseguita.setSingoloImportoPagato(BigDecimal.ZERO);

        dp.setDatiSingoloPagamento(List.of(eseguita, nonEseguita));
        r.setDatiPagamento(dp);
        return r;
    }

    @Test
    void sanp230LeggeDataOperazioneDallRptQuandoDisponibile() throws JAXBException {
        dominio();
        Rpt rpt = rpt(10L, OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        RptXmlRptEntity entity = new RptXmlRptEntity();
        entity.setId(10L);
        entity.setXmlRpt(marshalRpt(LocalDateTime.of(2026, 6, 9, 8, 15, 0)));
        when(rptXmlRptRepository.findById(10L)).thenReturn(Optional.of(entity));

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        // RPT: 2026-06-09T08:15 -> non mezzanotte, ore/minuti visibili. Diverso dalla
        // dataMsgRichiesta (2000-01-01): se il mapper ripiegasse comunque sulla colonna
        // anche con xml_rpt presente, questo assert lo scoprirebbe.
        assertThat(receipt.getOperationDate()).isEqualTo("09/06/2026 08:15");
        // RT: 2026-06-10T09:32 -> sempre solo data.
        assertThat(receipt.getApplicationDate()).isEqualTo("10/06/2026");
    }

    @Test
    void sanp230SenzaXmlRptRipiegaSuDataMsgRichiesta() {
        dominio();
        // mezzanotte esatta -> la data operazione deve mostrare solo la data
        Rpt rpt = rpt(11L, OffsetDateTime.of(2026, 6, 9, 0, 0, 0, 0, ZoneOffset.ofHours(2)));

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getOperationDate()).isEqualTo("09/06/2026");
    }

    @Test
    void sanp230UsaIlSoggettoPagatoreDellaRt() {
        dominio();
        Rpt rpt = rpt(12L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getPayer().getFiscalCode()).isEqualTo("RSSMRA80A01H501U");
        assertThat(receipt.getPayer().getFullName()).isEqualTo("Mario Rossi (storico RT)");
        assertThat(receipt.getPayer().getAddress()).isEqualTo("Via Roma, 1");
        assertThat(receipt.getPayer().getLocation()).isEqualTo("00100 Roma (RM)");
    }

    @Test
    void sanp230VociConIurVeroEStatoPerImporto() {
        dominio();
        Rpt rpt = rpt(13L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getItems()).hasSize(2);
        assertThat(receipt.getItems().get(0).getIur()).isEqualTo("IUR900000004");
        assertThat(receipt.getItems().get(0).getDescription()).isEqualTo("Diritti di segreteria");
        assertThat(receipt.getItems().get(0).getStatus()).isEqualTo(ReceiptItemStatus.EXECUTED);
        assertThat(receipt.getItems().get(1).getIur()).isEqualTo("IUR900000005");
        assertThat(receipt.getItems().get(1).getStatus()).isEqualTo(ReceiptItemStatus.NOT_EXECUTED);
    }

    @Test
    void sanp230ImportoEsitoEVersioneComplessivi() {
        dominio();
        Rpt rpt = rpt(14L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getAmount()).isEqualTo(16.00);
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.PARTIALLY_EXECUTED);
        assertThat(receipt.getObjectVersion()).isEqualTo(ReceiptVersion.SANP_230);
        assertThat(receipt.getPsp()).isEqualTo("Banca Attestante SpA, 01234567890");
        assertThat(receipt.getPaymentSubject()).isEqualTo("Diritti di segreteria");
    }

    // ---------- ctReceipt (SANP_240) ----------

    private static RTSubject subject(String codiceFiscale, String nome, String via, String civico, String cap,
            String citta, String provincia) {
        RTSubjectUniqueIdentifier id = new RTSubjectUniqueIdentifier();
        id.setEntityUniqueIdentifierType(TipoSoggetto.F);
        id.setEntityUniqueIdentifierValue(codiceFiscale);
        RTSubject s = new RTSubject();
        s.setUniqueIdentifier(id);
        s.setFullName(nome);
        s.setStreetName(via);
        s.setCivicNumber(civico);
        s.setPostalCode(cap);
        s.setCity(citta);
        s.setStateProvinceRegion(provincia);
        return s;
    }

    private static RicevutaCtReceipt ricevutaCtReceipt() {
        RicevutaCtReceipt r = new RicevutaCtReceipt();
        r.setTipo(TipoRicevuta.CT_RECEIPT);
        r.setReceiptId("RT900000003");
        r.setOutcome(RicevutaCtReceipt.OutcomeEnum.OK);
        r.setPaymentAmount(new BigDecimal("10.00"));
        r.setDebtor(subject("RSSMRA80A01H501U", "Mario Rossi", "Via Roma", "1", "00100", "Roma", "RM"));
        r.setPsPCompanyName("PSP di Prova SpA");
        r.setPspFiscalCode("99999999999");
        r.setPaymentDateTime(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2)));
        r.setApplicationDate(LocalDate.of(2026, 9, 1));
        RTTransfer t = new RTTransfer();
        t.setRemittanceInformation("TARI 2026 saldo");
        t.setTransferAmount(new BigDecimal("10.00"));
        r.setTransferList(List.of(t));
        return r;
    }

    @Test
    void ctReceiptDateVoceEPagatoreDalDebtor() {
        dominio();
        Rpt rpt = rpt(20L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaCtReceipt());

        assertThat(receipt.getOperationDate()).isEqualTo("01/09/2026 10:00");
        assertThat(receipt.getApplicationDate()).isEqualTo("01/09/2026");
        assertThat(receipt.getPayer().getFullName()).isEqualTo("Mario Rossi");
        assertThat(receipt.getPsp()).isEqualTo("PSP di Prova SpA, 99999999999");
        assertThat(receipt.getItems()).hasSize(1);
        assertThat(receipt.getItems().get(0).getIur()).isEqualTo("RT900000003");
        assertThat(receipt.getItems().get(0).getDescription()).isEqualTo("TARI 2026 saldo");
        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.EXECUTED);
        assertThat(receipt.getObjectVersion()).isEqualTo(ReceiptVersion.SANP_240);
    }

    @Test
    void ctReceiptSenzaDateNellaRtMostraNd() {
        dominio();
        Rpt rpt = rpt(21L, OffsetDateTime.now());
        RicevutaCtReceipt r = ricevutaCtReceipt();
        r.setPaymentDateTime(null);
        r.setApplicationDate(null);

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, r);

        assertThat(receipt.getOperationDate()).isEqualTo("N/D");
        assertThat(receipt.getApplicationDate()).isEqualTo("N/D");
    }

    // ---------- ctReceiptV2 ----------

    @Test
    void ctReceiptV2OutcomeKoMappaNotExecutedAnchePerVoceZero() {
        dominio();
        Rpt rpt = rpt(30L, OffsetDateTime.now());
        RicevutaCtReceiptV2 r = new RicevutaCtReceiptV2();
        r.setTipo(TipoRicevuta.CT_RECEIPT_V2);
        r.setReceiptId("RT900000001");
        r.setOutcome(RicevutaCtReceiptV2.OutcomeEnum.KO);
        r.setPaymentAmount(BigDecimal.ZERO);
        r.setDebtor(subject("RSSMRA80A01H501U", "Mario Rossi", "Via Roma 1", null, "00100", "Roma", "RM"));
        RTTransferV2 t = new RTTransferV2();
        t.setRemittanceInformation("TARI 2026");
        t.setTransferAmount(BigDecimal.ZERO);
        r.setTransferList(List.of(t));

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, r);

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.NOT_EXECUTED);
        assertThat(receipt.getItems().get(0).getStatus()).isEqualTo(ReceiptItemStatus.NOT_EXECUTED);
        assertThat(receipt.getItems().get(0).getIur()).isEqualTo("RT900000001");
        assertThat(receipt.getObjectVersion()).isEqualTo(ReceiptVersion.SANP_240_V2);
    }

    // ---------- Indirizzo ente (UO EC) ----------

    @Test
    void indirizzoEnteVuotoSeUoEcAssente() {
        dominio();
        Rpt rpt = rpt(40L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getOrganization().getAddress()).isEmpty();
        assertThat(receipt.getOrganization().getLocation()).isEmpty();
    }

    @Test
    void indirizzoEnteDallUnitaOperativaEcQuandoPresente() {
        dominio();
        UnitaOperativaEntity uo = new UnitaOperativaEntity();
        uo.setUoIndirizzo("Piazza Duomo");
        uo.setUoCivico("1");
        uo.setUoCap("20100");
        uo.setUoLocalita("Milano");
        uo.setUoProvincia("MI");
        when(unitaOperativaRepository.findByCodUoAndDominioId("EC", ID_DOMINIO)).thenReturn(Optional.of(uo));
        Rpt rpt = rpt(41L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getOrganization().getAddress()).isEqualTo("Piazza Duomo, 1");
        assertThat(receipt.getOrganization().getLocation()).isEqualTo("20100 Milano (MI)");
    }

    // ---------- creditor_logo / pagopa_logo ----------

    @Test
    void creditorLogoVuotoSeNonCaricato() {
        dominio();
        Rpt rpt = rpt(50L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getCreditorLogo()).isEmpty();
    }

    @Test
    void creditorLogoRiconosceContentTypePngDaiMagicBytes() {
        dominio();
        byte[] png = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
        String base64 = Base64.getEncoder().encodeToString(png);
        when(dominioLogoRepository.findLogoByCodDominio("12345678901"))
                .thenReturn(Optional.of(base64.getBytes(StandardCharsets.UTF_8)));
        Rpt rpt = rpt(51L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getCreditorLogo()).isEqualTo("data:image/png;base64," + base64);
    }

    @Test
    void creditorLogoRiconosceContentTypeJpegDaiMagicBytes() {
        dominio();
        byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 };
        String base64 = Base64.getEncoder().encodeToString(jpeg);
        when(dominioLogoRepository.findLogoByCodDominio("12345678901"))
                .thenReturn(Optional.of(base64.getBytes(StandardCharsets.UTF_8)));
        Rpt rpt = rpt(52L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getCreditorLogo()).isEqualTo("data:image/jpeg;base64," + base64);
    }

    @Test
    void pagopaLogoSempreDallaPropertyDiConfigurazione() {
        dominio();
        Rpt rpt = rpt(53L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getPagopaLogo()).isEqualTo("data:image/png;base64,AAAA");
    }

    // ---------- troncamento ----------

    @Test
    void fiscalCodeEnteTroncatoA16Caratteri() {
        dominio("1".repeat(35), "Comune di Test");
        Rpt rpt = rpt(60L, OffsetDateTime.now());

        Receipt receipt = mapper.toReceipt(pendenza(), rpt, ricevutaSanp230());

        assertThat(receipt.getOrganization().getFiscalCode()).hasSize(16);
    }

    // ---------- helper: XML dell'RPT originale (JAXB, nessun file statico) ----------

    private static byte[] marshalRpt(LocalDateTime dataOraMessaggioRichiesta) throws JAXBException {
        RPT rpTelematico = new RPT();
        rpTelematico.setDataOraMessaggioRichiesta(dataOraMessaggioRichiesta);
        JAXBContext context = JAXBContext.newInstance(RPT.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        context.createMarshaller().marshal(rpTelematico, baos);
        return baos.toByteArray();
    }
}
