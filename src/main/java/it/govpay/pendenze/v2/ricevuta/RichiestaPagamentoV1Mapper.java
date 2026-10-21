package it.govpay.pendenze.v2.ricevuta;

import org.springframework.stereotype.Component;

import java.util.Base64;

import it.gov.digitpa.schemas._2011.pagamenti.CtAllegatoRicevuta;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiMarcaBolloDigitale;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiSingoloPagamentoRT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiSingoloVersamentoRPT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiVersamentoRPT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDatiVersamentoRT;
import it.gov.digitpa.schemas._2011.pagamenti.CtDominio;
import it.gov.digitpa.schemas._2011.pagamenti.CtEnteBeneficiario;
import it.gov.digitpa.schemas._2011.pagamenti.CtIdentificativoUnivoco;
import it.gov.digitpa.schemas._2011.pagamenti.CtIdentificativoUnivocoPersonaFG;
import it.gov.digitpa.schemas._2011.pagamenti.CtIdentificativoUnivocoPersonaG;
import it.gov.digitpa.schemas._2011.pagamenti.CtIstitutoAttestante;
import it.gov.digitpa.schemas._2011.pagamenti.CtSoggettoPagatore;
import it.gov.digitpa.schemas._2011.pagamenti.RPT;
import it.gov.digitpa.schemas._2011.pagamenti.RT;
import it.gov.digitpa.schemas._2011.pagamenti.StAutenticazioneSoggetto;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoAllegatoRicevuta;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivoco;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivocoPersFG;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoIdentificativoUnivocoPersG;
import it.gov.digitpa.schemas._2011.pagamenti.StTipoVersamento;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceipt;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtSubject;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAReceiptV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.CtTransferPAV2;
import it.gov.pagopa.pagopa_api.pa.pafornode.StEntityUniqueIdentifierType;
import it.gov.pagopa.pagopa_api.pa.pafornode.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;

/**
 * Riformatta il contenuto gia' ricostruito/letto di una richiesta di pagamento (formati
 * {@code ctReceipt}/{@code ctReceiptV2}, vedi {@code RicostruttoreRichiestaPagamento} in
 * govpay-common-pendenze) nel vecchio formato V1 ({@code RPT}, lo stesso usato da SANP_230) —
 * porting di {@code MessaggiPagoPAUtils.toCtRichiestaPagamentoTelematico}/
 * {@code toCtRicevutaTelematica} del monolite (jars/core), adattato a {@link Pendenza}/
 * {@link Rpt}.
 *
 * <p><b>Solo per il wrapper v2</b>, deliberatamente non in govpay-common-pendenze: a
 * differenza della ricostruzione della RPT (serve comunque il contenuto, indipendentemente
 * dal formato con cui lo si presenta — quella e' logica di dominio, resta nella libreria),
 * questa e' una scelta di presentazione specifica del contratto v2 legacy (far apparire ogni
 * richiesta di pagamento nello stesso formato storico, qualunque sia la versione pagoPA
 * reale usata) — richiesta esplicitamente per il wrapper, non per client v3-nativi.</p>
 */
@Component
public class RichiestaPagamentoV1Mapper {

    private static final String COD_UNITA_OPERATIVA_EC = "EC";

    private final UnitaOperativaRepository unitaOperativaRepository;
    private final DominioRepository dominioRepository;

    public RichiestaPagamentoV1Mapper(UnitaOperativaRepository unitaOperativaRepository,
            DominioRepository dominioRepository) {
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.dominioRepository = dominioRepository;
    }

