package it.govpay.pendenze.v2.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivocoPersFG;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoVersamento;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtEntityUniqueIdentifier;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtRichiestaMarcaDaBollo;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtSubject;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferListPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferListPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.StEntityUniqueIdentifierType;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;

class RichiestaPagamentoV1MapperTest {

    private static final Long ID_DOMINIO = 1L;

    private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
    private final DominioRepository dominioRepository = mock(DominioRepository.class);
    private final RichiestaPagamentoV1Mapper mapper =
            new RichiestaPagamentoV1Mapper(unitaOperativaRepository, dominioRepository);

    private static DominioEntity dominio() {
        DominioEntity dominio = new DominioEntity();
        dominio.setCodDominio("12345678901");
        dominio.setRagioneSociale("Comune di Prova");
        return dominio;
    }

    private static UnitaOperativaEntity unitaOperativaEc() {
        UnitaOperativaEntity uo = new UnitaOperativaEntity();
        uo.setCodUo("EC");
        uo.setUoIndirizzo("Piazza Comune 1");
        uo.setUoCap("00100");
        uo.setUoLocalita("Roma");
        uo.setUoProvincia("RM");
        uo.setUoNazione("IT");
        return uo;
    }

    private static UnitaOperativaEntity unitaOperativaUfficioTributi() {
        UnitaOperativaEntity uo = new UnitaOperativaEntity();
        uo.setId(99L);
        uo.setCodUo("UFF-TRIBUTI");
        uo.setUoCodiceIdentificativo("UFF-TRIBUTI");
        uo.setUoDenominazione("Ufficio Tributi");
        uo.setUoIndirizzo("Via Tributi 5");
        uo.setUoCap("00200");
        uo.setUoLocalita("Roma");
        uo.setUoProvincia("RM");
        uo.setUoNazione("IT");
        return uo;
    }

    private static CtSubject debitore() {
        CtSubject debitore = new CtSubject();
        CtEntityUniqueIdentifier uniqueIdentifier = new CtEntityUniqueIdentifier();
        uniqueIdentifier.setEntityUniqueIdentifierType(StEntityUniqueIdentifierType.F);
        uniqueIdentifier.setEntityUniqueIdentifierValue("RSSMRA80A01H501U");
        debitore.setUniqueIdentifier(uniqueIdentifier);
        debitore.setFullName("Mario Rossi");
        debitore.setStreetName("Via Roma 1");
        debitore.setCivicNumber("1");
        debitore.setPostalCode("00100");
        debitore.setCity("Roma");
        debitore.setStateProvinceRegion("RM");
        debitore.setCountry("IT");
        return debitore;
    }

