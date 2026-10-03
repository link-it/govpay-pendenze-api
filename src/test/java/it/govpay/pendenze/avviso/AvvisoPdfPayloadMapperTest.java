package it.govpay.pendenze.avviso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.repository.DominioLogoRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.pendenze.api.model.LinguaSecondaria;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.stampe.client.model.Languages;
import it.govpay.stampe.client.model.PaymentNotice;

class AvvisoPdfPayloadMapperTest {

    private static final long ID_DOMINIO = 1L;

    private final DominioRepository dominioRepository = mock(DominioRepository.class);
    private final IbanAccreditoRepository ibanAccreditoRepository = mock(IbanAccreditoRepository.class);
    private final TributoRepository tributoRepository = mock(TributoRepository.class);
    private final DominioLogoRepository dominioLogoRepository = mock(DominioLogoRepository.class);
    private final AvvisoPdfPayloadMapper mapper = new AvvisoPdfPayloadMapper(dominioRepository,
            ibanAccreditoRepository, tributoRepository, dominioLogoRepository);

    private DominioEntity dominio() {
        StazioneEntity stazione = new StazioneEntity();
        stazione.setApplicationCode(1);
        return DominioEntity.builder()
                .codDominio("12345678901")
                .ragioneSociale("Comune di Test")
                .auxDigit(0)
                .stazione(stazione)
                .build();
    }

    private Pendenza pendenza(DominioEntity dominio, String indirizzo) {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(0);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setAnagrafica("Mario Rossi");
        soggetto.setIndirizzo(indirizzo);
        soggetto.setCivico("10");
        soggetto.setCap("00100");
        soggetto.setLocalita("Roma");
        soggetto.setProvincia("RM");
        return pendenzaConSoggetto(dominio, soggetto);
    }