    /** Formato {@code ctReceipt} (SANP_240/RPTV1_RTV2). */
    public RPT toRpt(CtPaymentPA data, Rpt rpt, Pendenza pendenza) {
        RPT ctRpt = intestazioneComune(rpt, pendenza, data.getDebtor());

        CtDatiVersamentoRPT datiVersamento = new CtDatiVersamentoRPT();
        // Data del messaggio di richiesta, non la scadenza (data.getDueDate()) — stessa
        // sostituzione di campo del legacy per entrambi i formati (MessaggiPagoPAUtils
        // .toCtRichiestaPagamentoTelematico): "esecuzione" qui significa "quando la richiesta
        // e' stata inviata", non "quando scade". dueDate resta invariata nel formato nativo
        // (quando la conversione V1 e' disabilitata).
        datiVersamento.setDataEsecuzionePagamento(
                rpt.getDataMsgRichiesta() != null ? rpt.getDataMsgRichiesta().toLocalDate() : null);
        datiVersamento.setImportoTotaleDaVersare(data.getPaymentAmount());
        datiVersamento.setTipoVersamento(toStTipoVersamento(rpt.getTipoVersamento()));
        datiVersamento.setIdentificativoUnivocoVersamento(rpt.getIuv());
        // "0" = FirmaRichiesta.NESSUNA nel legacy (jars/orm-beans/.../model/Rpt.java) — mai
        // firmata su questo percorso, nessun altro valore e' mai stato prodotto qui.
        datiVersamento.setFirmaRicevuta("0");
        if (data.getTransferList() != null) {
            for (CtTransferPA transfer : data.getTransferList().getTransfers()) {
                datiVersamento.getDatiSingoloVersamentos().add(toCtDatiSingoloVersamentoRPT(transfer));
            }
        }
        ctRpt.setDatiVersamento(datiVersamento);
        return ctRpt;
    }

    /** Formato {@code ctReceiptV2} (SANP_321_V2/RPTV2_RTV1). */
    public RPT toRpt(CtPaymentPAV2 data, Rpt rpt, Pendenza pendenza) {
        RPT ctRpt = intestazioneComune(rpt, pendenza, data.getDebtor());

        CtDatiVersamentoRPT datiVersamento = new CtDatiVersamentoRPT();
        // Stessa sostituzione dell'overload ctReceipt sopra — vedi il suo commento.
        datiVersamento.setDataEsecuzionePagamento(
                rpt.getDataMsgRichiesta() != null ? rpt.getDataMsgRichiesta().toLocalDate() : null);
        datiVersamento.setImportoTotaleDaVersare(data.getPaymentAmount());
        datiVersamento.setTipoVersamento(toStTipoVersamento(rpt.getTipoVersamento()));
        datiVersamento.setIdentificativoUnivocoVersamento(rpt.getIuv());
        datiVersamento.setFirmaRicevuta("0");
        if (data.getTransferList() != null) {
            for (CtTransferPAV2 transfer : data.getTransferList().getTransfers()) {
                datiVersamento.getDatiSingoloVersamentos().add(toCtDatiSingoloVersamentoRPTV2(transfer));
            }
        }
        ctRpt.setDatiVersamento(datiVersamento);
        return ctRpt;
    }

    /** Formato {@code ctReceipt} (SANP_240/RPTV1_RTV2). */
    public RT toRt(CtReceipt receipt, Rpt rpt, Pendenza pendenza) {
        RT ctRt = intestazioneComuneRt(rpt, pendenza, receipt.getReceiptId(), receipt.getIdPSP(), receipt.getPSPCompanyName(),
                receipt.getDebtor());

        CtDatiVersamentoRT datiPagamento = new CtDatiVersamentoRT();
        datiPagamento.setCodiceContestoPagamento(receipt.getReceiptId());
        datiPagamento.setCodiceEsitoPagamento(toCodiceEsitoPagamento(receipt.getOutcome()));
        datiPagamento.setIdentificativoUnivocoVersamento(receipt.getCreditorReferenceId());
        datiPagamento.setImportoTotalePagato(receipt.getPaymentAmount());
        if (receipt.getTransferList() != null) {
            for (CtTransferPA transfer : receipt.getTransferList().getTransfers()) {
                datiPagamento.getDatiSingoloPagamentos().add(toCtDatiSingoloPagamentoRT(transfer, receipt));
            }
        }
        ctRt.setDatiPagamento(datiPagamento);
        return ctRt;
    }

