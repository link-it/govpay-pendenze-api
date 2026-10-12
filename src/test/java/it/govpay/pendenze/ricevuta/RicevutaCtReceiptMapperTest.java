package it.govpay.pendenze.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import it.govpay.pendenze.api.model.RicevutaCtReceipt;
import it.govpay.pendenze.api.model.RicevutaCtReceiptV2;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.api.model.TipoSoggetto;
import it.govpay.pendenze.ricevuta.pagopa.JaxbUtils;

/**
 * Verifica il mapping bean JAXB → DTO v3 contro XML reali (copiati dalle fixture di
 * govpay-console-api, {@code rt-v2-ok.xml}/{@code rt-v2_2-ok.xml}): stesso contenuto gia'
 * validato in quel repository.
 */
class RicevutaCtReceiptMapperTest {

    private static byte[] fixture(String nome) throws IOException {
        try (InputStream in = RicevutaCtReceiptMapperTest.class.getResourceAsStream("/rt/" + nome)) {
            return in.readAllBytes();
        }
    }

    @Test
    void mapCtReceiptDaXmlReale() throws Exception {
        byte[] xml = fixture("rt-v2-ok.xml");
        RicevutaCtReceipt dto = RicevutaCtReceiptMapper
                .mapCtReceipt(JaxbUtils.toPaSendRTReqRT(xml).getReceipt());

        assertThat(dto.getTipo()).isEqualTo(TipoRicevuta.CT_RECEIPT);
        assertThat(dto.getReceiptId()).isEqualTo("RT900000003");
        assertThat(dto.getNoticeNumber()).isEqualTo("311000000000000003");
        assertThat(dto.getFiscalCode()).isEqualTo("12345678901");
        assertThat(dto.getOutcome()).isEqualTo(RicevutaCtReceipt.OutcomeEnum.OK);
        assertThat(dto.getCreditorReferenceId()).isEqualTo("IUV900000003");
        assertThat(dto.getPaymentAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(dto.getDescription()).isEqualTo("Pagamento TARI 2026");
        assertThat(dto.getCompanyName()).isEqualTo("Comune di Prova");
        assertThat(dto.getDebtor().getUniqueIdentifier().getEntityUniqueIdentifierType())
                .isEqualTo(TipoSoggetto.F);
        assertThat(dto.getDebtor().getUniqueIdentifier().getEntityUniqueIdentifierValue())
                .isEqualTo("RSSMRA80A01H501U");
        assertThat(dto.getDebtor().getFullName()).isEqualTo("Mario Rossi");
        assertThat(dto.getIdPSP()).isEqualTo("PSP_FIXTURE");
        assertThat(dto.getPsPCompanyName()).isEqualTo("PSP di Prova SpA");
        assertThat(dto.getIdChannel()).isEqualTo("CHANNEL_FIXTURE");
        assertThat(dto.getChannelDescription()).isEqualTo("App IO");
        assertThat(dto.getStandIn()).isFalse();
        assertThat(dto.getTransferList()).hasSize(1);
        assertThat(dto.getTransferList().get(0).getIdTransfer()).isEqualTo("1");
        assertThat(dto.getTransferList().get(0).getTransferAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(dto.getTransferList().get(0).getFiscalCodePA()).isEqualTo("12345678901");
        assertThat(dto.getTransferList().get(0).getIBAN()).isEqualTo("IT60X0542811101000000123456");
        assertThat(dto.getTransferList().get(0).getRemittanceInformation()).isEqualTo("TARI 2026 saldo");
        assertThat(dto.getTransferList().get(0).getTransferCategory()).isEqualTo("9/0101108TS/");
    }

    /**
     * {@code paymentDateTime} nella fixture ha offset {@code +02:00}
     * ({@code 2026-09-01T10:00:00.000+02:00}): l'adapter JAXB condiviso lo tronca (vedi
     * Javadoc di {@link RTDateTimeUtils}), quindi il risultato e' l'ora locale letta cosi'
     * com'e' ma rietichettata nel fuso di default della JVM — Europe/Rome su macchine di
     * sviluppo/CI cosi' configurate (stessa assunzione di govpay-console-api), 1 settembre =
     * ora legale quindi {@code +02:00} (stesso valore numerico dell'offset originale per
     * coincidenza: l'adapter l'avrebbe troncato comunque, anche fosse stato diverso) —
     * comportamento noto e documentato, non
     * un bug di questo test.
     */
    @Test
    void mapCtReceiptV2DaXmlReale() throws Exception {
        byte[] xml = fixture("rt-v2_2-ok.xml");
        RicevutaCtReceiptV2 dto = RicevutaCtReceiptMapper
                .mapCtReceiptV2(JaxbUtils.toPaSendRTV2RequestRT(xml).getReceipt());

        assertThat(dto.getTipo()).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);
        assertThat(dto.getReceiptId()).isEqualTo("RT900000001");
        assertThat(dto.getOfficeName()).isEqualTo("Ufficio Tributi");
        assertThat(dto.getDebtor().getStreetName()).isEqualTo("Via Roma 1");
        assertThat(dto.getDebtor().getPostalCode()).isEqualTo("00100");
        assertThat(dto.getDebtor().getCity()).isEqualTo("Roma");
        assertThat(dto.getDebtor().getStateProvinceRegion()).isEqualTo("RM");
        assertThat(dto.getDebtor().getCountry()).isEqualTo("IT");
        assertThat(dto.getPaymentMethod()).isEqualTo("AD");
        assertThat(dto.getPaymentDateTime())
                .isEqualTo(OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2)));
        assertThat(dto.getApplicationDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
        assertThat(dto.getTransferDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
        assertThat(dto.getTransferList()).hasSize(1);
        assertThat(dto.getTransferList().get(0).getIBAN()).isEqualTo("IT60X0542811101000000123456");
    }
}
