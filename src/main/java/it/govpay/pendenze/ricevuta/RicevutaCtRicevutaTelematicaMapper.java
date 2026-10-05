package it.govpay.pendenze.ricevuta;

import it.gov.digitpa.schemas._2011.pagamenti.CtDatiSingoloPagamentoRT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiVersamentoRT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDominio;
import it.gov.digitpa.schemas._2011.pagamenti.CtEnteBeneficiario;
import it.gov.digitpa.schemas._2011.pagamenti.CtIstitutoAttestante;
import it.gov.digitpa.schemas._2011.pagamenti.CtSoggettoPagatore;
import it.gov.digitpa.schemas._2011.pagamenti.CtSoggettoVersante;
import it.gov.digitpa.schemas._2011.pagamenti.RT;
import it.govpay.pendenze.api.model.RTAllegatoRicevuta;
import it.govpay.pendenze.api.model.RTAnagrafica;
import it.govpay.pendenze.api.model.RTDatiSingoloPagamento;
import it.govpay.pendenze.api.model.RTIdentificativoUnivoco;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematica;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDatiPagamento;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDatiPagamento.CodiceEsitoPagamentoEnum;
import it.govpay.pendenze.api.model.RicevutaCtRicevutaTelematicaDominio;
import it.govpay.pendenze.api.model.TipoIdentificativoUnivocoRT;
import it.govpay.pendenze.api.model.TipoRicevuta;

/**
 * Mapping {@link RT} (bean JAXB, formato SANP 2.3.0 {@code ctRicevutaTelematica}) →
 * {@link RicevutaCtRicevutaTelematica} (DTO OpenAPI v3).
 *
 * <p>{@code istitutoAttestante}/{@code enteBeneficiario}/{@code soggettoVersante}/
 * {@code soggettoPagatore} sono quattro tipi JAXB distinti ({@link CtIstitutoAttestante},
 * {@link CtEnteBeneficiario}, {@link CtSoggettoVersante}, {@link CtSoggettoPagatore}), con
 * nomi di campo diversi (suffisso Attestante/Beneficiario/Versante/Pagatore) e sottoinsiemi
 * diversi di campi opzionali (istituto/ente hanno {@code codiceUnitaOperativa}/
 * {@code denominazioneUnitaOperativa}, versante/pagatore hanno {@code email} — mai entrambi),
 * ma condividono tutti lo stesso DTO {@link RTAnagrafica} nello YAML v3 (vedi Javadoc di
 * campo su {@code RTAnagrafica.codiceUnitaOperativa} nello YAML): {@link #mapAnagrafica} li
 * unifica passando i singoli valori, non i bean, cosi' i campi non pertinenti restano
 * semplicemente {@code null} invece di richiedere quattro varianti quasi identiche.</p>
 */
final class RicevutaCtRicevutaTelematicaMapper {

    private RicevutaCtRicevutaTelematicaMapper() {
    }

    static RicevutaCtRicevutaTelematica map(RT rt) {
        RicevutaCtRicevutaTelematica dto = new RicevutaCtRicevutaTelematica();
        dto.setTipo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
        dto.setVersioneOggetto(rt.getVersioneOggetto());
        dto.setDominio(mapDominio(rt.getDominio()));
        dto.setIdentificativoMessaggioRicevuta(rt.getIdentificativoMessaggioRicevuta());
        dto.setDataOraMessaggioRicevuta(RTDateTimeUtils.toOffsetDateTimeConvenzionale(rt.getDataOraMessaggioRicevuta()));
        dto.setRiferimentoMessaggioRichiesta(rt.getRiferimentoMessaggioRichiesta());
        dto.setRiferimentoDataRichiesta(rt.getRiferimentoDataRichiesta());
        dto.setIstitutoAttestante(mapIstitutoAttestante(rt.getIstitutoAttestante()));
        dto.setEnteBeneficiario(mapEnteBeneficiario(rt.getEnteBeneficiario()));
        if (rt.getSoggettoVersante() != null) {
            dto.setSoggettoVersante(mapSoggettoVersante(rt.getSoggettoVersante()));
        }
        dto.setSoggettoPagatore(mapSoggettoPagatore(rt.getSoggettoPagatore()));
        dto.setDatiPagamento(mapDatiPagamento(rt.getDatiPagamento()));
        return dto;
    }

    private static RicevutaCtRicevutaTelematicaDominio mapDominio(CtDominio dominio) {
        RicevutaCtRicevutaTelematicaDominio dto = new RicevutaCtRicevutaTelematicaDominio();
        dto.setIdentificativoDominio(dominio.getIdentificativoDominio());
        dto.setIdentificativoStazioneRichiedente(dominio.getIdentificativoStazioneRichiedente());
        return dto;
    }

    private static RTAnagrafica mapIstitutoAttestante(CtIstitutoAttestante istituto) {
        return mapAnagrafica(istituto.getIdentificativoUnivocoAttestante().getTipoIdentificativoUnivoco().name(),
                istituto.getIdentificativoUnivocoAttestante().getCodiceIdentificativoUnivoco(),
                istituto.getDenominazioneAttestante(), istituto.getCodiceUnitOperAttestante(),
                istituto.getDenomUnitOperAttestante(), istituto.getIndirizzoAttestante(),
                istituto.getCivicoAttestante(), istituto.getCapAttestante(), istituto.getLocalitaAttestante(),
                istituto.getProvinciaAttestante(), istituto.getNazioneAttestante(), null);
    }