    /** Formato {@code ctReceiptV2} (SANP_321_V2/RPTV2_RTV1). */
    public RT toRt(CtReceiptV2 receipt, Rpt rpt, Pendenza pendenza) {
        RT ctRt = intestazioneComuneRt(rpt, pendenza, receipt.getReceiptId(), receipt.getIdPSP(), receipt.getPSPCompanyName(),
                receipt.getDebtor());

        CtDatiVersamentoRT datiPagamento = new CtDatiVersamentoRT();
        datiPagamento.setCodiceContestoPagamento(receipt.getReceiptId());
        datiPagamento.setCodiceEsitoPagamento(toCodiceEsitoPagamento(receipt.getOutcome()));
        datiPagamento.setIdentificativoUnivocoVersamento(receipt.getCreditorReferenceId());
        datiPagamento.setImportoTotalePagato(receipt.getPaymentAmount());
        if (receipt.getTransferList() != null) {
            for (CtTransferPAReceiptV2 transfer : receipt.getTransferList().getTransfers()) {
                datiPagamento.getDatiSingoloPagamentos().add(toCtDatiSingoloPagamentoRTV2(transfer, receipt));
            }
        }
        ctRt.setDatiPagamento(datiPagamento);
        return ctRt;
    }

    private RT intestazioneComuneRt(Rpt rpt, Pendenza pendenza, String identificativoRicevuta, String idPsp,
            String denominazionePsp, CtSubject debitore) {
        RT ctRt = new RT();
        ctRt.setVersioneOggetto("6.2.0");

        CtDominio ctDominio = new CtDominio();
        ctDominio.setIdentificativoDominio(rpt.getCodDominio());
        ctDominio.setIdentificativoStazioneRichiedente(rpt.getCodStazione());
        ctRt.setDominio(ctDominio);
        ctRt.setEnteBeneficiario(risolviEnteBeneficiario(pendenza));
        ctRt.setIdentificativoMessaggioRicevuta(identificativoRicevuta);
        ctRt.setRiferimentoMessaggioRichiesta(rpt.getCodMsgRichiesta());
        // I due timestamp sono scambiati rispetto al nome del campo (dataOraMessaggioRicevuta
        // da dataMsgRichiesta, riferimentoDataRichiesta da dataMsgRicevuta): stessa
        // particolarita' del legacy (MessaggiPagoPAUtils.toCtRicevutaTelematica), riportata
        // identica per compatibilita' con i client v2 esistenti.
        ctRt.setDataOraMessaggioRicevuta(
                rpt.getDataMsgRichiesta() != null ? rpt.getDataMsgRichiesta().toLocalDateTime() : null);
        ctRt.setRiferimentoDataRichiesta(rpt.getDataMsgRicevuta() != null ? rpt.getDataMsgRicevuta().toLocalDate() : null);

        CtIdentificativoUnivoco idUnivocoPsp = new CtIdentificativoUnivoco();
        idUnivocoPsp.setCodiceIdentificativoUnivoco(idPsp);
        idUnivocoPsp.setTipoIdentificativoUnivoco(StTipoIdentificativoUnivoco.A);
        CtIstitutoAttestante istitutoAttestante = new CtIstitutoAttestante();
        istitutoAttestante.setIdentificativoUnivocoAttestante(idUnivocoPsp);
        istitutoAttestante.setDenominazioneAttestante(denominazionePsp);
        ctRt.setIstitutoAttestante(istitutoAttestante);

        ctRt.setSoggettoPagatore(toCtSoggettoPagatore(debitore));
        ctRt.setSoggettoVersante(null); // informazione non presente nei formati paForNode
        return ctRt;
    }

    /** {@code "0"} = {@code PAGAMENTO_ESEGUITO}, {@code "1"} = {@code PAGAMENTO_NON_ESEGUITO}. */
    private String toCodiceEsitoPagamento(StOutcome outcome) {
        return outcome == StOutcome.OK ? "0" : "1";
    }

