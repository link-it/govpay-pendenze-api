package it.govpay.pendenze.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.gov.digitpa.schemas._2011.pagamenti.CtDatiSingoloPagamentoRT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiVersamentoRT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDominio;
import it.gov.digitpa.schemas._2011.pagamenti.CtEnteBeneficiario;
import it.gov.digitpa.schemas._2011.pagamenti.CtIdentificativoUnivoco;
import it.gov.digitpa.schemas._2011.pagamenti.CtIdentificativoUnivocoPersonaFG;
import it.gov.digitpa.schemas._2011.pagamenti.CtIdentificativoUnivocoPersonaG;
import it.gov.digitpa.schemas._2011.pagamenti.CtIstitutoAttestante;
import it.gov.digitpa.schemas._2011.pagamenti.CtSoggettoPagatore;
import it.gov.digitpa.schemas._2011.pagamenti.RT;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivoco;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivocoPersFG;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivocoPersG;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematica;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDatiPagamento.CodiceEsitoPagamentoEnum;
import it.govpay.pendenze.api.model.TipoIdentificativoUnivocoRT;
import it.govpay.pendenze.api.model.TipoRicevuta;

class RicevutaCtRicevutaTelematicaMapperTest {

    /**
     * RT minimale ma completo (tutti i campi required): {@code istitutoAttestante} con
     * {@code tipoIdentificativoUnivoco=A} (codice ABI) — proprio il caso che la XSD legacy
     * ammette e che {@code TipoSoggetto} (F/G) non potrebbe rappresentare, vedi
     * {@link TipoIdentificativoUnivocoRT}.
     */
    private static RT rt() {
        RT rt = new RT();
        rt.setVersioneOggetto("6.2");
        CtDominio dominio = new CtDominio();
        dominio.setIdentificativoDominio("12345678901");
        dominio.setIdentificativoStazioneRichiedente("STZ-1");
        rt.setDominio(dominio);
        rt.setIdentificativoMessaggioRicevuta("RT900000004");
        rt.setDataOraMessaggioRicevuta(LocalDateTime.of(2026, 6, 10, 9, 32, 0));
        rt.setRiferimentoMessaggioRichiesta("RP900000004");
        rt.setRiferimentoDataRichiesta(LocalDate.of(2026, 6, 9));

        CtIdentificativoUnivoco idAttestante = new CtIdentificativoUnivoco();
        idAttestante.setTipoIdentificativoUnivoco(StTipoIdentificativoUnivoco.A);
        idAttestante.setCodiceIdentificativoUnivoco("01234567890");
        CtIstitutoAttestante istituto = new CtIstitutoAttestante();
        istituto.setIdentificativoUnivocoAttestante(idAttestante);
        istituto.setDenominazioneAttestante("Banca Attestante SpA");
        istituto.setCodiceUnitOperAttestante("UO1");
        istituto.setDenomUnitOperAttestante("Ufficio Centrale");
        rt.setIstitutoAttestante(istituto);

        CtIdentificativoUnivocoPersonaG idBeneficiario = new CtIdentificativoUnivocoPersonaG();
        idBeneficiario.setTipoIdentificativoUnivoco(StTipoIdentificativoUnivocoPersG.G);
        idBeneficiario.setCodiceIdentificativoUnivoco("12345678901");
        CtEnteBeneficiario ente = new CtEnteBeneficiario();
        ente.setIdentificativoUnivocoBeneficiario(idBeneficiario);
        ente.setDenominazioneBeneficiario("Comune di Test");
        rt.setEnteBeneficiario(ente);

        CtIdentificativoUnivocoPersonaFG idPagatore = new CtIdentificativoUnivocoPersonaFG();
        idPagatore.setTipoIdentificativoUnivoco(StTipoIdentificativoUnivocoPersFG.F);
        idPagatore.setCodiceIdentificativoUnivoco("RSSMRA80A01H501U");
        CtSoggettoPagatore pagatore = new CtSoggettoPagatore();
        pagatore.setIdentificativoUnivocoPagatore(idPagatore);
        pagatore.setAnagraficaPagatore("Mario Rossi");
        pagatore.setEMailPagatore("mario.rossi@example.test");
        rt.setSoggettoPagatore(pagatore);

        CtDatiVersamentoRT dati = new CtDatiVersamentoRT();
        dati.setCodiceEsitoPagamento("0");
        dati.setImportoTotalePagato(new BigDecimal("16.00"));
        dati.setIdentificativoUnivocoVersamento("IUV900000004");
        dati.setCodiceContestoPagamento("CCP900000004");
        CtDatiSingoloPagamentoRT singolo = new CtDatiSingoloPagamentoRT();
        singolo.setSingoloImportoPagato(new BigDecimal("16.00"));
        singolo.setDataEsitoSingoloPagamento(LocalDate.of(2026, 6, 10));
        singolo.setIdentificativoUnivocoRiscossione("IUR900000004");
        singolo.setCausaleVersamento("Diritti di segreteria");
        singolo.setDatiSpecificiRiscossione("01/DIRSEGR001");
        dati.getDatiSingoloPagamentos().add(singolo);
        rt.setDatiPagamento(dati);

        return rt;
    }

