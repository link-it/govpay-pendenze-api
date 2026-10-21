package it.govpay.pendenze.v2.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.gov.digitpa.schemas._2011.pagamenti.CtDominio;
import it.gov.digitpa.schemas._2011.pagamenti.RPT;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.ricevuta.RicevutaNonLeggibileException;
import it.govpay.pendenze.service.RicevutaRendicontazioneService;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;

/**
 * Verifica il dispatch completo contro le stesse fixture XML reali usate altrove in questo
 * repo (vedi {@code RicevutaCtReceiptMapperTest}) — copre entrambi i formati paForNode nel
 * caso "{@code xml_rpt} assente" (il piu' frequente, quello che richiede la ricostruzione).
 */
class RppContenutoMapperTest {

    private static final Long ID_DOMINIO = 1L;

    private final RicevutaRendicontazioneService ricevutaRendicontazioneService =
            mock(RicevutaRendicontazioneService.class);
    private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
    private final DominioRepository dominioRepository = mock(DominioRepository.class);
    private final RichiestaPagamentoV1Mapper richiestaPagamentoV1Mapper =
            new RichiestaPagamentoV1Mapper(unitaOperativaRepository, dominioRepository);

    private RppContenutoMapper mapper(boolean conversioneV1Abilitata) {
        return new RppContenutoMapper(ricevutaRendicontazioneService, richiestaPagamentoV1Mapper,
                new RicevutaJsonConverter(), conversioneV1Abilitata, null);
    }

    private static byte[] fixture(String nome) throws IOException {
        try (InputStream in = RppContenutoMapperTest.class.getResourceAsStream("/rt/" + nome)) {
            return in.readAllBytes();
        }
    }

    private static byte[] marshalRpt(RPT rpt) throws Exception {
        JAXBContext context = JAXBContext.newInstance(RPT.class);
        Marshaller marshaller = context.createMarshaller();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        marshaller.marshal(rpt, out);
        return out.toByteArray();
    }

    /**
     * {@code toRpt}/{@code toRt} restituiscono una struttura generica di {@code Map}/
     * {@code List}/valori semplici (vedi Javadoc di {@link RicevutaJsonConverter}, non
     * piu' un {@code JsonNode}: qui si riconverte solo per comodita' di asserzione nei test
     * (API fluente {@code .get(...).asText()}), con un mapper locale che non ha nulla a che
     * fare con quello usato in produzione.
     */
    private static JsonNode asJsonNode(Object value) {
        return new ObjectMapper().valueToTree(value);
    }

    private static Pendenza pendenza() {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        OpzionePagamento opzione = new OpzionePagamento();
        posizione.addOpzionePagamento(opzione);
        Pendenza pendenza = new Pendenza();
        pendenza.setIdDominio(ID_DOMINIO);
        opzione.addPendenza(pendenza);
        return pendenza;
    }