    private CtDatiSingoloPagamentoRT toCtDatiSingoloPagamentoRT(CtTransferPA transfer, CtReceipt receipt) {
        CtDatiSingoloPagamentoRT dati = new CtDatiSingoloPagamentoRT();
        dati.setCausaleVersamento(transfer.getRemittanceInformation());
        dati.setCommissioniApplicatePSP(receipt.getFee());
        dati.setDataEsitoSingoloPagamento(
                receipt.getPaymentDateTime() != null ? receipt.getPaymentDateTime().toLocalDate() : null);
        dati.setDatiSpecificiRiscossione(transfer.getTransferCategory());
        dati.setEsitoSingoloPagamento(receipt.getOutcome().toString());
        dati.setIdentificativoUnivocoRiscossione(receipt.getReceiptId());
        dati.setSingoloImportoPagato(transfer.getTransferAmount());
        return dati;
    }

    private CtDatiSingoloPagamentoRT toCtDatiSingoloPagamentoRTV2(CtTransferPAReceiptV2 transfer, CtReceiptV2 receipt) {
        CtDatiSingoloPagamentoRT dati = new CtDatiSingoloPagamentoRT();
        if (transfer.getMBDAttachment() != null) {
            CtAllegatoRicevuta allegatoRicevuta = new CtAllegatoRicevuta();
            allegatoRicevuta.setTestoAllegato(transfer.getMBDAttachment());
            allegatoRicevuta.setTipoAllegatoRicevuta(StTipoAllegatoRicevuta.BD);
            dati.setAllegatoRicevuta(allegatoRicevuta);
        }
        dati.setCausaleVersamento(transfer.getRemittanceInformation());
        dati.setCommissioniApplicatePSP(receipt.getFee());
        dati.setDataEsitoSingoloPagamento(
                receipt.getPaymentDateTime() != null ? receipt.getPaymentDateTime().toLocalDate() : null);
        dati.setDatiSpecificiRiscossione(transfer.getTransferCategory());
        dati.setEsitoSingoloPagamento(receipt.getOutcome().toString());
        dati.setIdentificativoUnivocoRiscossione(receipt.getReceiptId());
        dati.setSingoloImportoPagato(transfer.getTransferAmount());
        return dati;
    }

    private RPT intestazioneComune(Rpt rpt, Pendenza pendenza, CtSubject debitore) {
        RPT ctRpt = new RPT();
        ctRpt.setVersioneOggetto("6.2.0");

        CtDominio ctDominio = new CtDominio();
        ctDominio.setIdentificativoDominio(rpt.getCodDominio());
        ctDominio.setIdentificativoStazioneRichiedente(rpt.getCodStazione());
        ctRpt.setDominio(ctDominio);
        ctRpt.setIdentificativoMessaggioRichiesta(rpt.getCodMsgRichiesta());
        ctRpt.setDataOraMessaggioRichiesta(rpt.getDataMsgRichiesta() != null
                ? rpt.getDataMsgRichiesta().toLocalDateTime() : null);
        // Informazione non presente nei formati paForNode, ma campo obbligatorio nel formato
        // V1 — stesso valore fisso usato dal legacy per questo stesso motivo.
        ctRpt.setAutenticazioneSoggetto(StAutenticazioneSoggetto.N_A);
        ctRpt.setSoggettoPagatore(toCtSoggettoPagatore(debitore));
        ctRpt.setEnteBeneficiario(risolviEnteBeneficiario(pendenza));
        return ctRpt;
    }