    @Test
    void mapRicevutaCtRicevutaTelematica() {
        RicevutaCtRicevutaTelematica dto = RicevutaCtRicevutaTelematicaMapper.map(rt());

        assertThat(dto.getTipo()).isEqualTo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
        assertThat(dto.getVersioneOggetto()).isEqualTo("6.2");
        assertThat(dto.getDominio().getIdentificativoDominio()).isEqualTo("12345678901");
        assertThat(dto.getDominio().getIdentificativoStazioneRichiedente()).isEqualTo("STZ-1");
        assertThat(dto.getIdentificativoMessaggioRicevuta()).isEqualTo("RT900000004");
        assertThat(dto.getDataOraMessaggioRicevuta())
                .isEqualTo(OffsetDateTime.of(2026, 6, 10, 9, 32, 0, 0, ZoneOffset.ofHours(2)));
        assertThat(dto.getRiferimentoDataRichiesta()).isEqualTo(LocalDate.of(2026, 6, 9));
    }

    /** Il caso che TipoSoggetto (F/G) non potrebbe rappresentare — vedi Javadoc di {@link #rt()}. */
    @Test
    void istitutoAttestanteConCodiceAbiMappaSuTipoA() {
        RicevutaCtRicevutaTelematica dto = RicevutaCtRicevutaTelematicaMapper.map(rt());

        assertThat(dto.getIstitutoAttestante().getIdentificativoUnivoco().getTipoIdentificativoUnivoco())
                .isEqualTo(TipoIdentificativoUnivocoRT.A);
        assertThat(dto.getIstitutoAttestante().getIdentificativoUnivoco().getCodiceIdentificativoUnivoco())
                .isEqualTo("01234567890");
        assertThat(dto.getIstitutoAttestante().getDenominazione()).isEqualTo("Banca Attestante SpA");
        assertThat(dto.getIstitutoAttestante().getCodiceUnitaOperativa()).isEqualTo("UO1");
    }

    @Test
    void enteBeneficiarioSoloTipoG() {
        RicevutaCtRicevutaTelematica dto = RicevutaCtRicevutaTelematicaMapper.map(rt());

        assertThat(dto.getEnteBeneficiario().getIdentificativoUnivoco().getTipoIdentificativoUnivoco())
                .isEqualTo(TipoIdentificativoUnivocoRT.G);
        assertThat(dto.getEnteBeneficiario().getDenominazione()).isEqualTo("Comune di Test");
    }

    @Test
    void soggettoPagatoreTipoFConEmail() {
        RicevutaCtRicevutaTelematica dto = RicevutaCtRicevutaTelematicaMapper.map(rt());

        assertThat(dto.getSoggettoPagatore().getIdentificativoUnivoco().getTipoIdentificativoUnivoco())
                .isEqualTo(TipoIdentificativoUnivocoRT.F);
        assertThat(dto.getSoggettoPagatore().getDenominazione()).isEqualTo("Mario Rossi");
        assertThat(dto.getSoggettoPagatore().getEmail()).isEqualTo("mario.rossi@example.test");
        assertThat(dto.getSoggettoPagatore().getCodiceUnitaOperativa()).isNull();
    }

    @Test
    void soggettoVersanteAssenteSeNonPresenteNellXml() {
        RicevutaCtRicevutaTelematica dto = RicevutaCtRicevutaTelematicaMapper.map(rt());

        assertThat(dto.getSoggettoVersante()).isNull();
    }

    @Test
    void datiPagamentoEDatiSingoloPagamento() {
        RicevutaCtRicevutaTelematica dto = RicevutaCtRicevutaTelematicaMapper.map(rt());

        assertThat(dto.getDatiPagamento().getCodiceEsitoPagamento()).isEqualTo(CodiceEsitoPagamentoEnum.NUMBER_0);
        assertThat(dto.getDatiPagamento().getImportoTotalePagato()).isEqualByComparingTo(new BigDecimal("16.00"));
        assertThat(dto.getDatiPagamento().getIdentificativoUnivocoVersamento()).isEqualTo("IUV900000004");
        assertThat(dto.getDatiPagamento().getCodiceContestoPagamento()).isEqualTo("CCP900000004");

        List<it.govpay.pendenze.api.model.RTDatiSingoloPagamento> singoli = dto.getDatiPagamento()
                .getDatiSingoloPagamento();
        assertThat(singoli).hasSize(1);
        assertThat(singoli.get(0).getSingoloImportoPagato()).isEqualByComparingTo(new BigDecimal("16.00"));
        assertThat(singoli.get(0).getDataEsitoSingoloPagamento()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(singoli.get(0).getIdentificativoUnivocoRiscossione()).isEqualTo("IUR900000004");
        assertThat(singoli.get(0).getCausaleVersamento()).isEqualTo("Diritti di segreteria");
        assertThat(singoli.get(0).getAllegatoRicevuta()).isNull();
    }
}