    private static RTAnagrafica mapEnteBeneficiario(CtEnteBeneficiario ente) {
        return mapAnagrafica(ente.getIdentificativoUnivocoBeneficiario().getTipoIdentificativoUnivoco().name(),
                ente.getIdentificativoUnivocoBeneficiario().getCodiceIdentificativoUnivoco(),
                ente.getDenominazioneBeneficiario(), ente.getCodiceUnitOperBeneficiario(),
                ente.getDenomUnitOperBeneficiario(), ente.getIndirizzoBeneficiario(), ente.getCivicoBeneficiario(),
                ente.getCapBeneficiario(), ente.getLocalitaBeneficiario(), ente.getProvinciaBeneficiario(),
                ente.getNazioneBeneficiario(), null);
    }

    private static RTAnagrafica mapSoggettoVersante(CtSoggettoVersante versante) {
        return mapAnagrafica(versante.getIdentificativoUnivocoVersante().getTipoIdentificativoUnivoco().name(),
                versante.getIdentificativoUnivocoVersante().getCodiceIdentificativoUnivoco(),
                versante.getAnagraficaVersante(), null, null, versante.getIndirizzoVersante(),
                versante.getCivicoVersante(), versante.getCapVersante(), versante.getLocalitaVersante(),
                versante.getProvinciaVersante(), versante.getNazioneVersante(), versante.getEMailVersante());
    }

    private static RTAnagrafica mapSoggettoPagatore(CtSoggettoPagatore pagatore) {
        return mapAnagrafica(pagatore.getIdentificativoUnivocoPagatore().getTipoIdentificativoUnivoco().name(),
                pagatore.getIdentificativoUnivocoPagatore().getCodiceIdentificativoUnivoco(),
                pagatore.getAnagraficaPagatore(), null, null, pagatore.getIndirizzoPagatore(),
                pagatore.getCivicoPagatore(), pagatore.getCapPagatore(), pagatore.getLocalitaPagatore(),
                pagatore.getProvinciaPagatore(), pagatore.getNazionePagatore(), pagatore.getEMailPagatore());
    }

    private static RTAnagrafica mapAnagrafica(String tipoIdentificativoUnivoco, String codiceIdentificativoUnivoco,
            String denominazione, String codiceUnitaOperativa, String denominazioneUnitaOperativa, String indirizzo,
            String civico, String cap, String localita, String provincia, String nazione, String email) {
        RTIdentificativoUnivoco identificativo = new RTIdentificativoUnivoco();
        identificativo.setTipoIdentificativoUnivoco(TipoIdentificativoUnivocoRT.fromValue(tipoIdentificativoUnivoco));
        identificativo.setCodiceIdentificativoUnivoco(codiceIdentificativoUnivoco);

        RTAnagrafica anagrafica = new RTAnagrafica();
        anagrafica.setIdentificativoUnivoco(identificativo);
        anagrafica.setDenominazione(denominazione);
        anagrafica.setCodiceUnitaOperativa(codiceUnitaOperativa);
        anagrafica.setDenominazioneUnitaOperativa(denominazioneUnitaOperativa);
        anagrafica.setIndirizzo(indirizzo);
        anagrafica.setCivico(civico);
        anagrafica.setCap(cap);
        anagrafica.setLocalita(localita);
        anagrafica.setProvincia(provincia);
        anagrafica.setNazione(nazione);
        anagrafica.setEmail(email);
        return anagrafica;
    }

    private static RicevutaCtRicevutaTelematicaDatiPagamento mapDatiPagamento(CtDatiVersamentoRT dati) {
        RicevutaCtRicevutaTelematicaDatiPagamento dto = new RicevutaCtRicevutaTelematicaDatiPagamento();
        dto.setCodiceEsitoPagamento(CodiceEsitoPagamentoEnum.fromValue(Integer.valueOf(dati.getCodiceEsitoPagamento())));
        dto.setImportoTotalePagato(dati.getImportoTotalePagato());
        dto.setIdentificativoUnivocoVersamento(dati.getIdentificativoUnivocoVersamento());
        dto.setCodiceContestoPagamento(dati.getCodiceContestoPagamento());
        if (dati.getDatiSingoloPagamentos() != null) {
            dto.setDatiSingoloPagamento(dati.getDatiSingoloPagamentos().stream()
                    .map(RicevutaCtRicevutaTelematicaMapper::mapDatiSingoloPagamento).toList());
        }
        return dto;
    }

    private static RTDatiSingoloPagamento mapDatiSingoloPagamento(CtDatiSingoloPagamentoRT dati) {
        RTDatiSingoloPagamento dto = new RTDatiSingoloPagamento();
        dto.setSingoloImportoPagato(dati.getSingoloImportoPagato());
        dto.setEsitoSingoloPagamento(dati.getEsitoSingoloPagamento());
        dto.setDataEsitoSingoloPagamento(dati.getDataEsitoSingoloPagamento());
        dto.setIdentificativoUnivocoRiscossione(dati.getIdentificativoUnivocoRiscossione());
        dto.setCausaleVersamento(dati.getCausaleVersamento());
        dto.setDatiSpecificiRiscossione(dati.getDatiSpecificiRiscossione());
        dto.setCommissioniApplicatePSP(dati.getCommissioniApplicatePSP());
        dto.setCommissioniApplicatePA(dati.getCommissioniApplicatePA());
        if (dati.getAllegatoRicevuta() != null) {
            RTAllegatoRicevuta allegato = new RTAllegatoRicevuta();
            allegato.setTipoAllegatoRicevuta(dati.getAllegatoRicevuta().getTipoAllegatoRicevuta().value());
            allegato.setTestoAllegato(dati.getAllegatoRicevuta().getTestoAllegato());
            dto.setAllegatoRicevuta(allegato);
        }
        return dto;
    }
}