    /**
     * L'ente beneficiario e' anagraficamente una {@code UnitaOperativa} con {@code codUo="EC"}
     * (l'ente creditore stesso, non una vera unita' operativa) — mai colonne proprie su
     * {@code domini}, verificato nel legacy ({@code Dominio.getAnagrafica()} risolve
     * esattamente cosi'). L'unita' operativa specifica della posizione (se presente e diversa
     * da "EC") sovrascrive l'indirizzo, mai il nome/codice del beneficiario (che restano
     * sempre quelli del dominio) — stessa precedenza di {@code MessaggiPagoPAUtils}.
     */
    private CtEnteBeneficiario risolviEnteBeneficiario(Pendenza pendenza) {
        Long idDominio = pendenza.getIdDominio();
        DominioEntity dominio = dominioRepository.findById(idDominio)
                .orElseThrow(() -> new IllegalStateException("Dominio [id:" + idDominio + "] non trovato in anagrafica"));

        CtEnteBeneficiario enteBeneficiario = new CtEnteBeneficiario();
        CtIdentificativoUnivocoPersonaG idUnivoco = new CtIdentificativoUnivocoPersonaG();
        idUnivoco.setCodiceIdentificativoUnivoco(dominio.getCodDominio());
        idUnivoco.setTipoIdentificativoUnivoco(StTipoIdentificativoUnivocoPersG.G);
        enteBeneficiario.setIdentificativoUnivocoBeneficiario(idUnivoco);
        enteBeneficiario.setDenominazioneBeneficiario(dominio.getRagioneSociale());

        UnitaOperativaEntity ec = unitaOperativaRepository.findByCodUoAndDominioId(COD_UNITA_OPERATIVA_EC, idDominio)
                .orElse(null);
        applicaIndirizzo(enteBeneficiario, ec);

        Long idUnitaOperativa = idUnitaOperativaDellaPosizione(pendenza);
        if (idUnitaOperativa != null) {
            unitaOperativaRepository.findById(idUnitaOperativa)
                    .filter(uo -> !COD_UNITA_OPERATIVA_EC.equals(uo.getCodUo()))
                    .ifPresent(uo -> {
                        enteBeneficiario.setCodiceUnitOperBeneficiario(uo.getUoCodiceIdentificativo());
                        enteBeneficiario.setDenomUnitOperBeneficiario(uo.getUoDenominazione());
                        applicaIndirizzo(enteBeneficiario, uo);
                    });
        }
        return enteBeneficiario;
    }

    private Long idUnitaOperativaDellaPosizione(Pendenza pendenza) {
        OpzionePagamento opzione = pendenza.getOpzionePagamento();
        if (opzione == null) {
            return null;
        }
        PosizioneDebitoria posizione = opzione.getPosizioneDebitoria();
        return posizione != null ? posizione.getIdUnitaOperativa() : null;
    }

    private void applicaIndirizzo(CtEnteBeneficiario enteBeneficiario, UnitaOperativaEntity uo) {
        if (uo == null) {
            return;
        }
        enteBeneficiario.setIndirizzoBeneficiario(uo.getUoIndirizzo());
        enteBeneficiario.setCivicoBeneficiario(uo.getUoCivico());
        enteBeneficiario.setCapBeneficiario(uo.getUoCap());
        enteBeneficiario.setLocalitaBeneficiario(uo.getUoLocalita());
        enteBeneficiario.setProvinciaBeneficiario(uo.getUoProvincia());
        enteBeneficiario.setNazioneBeneficiario(uo.getUoNazione());
    }