    private void confDominioEUnitaOperativaEc() {
        DominioEntity dominio = new DominioEntity();
        dominio.setCodDominio("12345678901");
        dominio.setRagioneSociale("Comune di Prova");
        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio));

        UnitaOperativaEntity ec = new UnitaOperativaEntity();
        ec.setCodUo("EC");
        when(unitaOperativaRepository.findByCodUoAndDominioId(eq("EC"), eq(ID_DOMINIO))).thenReturn(Optional.of(ec));
    }

    @Test
    @DisplayName("mapVersioneATipoRpt: le tre versioni miste selezionano il formato della RPT, non quello della RT")
    void mapVersioneATipoRptDistinguePerLeVersioniMiste() {
        // Pure: coincide con RicevutaMapper.mapVersioneATipo (nessuna differenza da verificare).
        assertThat(RppContenutoMapper.mapVersioneATipoRpt("SANP_230")).isEqualTo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
        assertThat(RppContenutoMapper.mapVersioneATipoRpt("SANP_240")).isEqualTo(TipoRicevuta.CT_RECEIPT);
        assertThat(RppContenutoMapper.mapVersioneATipoRpt("SANP_321_V2")).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);

        // Miste: RPT e RT divergono, qui si verifica il formato della RPT (vedi la tabella nel Javadoc di classe).
        assertThat(RppContenutoMapper.mapVersioneATipoRpt("RPTSANP230_RTV2"))
                .isEqualTo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
        assertThat(RppContenutoMapper.mapVersioneATipoRpt("RPTV1_RTV2")).isEqualTo(TipoRicevuta.CT_RECEIPT);
        assertThat(RppContenutoMapper.mapVersioneATipoRpt("RPTV2_RTV1")).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);
    }

    @Test
    @DisplayName("toRpt con versione RPTSANP230_RTV2 (RPT SANP_230, RT ctReceiptV2): legge la RPT come SANP_230, "
            + "non come ctReceiptV2 — prima del fix lanciava NullPointerException (data nullo)")
    void toRptConVersioneMistaLeggeLaRptNelFormatoCorretto() throws Exception {
        RPT rptReale = new RPT();
        rptReale.setVersioneOggetto("6.2.0");
        CtDominio dominio = new CtDominio();
        dominio.setIdentificativoDominio("12345678901");
        dominio.setIdentificativoStazioneRichiedente("STZ-1");
        rptReale.setDominio(dominio);

        Rpt rpt = new Rpt();
        rpt.setId(3L);
        rpt.setIur("RT900000099");
        rpt.setVersione("RPTSANP230_RTV2");
        when(ricevutaRendicontazioneService.trovaXmlRichiestaPagamento(eq(3L)))
                .thenReturn(Optional.of(marshalRpt(rptReale)));

        var rptJson = asJsonNode(mapper(true).toRpt(rpt, pendenza()));

        assertThat(rptJson.get("versioneOggetto").asText()).isEqualTo("6.2.0");
        assertThat(rptJson.get("dominio").get("identificativoDominio").asText()).isEqualTo("12345678901");
    }

    @Test
    @DisplayName("toRpt (ctReceipt, xml_rpt assente, conversione V1 abilitata): ricostruisce dalla RT e riformatta in V1")
    void toRptCtReceiptRicostruitoDallaRtConversioneAbilitata() throws Exception {
        confDominioEUnitaOperativaEc();
        Rpt rpt = new Rpt();
        rpt.setId(1L);
        rpt.setIur("RT900000003");
        rpt.setVersione("SANP_240");
        rpt.setCodDominio("12345678901");
        rpt.setCodStazione("STAZIONE_FIXTURE");
        rpt.setXmlRt(fixture("rt-v2-ok.xml"));
        when(ricevutaRendicontazioneService.trovaXmlRichiestaPagamento(eq(1L))).thenReturn(Optional.empty());

        var rptJson = asJsonNode(mapper(true).toRpt(rpt, pendenza()));

        assertThat(rptJson.get("versioneOggetto").asText()).isEqualTo("6.2.0");
        assertThat(rptJson.get("soggettoPagatore").get("anagraficaPagatore").asText()).isEqualTo("Mario Rossi");
        assertThat(rptJson.get("enteBeneficiario").get("denominazioneBeneficiario").asText())
                .isEqualTo("Comune di Prova");
        assertThat(rptJson.get("datiVersamento").get("importoTotaleDaVersare").asDouble()).isEqualTo(10.00);
        assertThat(rptJson.get("datiVersamento").get("datiSingoloVersamento").get(0).get("ibanAccredito").asText())
                .isEqualTo("IT60X0542811101000000123456");
    }

    @Test
    @DisplayName("toRpt (ctReceiptV2, xml_rpt assente, conversione V1 abilitata): ricostruisce dalla RT e riformatta in V1")
    void toRptCtReceiptV2RicostruitoDallaRtConversioneAbilitata() throws Exception {
        confDominioEUnitaOperativaEc();
        Rpt rpt = new Rpt();
        rpt.setId(2L);
        rpt.setIur("RT900000001");
        rpt.setVersione("SANP_321_V2");
        rpt.setCodDominio("12345678901");
        rpt.setCodStazione("STAZIONE_FIXTURE");
        rpt.setXmlRt(fixture("rt-v2_2-ok.xml"));
        when(ricevutaRendicontazioneService.trovaXmlRichiestaPagamento(eq(2L))).thenReturn(Optional.empty());

        var rptJson = asJsonNode(mapper(true).toRpt(rpt, pendenza()));

        assertThat(rptJson.get("versioneOggetto").asText()).isEqualTo("6.2.0");
        assertThat(rptJson.get("datiVersamento").get("firmaRicevuta").asText()).isEqualTo("0");
        assertThat(rptJson.get("datiVersamento").get("datiSingoloVersamento").get(0).get("causaleVersamento").asText())
                .isEqualTo("TARI 2026 saldo");
    }

    @Test
    @DisplayName("toRpt (ctReceiptV2, xml_rpt assente, conversione V1 DISABILITATA): formato nativo, non riformattato")
    void toRptCtReceiptV2RicostruitoDallaRtConversioneDisabilitata() throws Exception {
        confDominioEUnitaOperativaEc();
        Rpt rpt = new Rpt();
        rpt.setId(2L);
        rpt.setIur("RT900000001");
        rpt.setVersione("SANP_321_V2");
        rpt.setCodDominio("12345678901");
        rpt.setCodStazione("STAZIONE_FIXTURE");
        rpt.setXmlRt(fixture("rt-v2_2-ok.xml"));
        when(ricevutaRendicontazioneService.trovaXmlRichiestaPagamento(eq(2L))).thenReturn(Optional.empty());

        var rptJson = asJsonNode(mapper(false).toRpt(rpt, pendenza()));

        // Formato CtPaymentPAV2 nativo: niente "versioneOggetto"/"datiVersamento" (campi V1),
        // ma "creditorReferenceId"/"transferList" (campi nativi paForNode).
        assertThat(rptJson.has("versioneOggetto")).isFalse();
        assertThat(rptJson.get("creditorReferenceId").asText()).isEqualTo("IUV900000001");
        assertThat(rptJson.get("transferList").get("transfer").get(0).get("remittanceInformation").asText())
                .isEqualTo("TARI 2026 saldo");
    }

    @Test
    @DisplayName("toRt (ctReceipt, conversione V1 abilitata): riformatta in V1, come toRpt")
    void toRtCtReceiptConversioneAbilitata() throws Exception {
        confDominioEUnitaOperativaEc();
        Rpt rpt = new Rpt();
        rpt.setIur("RT900000003");
        rpt.setVersione("SANP_240");
        rpt.setCodDominio("12345678901");
        rpt.setCodStazione("STAZIONE_FIXTURE");
        rpt.setXmlRt(fixture("rt-v2-ok.xml"));

        var rtJson = asJsonNode(mapper(true).toRt(rpt, pendenza()));

        assertThat(rtJson.get("versioneOggetto").asText()).isEqualTo("6.2.0");
        assertThat(rtJson.get("identificativoMessaggioRicevuta").asText()).isEqualTo("RT900000003");
        assertThat(rtJson.get("datiPagamento").get("identificativoUnivocoVersamento").asText())
                .isEqualTo("IUV900000003");
        assertThat(rtJson.get("enteBeneficiario").get("denominazioneBeneficiario").asText())
                .isEqualTo("Comune di Prova");
    }

    @Test
    @DisplayName("toRt (ctReceipt, conversione V1 DISABILITATA, default): formato nativo, non riformattato")
    void toRtCtReceiptConversioneDisabilitataDiDefault() throws Exception {
        Rpt rpt = new Rpt();
        rpt.setIur("RT900000003");
        rpt.setVersione("SANP_240");
        rpt.setXmlRt(fixture("rt-v2-ok.xml"));

        var rtJson = asJsonNode(mapper(false).toRt(rpt, pendenza()));

        assertThat(rtJson.has("versioneOggetto")).isFalse();
        assertThat(rtJson.get("receiptId").asText()).isEqualTo("RT900000003");
        assertThat(rtJson.get("creditorReferenceId").asText()).isEqualTo("IUV900000003");
    }

    @Test
    @DisplayName("toRt senza RT acquisita: errore esplicito, non NullPointerException")
    void toRtSenzaRtAcquisita() {
        Rpt rpt = new Rpt();
        rpt.setIur("RT-MAI-ARRIVATA");
        rpt.setVersione("SANP_240");

        assertThatThrownBy(() -> mapper(true).toRt(rpt, pendenza())).isInstanceOf(RicevutaNonLeggibileException.class);
    }
}