    private Pendenza pendenzaConSoggetto(DominioEntity dominio, SoggettoDebitore soggetto) {
        PosizioneDebitoria posizioneDebitoria = new PosizioneDebitoria();
        posizioneDebitoria.setDescrizione("Diritti di segreteria");
        posizioneDebitoria.addSoggettoDebitore(soggetto);

        OpzionePagamento opzionePagamento = new OpzionePagamento();
        opzionePagamento.setPosizioneDebitoria(posizioneDebitoria);
        opzionePagamento.setDataScadenza(LocalDate.of(2026, 12, 31));

        Pendenza pendenza = new Pendenza();
        pendenza.setOpzionePagamento(opzionePagamento);
        pendenza.setIdDominio(ID_DOMINIO);
        pendenza.setImporto(100.0);
        pendenza.setIuv("123456789012345");
        pendenza.setNumeroAvviso("001123456789012345");
        pendenza.setDataValidita(OffsetDateTime.now());

        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio));
        return pendenza;
    }

    @Test
    void mappaCreditoreDebitoreETitolo() {
        DominioEntity dominio = dominio();
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio, "Via Roma"), null, null, null, null);

        assertThat(notice.getLanguage()).isEqualTo(Languages.IT);
        assertThat(notice.getTitle()).isEqualTo("Diritti di segreteria");
        assertThat(notice.getCreditor().getFiscalCode()).isEqualTo("12345678901");
        assertThat(notice.getCreditor().getBusinessName()).isEqualTo("Comune di Test");
        assertThat(notice.getDebtor().getFiscalCode()).isEqualTo("RSSMRA80A01H501U");
        assertThat(notice.getDebtor().getFullName()).isEqualTo("Mario Rossi");
        assertThat(notice.getDebtor().getAddressLine1()).isEqualTo("Via Roma, 10");
        assertThat(notice.getDebtor().getAddressLine2()).isEqualTo("00100 Roma (RM)");
    }

    @Test
    void cbillCodeLettoDalDominio() {
        DominioEntity dominio = DominioEntity.builder()
                .codDominio("12345678901")
                .ragioneSociale("Comune di Test")
                .auxDigit(0)
                .cbill(("ABCDE"))
                .stazione(new StazioneEntity())
                .build();

        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio, "Via Roma"), null, null, null, null);

        assertThat(notice.getCreditor().getCbillCode()).isEqualTo("ABCDE");
    }

    @Test
    void cbillCodeAssenteSeDominioNonLoHaConfigurato() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getCreditor().getCbillCode()).isNull();
    }

    @Test
    void indirizzoAssenteNonValorizzaAddressLine1() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), null), null, null, null, null);

        assertThat(notice.getDebtor().getAddressLine1()).isNull();
    }

    // --- first_logo: letto da DominioLogoRepository (govpay-common), per codDominio ---

    @Test
    void firstLogoVuotoSeIlDominioNonHaUnLogoCaricato() {
        when(dominioLogoRepository.findLogoByCodDominio("12345678901")).thenReturn(Optional.empty());

        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getFirstLogo()).isEmpty();
    }

    /** Colonna {@code logo} presente ma nulla: la query JPQL puo' restituire un Optional non
     * vuoto con contenuto nullo (vedi {@code DominioLogoRepositoryTest} in govpay-common). */
    @Test
    void firstLogoVuotoSeLaColonnaLogoENulla() {
        when(dominioLogoRepository.findLogoByCodDominio("12345678901")).thenReturn(Optional.ofNullable(null));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getFirstLogo()).isEmpty();
    }

    @Test
    void firstLogoLettoDalDominio() {
        byte[] logo = { 1, 2, 3 };
        when(dominioLogoRepository.findLogoByCodDominio("12345678901")).thenReturn(Optional.of(logo));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getFirstLogo()).isEqualTo(logo);
    }

    @Test
    void fullQrcodeENoticeNumberDallaPendenza() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getFull().getNoticeNumber()).isEqualTo("001123456789012345");
        assertThat(notice.getFull().getQrcode())
                .isEqualTo("PAGOPA|002|001123456789012345|12345678901|10000");
        assertThat(notice.getFull().getDueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void nessunaLinguaSecondariaConFalse() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), LinguaSecondaria.FALSE, null,
                null, null);

        assertThat(notice.getSecondLanguage()).isNull();
    }

    @Test
    void linguaSecondariaValorizzataImpostaBilinguismo() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), LinguaSecondaria.EN, null,
                null, null);

        assertThat(notice.getSecondLanguage().getBilinguism()).isTrue();
        assertThat(notice.getSecondLanguage().getLanguage()).isEqualTo(Languages.EN);
    }

    @Test
    void deMapsToClientDE() {
        assertThat(AvvisoPdfPayloadMapper.toClientLanguage(LinguaSecondaria.DE)).isEqualTo(Languages.DE);
    }

    @Test
    void frMapsToClientFR() {
        assertThat(AvvisoPdfPayloadMapper.toClientLanguage(LinguaSecondaria.FR)).isEqualTo(Languages.FR);
    }

    @Test
    void slMapsToClientSL() {
        assertThat(AvvisoPdfPayloadMapper.toClientLanguage(LinguaSecondaria.SL)).isEqualTo(Languages.SL);
    }

    @Test
    void falseMapsToNull() {
        assertThat(AvvisoPdfPayloadMapper.toClientLanguage(LinguaSecondaria.FALSE)).isNull();
    }

    @Test
    void nullMapsToNull() {
        assertThat(AvvisoPdfPayloadMapper.toClientLanguage(null)).isNull();
    }

    // --- title ('oggetto del pagamento'): la descrizione della posizione debitoria ---

    @Test
    void titleEhLaDescrizioneDellaPosizioneDebitoria() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getTitle()).isEqualTo("Diritti di segreteria");
    }

    @Test
    void secondLanguageTitleEhCausaleTradottaSeValorizzata() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), LinguaSecondaria.EN,
                "Secretarial fees", null, null);

        assertThat(notice.getSecondLanguage().getTitle()).isEqualTo("Secretarial fees");
    }

    @Test
    void secondLanguageTitleAssenteSeCausaleTradottaNonValorizzata() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), LinguaSecondaria.EN, null,
                null, null);

        assertThat(notice.getSecondLanguage().getTitle()).isNull();
    }

    // --- informativaImporto: inoltrata cosi' com'e' a govpay-stampe ---

    @Test
    void informativaImportoAssenteDiDefault() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getInformativaImporto()).isNull();
    }

    @Test
    void informativaImportoValorizzataInoltrataAGovpayStampe() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null,
                "Testo personalizzato", null);

        assertThat(notice.getInformativaImporto()).isEqualTo("Testo personalizzato");
    }

    @Test
    void informativaImportoVuotaInoltrataCosiComE() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, "", null);

        assertThat(notice.getInformativaImporto()).isEmpty();
    }

    // --- second_language.informativaImporto: inoltrata cosi' com'e' a govpay-stampe ---

    @Test
    void secondLanguageInformativaImportoAssenteSeNonValorizzata() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), LinguaSecondaria.EN, null,
                null, null);

        assertThat(notice.getSecondLanguage().getInformativaImporto()).isNull();
    }

    @Test
    void secondLanguageInformativaImportoInoltrataAGovpayStampe() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), LinguaSecondaria.EN, null,
                "Testo personalizzato", "Custom text");

        assertThat(notice.getSecondLanguage().getInformativaImporto()).isEqualTo("Custom text");
    }

    // --- bollettino postale: valorizzazione dell'IBAN (govpay-stampe lo esige con postal=true) ---

    private static IbanAccreditoEntity iban(String codIban, boolean postale) {
        IbanAccreditoEntity entity = new IbanAccreditoEntity();
        entity.setCodIban(codIban);
        entity.setPostale(postale);
        return entity;
    }

    private static VocePendenza primaVoce(Pendenza pendenza) {
        VocePendenza voce = new VocePendenza();
        pendenza.getVoci().add(voce);
        return voce;
    }

    @Test
    void pendenzaSenzaVociNonEPostale() {
        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio(), "Via Roma"), null, null, null, null);

        assertThat(notice.getPostal()).isFalse();
        assertThat(notice.getFull().getIban()).isNull();
    }

    @Test
    void ibanAccreditoSullaPrimaVocePostaleAttivaIlBollettino() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        primaVoce(pendenza).setIdIbanAccredito(10L);
        when(ibanAccreditoRepository.findById(10L))
                .thenReturn(Optional.of(iban("IT60X0542811101000000123456", true)));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getPostal()).isTrue();
        assertThat(notice.getFull().getIban().getIbanCode()).isEqualTo("IT60X0542811101000000123456");
    }

    /**
     * Pendenza a riferimento: la voce non ha {@code idIbanAccredito} valorizzato e l'IBAN va
     * ereditato dal tipo entrata del dominio ({@code tributi}), come fa console-api.
     */
    @Test
    void pendenzaARiferimentoEreditaLIbanPostaleDalTributo() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        primaVoce(pendenza).setIdTributo(20L);
        TributoEntity tributo = new TributoEntity();
        tributo.setIbanAccredito(iban("IT60X0542811101000000999999", true));
        when(tributoRepository.findById(20L)).thenReturn(Optional.of(tributo));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getPostal()).isTrue();
        assertThat(notice.getFull().getIban().getIbanCode()).isEqualTo("IT60X0542811101000000999999");
    }

    @Test
    void ibanSullaVocePrevaleSuQuelloDelTributo() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        VocePendenza voce = primaVoce(pendenza);
        voce.setIdIbanAccredito(10L);
        voce.setIdTributo(20L);
        when(ibanAccreditoRepository.findById(10L))
                .thenReturn(Optional.of(iban("IT60X0542811101000000123456", true)));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getFull().getIban().getIbanCode()).isEqualTo("IT60X0542811101000000123456");
    }

    /** Stesso vincolo del legacy: l'appoggio si guarda solo se l'accredito non e' postale. */
    @Test
    void ibanDiAppoggioUsatoQuandoLAccreditoNonEPostale() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        VocePendenza voce = primaVoce(pendenza);
        voce.setIdIbanAccredito(10L);
        voce.setIdIbanAppoggio(11L);
        when(ibanAccreditoRepository.findById(10L))
                .thenReturn(Optional.of(iban("IT60X0542811101000000123456", false)));
        when(ibanAccreditoRepository.findById(11L))
                .thenReturn(Optional.of(iban("IT60X0542811101000000777777", true)));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getPostal()).isTrue();
        assertThat(notice.getFull().getIban().getIbanCode()).isEqualTo("IT60X0542811101000000777777");
    }

    @Test
    void nessunIbanPostaleNessunBollettinoPostale() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        primaVoce(pendenza).setIdIbanAccredito(10L);
        when(ibanAccreditoRepository.findById(10L))
                .thenReturn(Optional.of(iban("IT60X0542811101000000123456", false)));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getPostal()).isFalse();
        assertThat(notice.getFull().getIban()).isNull();
    }

    /** Intestatario e autorizzazione si inviano grezzi: i fallback li applica govpay-stampe. */
    @Test
    void intestatarioEAutorizzazioneInoltratiGrezzi() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        primaVoce(pendenza).setIdIbanAccredito(10L);
        IbanAccreditoEntity accredito = iban("IT60X0542811101000000123456", true);
        accredito.setIntestatario("Comune di Test");
        accredito.setAutStampaPoste("AUT. 123/2026");
        when(ibanAccreditoRepository.findById(10L)).thenReturn(Optional.of(accredito));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getFull().getIban().getOwnerBusinessName()).isEqualTo("Comune di Test");
        assertThat(notice.getFull().getIban().getPostalAuthMessage()).isEqualTo("AUT. 123/2026");
    }

    @Test
    void ownerBusinessNameTroncatoA50Caratteri() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        primaVoce(pendenza).setIdIbanAccredito(10L);
        IbanAccreditoEntity accredito = iban("IT60X0542811101000000123456", true);
        accredito.setIntestatario("A".repeat(60));
        when(ibanAccreditoRepository.findById(10L)).thenReturn(Optional.of(accredito));

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getFull().getIban().getOwnerBusinessName()).hasSize(50);
    }

    // --- troncamento: i limiti dello schema (pagoPA) si applicano DOPO la concatenazione ---

    private static SoggettoDebitore soggettoCon(String anagrafica, String indirizzo, String civico, String cap,
            String localita, String provincia) {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setOrdine(0);
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setAnagrafica(anagrafica);
        soggetto.setIndirizzo(indirizzo);
        soggetto.setCivico(civico);
        soggetto.setCap(cap);
        soggetto.setLocalita(localita);
        soggetto.setProvincia(provincia);
        return soggetto;
    }

    @Test
    void businessNameTroncatoA50Caratteri() {
        DominioEntity dominio = DominioEntity.builder()
                .codDominio("12345678901")
                .ragioneSociale("A".repeat(60))
                .auxDigit(0)
                .stazione(new StazioneEntity())
                .build();

        PaymentNotice notice = mapper.toPaymentNotice(pendenza(dominio, "Via Roma"), null, null, null, null);

        assertThat(notice.getCreditor().getBusinessName()).hasSize(50);
    }

    @Test
    void fullNameTroncatoA70Caratteri() {
        SoggettoDebitore soggetto = soggettoCon("B".repeat(80), "Via Roma", "10", "00100", "Roma", "RM");
        Pendenza pendenza = pendenzaConSoggetto(dominio(), soggetto);

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getDebtor().getFullName()).hasSize(70);
    }

    /** indirizzo (70) + ", " + civico (16) puo' superare 70: il limite va sul risultato finale. */
    @Test
    void addressLine1TroncatoA70CaratteriDopoLaConcatenazione() {
        SoggettoDebitore soggetto = soggettoCon("Mario Rossi", "C".repeat(70), "1234567890123456", "00100", "Roma",
                "RM");
        Pendenza pendenza = pendenzaConSoggetto(dominio(), soggetto);

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getDebtor().getAddressLine1()).hasSize(70);
    }

    /** cap (16) + localita (35) + " (" + provincia (35) + ")" puo' superare 70. */
    @Test
    void addressLine2TroncatoA70CaratteriDopoLaConcatenazione() {
        SoggettoDebitore soggetto = soggettoCon("Mario Rossi", "Via Roma", "10", "0000000000000000",
                "D".repeat(35), "E".repeat(35));
        Pendenza pendenza = pendenzaConSoggetto(dominio(), soggetto);

        PaymentNotice notice = mapper.toPaymentNotice(pendenza, null, null, null, null);

        assertThat(notice.getDebtor().getAddressLine2()).hasSize(70);
    }

    @Test
    void isPendenzaMbtFalseSenzaVoci() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");

        assertThat(AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)).isFalse();
    }

    @Test
    void isPendenzaMbtVeroSoloConTuttiETreICampiValorizzati() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");
        VocePendenza voceParziale = new VocePendenza();
        voceParziale.setTipoBollo("01");
        voceParziale.setHashDocumento("hash");
        // provinciaResidenza mancante: non basta
        pendenza.getVoci().add(voceParziale);

        assertThat(AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)).isFalse();

        voceParziale.setProvinciaResidenza("RM");

        assertThat(AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)).isTrue();
    }

    @Test
    void isAnagraficaDebitoreAssenteFalseSeValorizzata() {
        Pendenza pendenza = pendenza(dominio(), "Via Roma");

        assertThat(AvvisoPdfPayloadMapper.isAnagraficaDebitoreAssente(pendenza)).isFalse();
    }

    @Test
    void isAnagraficaDebitoreAssenteVeroSeAnagraficaNulla() {
        SoggettoDebitore soggetto = soggettoCon(null, "Via Roma", "10", "00100", "Roma", "RM");
        Pendenza pendenza = pendenzaConSoggetto(dominio(), soggetto);

        assertThat(AvvisoPdfPayloadMapper.isAnagraficaDebitoreAssente(pendenza)).isTrue();
    }

    @Test
    void isAnagraficaDebitoreAssenteVeroSeAnagraficaVuota() {
        SoggettoDebitore soggetto = soggettoCon("   ", "Via Roma", "10", "00100", "Roma", "RM");
        Pendenza pendenza = pendenzaConSoggetto(dominio(), soggetto);

        assertThat(AvvisoPdfPayloadMapper.isAnagraficaDebitoreAssente(pendenza)).isTrue();
    }
}