    private CtSoggettoPagatore toCtSoggettoPagatore(CtSubject debitore) {
        if (debitore == null) {
            return null;
        }
        CtSoggettoPagatore soggettoPagatore = new CtSoggettoPagatore();
        CtIdentificativoUnivocoPersonaFG idUnivoco = new CtIdentificativoUnivocoPersonaFG();
        idUnivoco.setCodiceIdentificativoUnivoco(debitore.getUniqueIdentifier().getEntityUniqueIdentifierValue());
        idUnivoco.setTipoIdentificativoUnivoco(
                debitore.getUniqueIdentifier().getEntityUniqueIdentifierType() == StEntityUniqueIdentifierType.F
                        ? StTipoIdentificativoUnivocoPersFG.F
                        : StTipoIdentificativoUnivocoPersFG.G);
        soggettoPagatore.setIdentificativoUnivocoPagatore(idUnivoco);
        soggettoPagatore.setAnagraficaPagatore(debitore.getFullName());
        soggettoPagatore.setIndirizzoPagatore(debitore.getStreetName());
        soggettoPagatore.setCivicoPagatore(debitore.getCivicNumber());
        soggettoPagatore.setCapPagatore(debitore.getPostalCode());
        soggettoPagatore.setLocalitaPagatore(debitore.getCity());
        soggettoPagatore.setProvinciaPagatore(debitore.getStateProvinceRegion());
        soggettoPagatore.setNazionePagatore(debitore.getCountry());
        soggettoPagatore.setEMailPagatore(debitore.getEMail());
        return soggettoPagatore;
    }

    private CtDatiSingoloVersamentoRPT toCtDatiSingoloVersamentoRPT(CtTransferPA transfer) {
        CtDatiSingoloVersamentoRPT dati = new CtDatiSingoloVersamentoRPT();
        dati.setCausaleVersamento(transfer.getRemittanceInformation());
        dati.setDatiSpecificiRiscossione(transfer.getTransferCategory());
        dati.setIbanAccredito(transfer.getIBAN());
        dati.setImportoSingoloVersamento(transfer.getTransferAmount());
        return dati;
    }

    /**
     * {@code richiestaMarcaDaBollo} riportato quando presente — porting di
     * {@code MessaggiPagoPAUtils.toDatiSingoloVersamentoV2} (jars/core): hash del documento
     * codificato in base64 (il formato XML V1 lo richiede come stringa, non byte grezzi),
     * provincia e tipo bollo passati cosi' com'erano.
     */
    private CtDatiSingoloVersamentoRPT toCtDatiSingoloVersamentoRPTV2(CtTransferPAV2 transfer) {
        CtDatiSingoloVersamentoRPT dati = new CtDatiSingoloVersamentoRPT();
        dati.setCausaleVersamento(transfer.getRemittanceInformation());
        dati.setDatiSpecificiRiscossione(transfer.getTransferCategory());
        dati.setIbanAccredito(transfer.getIBAN());
        dati.setImportoSingoloVersamento(transfer.getTransferAmount());
        if (transfer.getRichiestaMarcaDaBollo() != null) {
            var richiesta = transfer.getRichiestaMarcaDaBollo();
            CtDatiMarcaBolloDigitale datiMarcaBolloDigitale = new CtDatiMarcaBolloDigitale();
            datiMarcaBolloDigitale.setHashDocumento(Base64.getEncoder().encodeToString(richiesta.getHashDocumento()));
            datiMarcaBolloDigitale.setProvinciaResidenza(richiesta.getProvinciaResidenza());
            datiMarcaBolloDigitale.setTipoBollo(richiesta.getTipoBollo());
            dati.setDatiMarcaBolloDigitale(datiMarcaBolloDigitale);
        }
        return dati;
    }

    /**
     * {@code rpt.tipoVersamento} e' gia' nella stessa codifica di {@link StTipoVersamento}
     * (es. {@code BBT}/{@code BP}/{@code AD}) — a differenza del legacy, che passa per
     * l'enum di business intermedio {@code Canale.TipoVersamento} solo per poi riconvertirlo
     * nella stessa codifica, qui si mappa direttamente. {@code OTH} se il valore e' assente o
     * non riconosciuto — stessa rete di sicurezza del legacy ({@code TipoVersamento.OTHER}).
     */
    private StTipoVersamento toStTipoVersamento(String codifica) {
        if (codifica == null) {
            return StTipoVersamento.OTH;
        }
        try {
            return StTipoVersamento.fromValue(codifica);
        } catch (IllegalArgumentException e) {
            return StTipoVersamento.OTH;
        }
    }
}