    private static Rpt rpt() {
        Rpt rpt = new Rpt();
        rpt.setCodDominio("12345678901");
        rpt.setCodStazione("STAZIONE_FIXTURE");
        rpt.setCodMsgRichiesta("MSG-RICHIESTA-1");
        rpt.setDataMsgRichiesta(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2)));
        rpt.setIuv("IUV900000003");
        rpt.setTipoVersamento("BBT");
        return rpt;
    }

    private static Pendenza pendenzaConUnitaOperativa(Long idUnitaOperativa) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setIdUnitaOperativa(idUnitaOperativa);
        OpzionePagamento opzione = new OpzionePagamento();
        posizione.addOpzionePagamento(opzione);
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(ID_DOMINIO);
        opzione.addPendenza(pendenza);
        return pendenza;
    }

    @Test
    @DisplayName("toRpt (ctReceipt): intestazione da Rpt, soggetto pagatore dalla RT, ente beneficiario dalla UO EC")
    void toRptCtReceipt() {
        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio()));
        when(unitaOperativaRepository.findByCodUoAndDominioId(eq("EC"), eq(ID_DOMINIO)))
                .thenReturn(Optional.of(unitaOperativaEc()));

        CtTransferPA transfer = new CtTransferPA();
        transfer.setRemittanceInformation("TARI 2026 saldo");
        transfer.setIBAN("IT60X0542811101000000123456");
        transfer.setTransferAmount(new BigDecimal("10.00"));
        transfer.setTransferCategory("9/0101108TS/");
        CtTransferListPA transferList = new CtTransferListPA();
        transferList.getTransfers().add(transfer);

        // dueDate (scadenza) deliberatamente diversa da dataMsgRichiesta (1 settembre, vedi
        // rpt()): dataEsecuzionePagamento deve riportare quest'ultima, non la scadenza.
        CtPaymentPA data = new CtPaymentPA();
        data.setDebtor(debitore());
        data.setDueDate(LocalDate.of(2026, 10, 15));
        data.setPaymentAmount(new BigDecimal("10.00"));
        data.setTransferList(transferList);

        var ctRpt = mapper.toRpt(data, rpt(), pendenzaConUnitaOperativa(null));

        assertThat(ctRpt.getVersioneOggetto()).isEqualTo("6.2.0");
        assertThat(ctRpt.getDominio().getIdentificativoDominio()).isEqualTo("12345678901");
        assertThat(ctRpt.getDominio().getIdentificativoStazioneRichiedente()).isEqualTo("STAZIONE_FIXTURE");
        assertThat(ctRpt.getIdentificativoMessaggioRichiesta()).isEqualTo("MSG-RICHIESTA-1");

        assertThat(ctRpt.getSoggettoPagatore().getAnagraficaPagatore()).isEqualTo("Mario Rossi");
        assertThat(ctRpt.getSoggettoPagatore().getIdentificativoUnivocoPagatore().getCodiceIdentificativoUnivoco())
                .isEqualTo("RSSMRA80A01H501U");
        assertThat(ctRpt.getSoggettoPagatore().getIdentificativoUnivocoPagatore().getTipoIdentificativoUnivoco())
                .isEqualTo(StTipoIdentificativoUnivocoPersFG.F);

        assertThat(ctRpt.getEnteBeneficiario().getIdentificativoUnivocoBeneficiario().getCodiceIdentificativoUnivoco())
                .isEqualTo("12345678901");
        assertThat(ctRpt.getEnteBeneficiario().getDenominazioneBeneficiario()).isEqualTo("Comune di Prova");
        assertThat(ctRpt.getEnteBeneficiario().getIndirizzoBeneficiario()).isEqualTo("Piazza Comune 1");
        assertThat(ctRpt.getEnteBeneficiario().getCodiceUnitOperBeneficiario()).isNull();

        // dataMsgRichiesta, non dueDate — stesso comportamento del legacy
        // (MessaggiPagoPAUtils.toCtRichiestaPagamentoTelematico): "esecuzione" = invio della
        // richiesta, non scadenza.
        assertThat(ctRpt.getDatiVersamento().getDataEsecuzionePagamento()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(ctRpt.getDatiVersamento().getTipoVersamento()).isEqualTo(StTipoVersamento.BBT);
        assertThat(ctRpt.getDatiVersamento().getIdentificativoUnivocoVersamento()).isEqualTo("IUV900000003");
        assertThat(ctRpt.getDatiVersamento().getFirmaRicevuta()).isEqualTo("0");
        assertThat(ctRpt.getDatiVersamento().getDatiSingoloVersamentos()).hasSize(1);
        assertThat(ctRpt.getDatiVersamento().getDatiSingoloVersamentos().get(0).getIbanAccredito())
                .isEqualTo("IT60X0542811101000000123456");
    }

    @Test
    @DisplayName("toRpt (ctReceiptV2): l'unita' operativa specifica, se non EC, sovrascrive l'indirizzo ma non nome/codice del dominio")
    void toRptCtReceiptV2ConUnitaOperativaSpecifica() {
        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio()));
        when(unitaOperativaRepository.findByCodUoAndDominioId(eq("EC"), eq(ID_DOMINIO)))
                .thenReturn(Optional.of(unitaOperativaEc()));
        when(unitaOperativaRepository.findById(eq(99L))).thenReturn(Optional.of(unitaOperativaUfficioTributi()));

        CtTransferPAV2 transfer = new CtTransferPAV2();
        transfer.setRemittanceInformation("TARI 2026 saldo");
        transfer.setIBAN("IT60X0542811101000000123456");
        transfer.setTransferAmount(new BigDecimal("10.00"));
        CtTransferListPAV2 transferList = new CtTransferListPAV2();
        transferList.getTransfers().add(transfer);

        CtPaymentPAV2 data = new CtPaymentPAV2();
        data.setDebtor(debitore());
        data.setDueDate(LocalDate.of(2026, 10, 15));
        data.setPaymentAmount(new BigDecimal("10.00"));
        data.setTransferList(transferList);

        var ctRpt = mapper.toRpt(data, rpt(), pendenzaConUnitaOperativa(99L));

        assertThat(ctRpt.getEnteBeneficiario().getDenominazioneBeneficiario()).isEqualTo("Comune di Prova");
        assertThat(ctRpt.getEnteBeneficiario().getCodiceUnitOperBeneficiario()).isEqualTo("UFF-TRIBUTI");
        assertThat(ctRpt.getEnteBeneficiario().getDenomUnitOperBeneficiario()).isEqualTo("Ufficio Tributi");
        assertThat(ctRpt.getEnteBeneficiario().getIndirizzoBeneficiario()).isEqualTo("Via Tributi 5");
        assertThat(ctRpt.getEnteBeneficiario().getCapBeneficiario()).isEqualTo("00200");
    }

    @Test
    @DisplayName("toRpt (ctReceiptV2) con marca da bollo: hashDocumento in base64, tipoBollo/provinciaResidenza riportati")
    void toRptCtReceiptV2ConMarcaDaBollo() {
        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio()));
        when(unitaOperativaRepository.findByCodUoAndDominioId(eq("EC"), eq(ID_DOMINIO)))
                .thenReturn(Optional.of(unitaOperativaEc()));

        CtRichiestaMarcaDaBollo richiestaMarcaDaBollo = new CtRichiestaMarcaDaBollo();
        richiestaMarcaDaBollo.setHashDocumento(new byte[] { 1, 2, 3 });
        richiestaMarcaDaBollo.setTipoBollo("01");
        richiestaMarcaDaBollo.setProvinciaResidenza("RM");

        CtTransferPAV2 transfer = new CtTransferPAV2();
        transfer.setRemittanceInformation("Marca da bollo digitale");
        transfer.setTransferAmount(new BigDecimal("16.00"));
        transfer.setRichiestaMarcaDaBollo(richiestaMarcaDaBollo);
        CtTransferListPAV2 transferList = new CtTransferListPAV2();
        transferList.getTransfers().add(transfer);

        CtPaymentPAV2 data = new CtPaymentPAV2();
        data.setDebtor(debitore());
        data.setPaymentAmount(new BigDecimal("16.00"));
        data.setTransferList(transferList);

        var ctRpt = mapper.toRpt(data, rpt(), pendenzaConUnitaOperativa(null));

        var datiMarcaBollo = ctRpt.getDatiVersamento().getDatiSingoloVersamentos().get(0).getDatiMarcaBolloDigitale();
        assertThat(datiMarcaBollo).isNotNull();
        assertThat(datiMarcaBollo.getHashDocumento()).isEqualTo(java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }));
        assertThat(datiMarcaBollo.getTipoBollo()).isEqualTo("01");
        assertThat(datiMarcaBollo.getProvinciaResidenza()).isEqualTo("RM");
    }

    @Test
    @DisplayName("un tipoVersamento non riconosciuto (o assente) ricade su OTH, come il legacy")
    void tipoVersamentoNonRiconosciutoRicadeSuOth() {
        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio()));
        when(unitaOperativaRepository.findByCodUoAndDominioId(eq("EC"), eq(ID_DOMINIO)))
                .thenReturn(Optional.of(unitaOperativaEc()));

        Rpt rptConTipoIgnoto = rpt();
        rptConTipoIgnoto.setTipoVersamento("QUALCOSA_DI_IGNOTO");

        CtPaymentPA data = new CtPaymentPA();
        data.setDebtor(debitore());
        data.setTransferList(new CtTransferListPA());

        var ctRpt = mapper.toRpt(data, rptConTipoIgnoto, pendenzaConUnitaOperativa(null));

        assertThat(ctRpt.getDatiVersamento().getTipoVersamento()).isEqualTo(StTipoVersamento.OTH);
    }
}
