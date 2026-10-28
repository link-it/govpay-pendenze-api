package it.govpay.pendenze.v2.pendenza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.StatoPagamento;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.model.TipoSoggetto;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.service.CausaleVersamentoCodec;

class PendenzaV2MapperTest {

    private static final Long ID_APPLICAZIONE = 10L;
    private static final Long ID_DOMINIO = 20L;

    private final ApplicazioneRepository applicazioneRepository = mock(ApplicazioneRepository.class);
    private final DominioRepository dominioRepository = mock(DominioRepository.class);
    private final UnitaOperativaRepository unitaOperativaRepository = mock(UnitaOperativaRepository.class);
    private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository =
            mock(TipoVersamentoDominioRepository.class);
    private final TributoRepository tributoRepository = mock(TributoRepository.class);
    private final IbanAccreditoRepository ibanAccreditoRepository = mock(IbanAccreditoRepository.class);
    private final PendenzaV2Mapper mapper = new PendenzaV2Mapper(applicazioneRepository, dominioRepository,
            unitaOperativaRepository, tipoVersamentoDominioRepository, tributoRepository, ibanAccreditoRepository);

    private void confAnagraficaBase() {
        ApplicazioneEntity applicazione = new ApplicazioneEntity();
        applicazione.setCodApplicazione("A2A-PROVA");
        when(applicazioneRepository.findById(eq(ID_APPLICAZIONE))).thenReturn(Optional.of(applicazione));

        DominioEntity dominio = new DominioEntity();
        dominio.setCodDominio("12345678901");
        dominio.setRagioneSociale("Comune di Prova");
        when(dominioRepository.findById(eq(ID_DOMINIO))).thenReturn(Optional.of(dominio));
    }

    private static VocePendenza voceEntrata() {
        VocePendenza voce = new VocePendenza();
        voce.setIdVocePendenza("voce-1");
        voce.setImporto(10.01);
        voce.setDescrizione("TARI 2026 saldo");
        voce.setIndice(1);
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);
        voce.setIdIbanAccredito(55L);
        voce.setTipoContabilita(it.govpay.common.entity.TipoContabilita.CAPITOLO);
        voce.setCodiceContabilita("CAP-123");
        return voce;
    }

    private static Pendenza pendenzaBase(StatoPendenza stato, StatoPagamento statoPagamento, boolean anomalo) {
        PosizioneDebitoria posizione = new PosizioneDebitoria();
        posizione.setCodDocumento("DOC-REALE-1");
        posizione.setDescrizione("Sanzione CdS n. abc00000");
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(TipoSoggetto.F);
        soggetto.setIdentificativo("RSSMRA80A01H501U");
        soggetto.setAnagrafica("Mario Rossi");
        posizione.addSoggettoDebitore(soggetto);

        OpzionePagamento opzione = new OpzionePagamento();
        opzione.setTipologia(TipologiaOpzionePagamento.PIANO_RATEALE);
        posizione.addOpzionePagamento(opzione);

        Pendenza pendenza = new Pendenza();
        pendenza.setIdApplicazione(ID_APPLICAZIONE);
        pendenza.setIdDominio(ID_DOMINIO);
        pendenza.setIdPendenza("pend-1");
        pendenza.setImporto(10.01);
        pendenza.setNumeroRata(2);
        pendenza.setDataCreazione(OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        pendenza.setStato(stato);
        pendenza.setStatoPagamento(statoPagamento);
        pendenza.setAnomalo(anomalo);
        pendenza.setCausaleVersamento(CausaleVersamentoCodec.encode("Causale di prova"));
        pendenza.addVocePendenza(voceEntrata());
        opzione.addPendenza(pendenza);
        return pendenza;
    }

    @Test
    @DisplayName("mappa i campi principali: stato ESEGUITO+INCASSATO -> INCASSATA, documento rateale, soggetto, voce, causale decodificata")
    void mappaCampiPrincipali() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.ESEGUITO, StatoPagamento.INCASSATO, false);

        var dto = mapper.toDto(entity, List.of(), List.of());

        assertThat(dto.getIdA2A()).isEqualTo("A2A-PROVA");
        assertThat(dto.getIdPendenza()).isEqualTo("pend-1");
        assertThat(dto.getDominio().getIdDominio()).isEqualTo("12345678901");
        assertThat(dto.getDominio().getRagioneSociale()).isEqualTo("Comune di Prova");
        assertThat(dto.getStato()).isEqualTo(it.govpay.pendenze.v2.api.model.StatoPendenza.INCASSATA);
        assertThat(dto.getCausale()).isEqualTo("Causale di prova");
        assertThat(dto.getSoggettoPagatore().getIdentificativo()).isEqualTo("RSSMRA80A01H501U");
        assertThat(dto.getDocumento().getIdentificativo()).isEqualTo("DOC-REALE-1");
        assertThat(dto.getDocumento().getDescrizione()).isEqualTo("Sanzione CdS n. abc00000");
        assertThat(dto.getDocumento().getRata()).isEqualTo(2);
        assertThat(dto.getDocumento().getSoglia()).isNull();
        assertThat(dto.getVoci()).hasSize(1);
        assertThat(dto.getVoci().get(0).getIbanAccredito()).isNull(); // repository non configurato in questo test
        assertThat(dto.getTipo()).isEqualTo(it.govpay.pendenze.v2.api.model.TipoPendenzaTipologia.DOVUTO);
    }

    @Test
    @DisplayName("un documento sintetizzato dalla migrazione (prefisso MIGR-) non viene mai mostrato")
    void documentoSintetizzatoOmesso() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        entity.getOpzionePagamento().getPosizioneDebitoria().setCodDocumento("MIGR-42");

        var dto = mapper.toDto(entity, List.of(), List.of());

        assertThat(dto.getDocumento()).isNull();
    }

    @Test
    @DisplayName("ANNULLATO -> ANNULLATA, ma anomalo=true prevale sempre su qualunque altro stato")
    void anomaloPrevaleSuAnnullato() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.ANNULLATO, StatoPagamento.NON_PAGATO, true);

        var dto = mapper.toDto(entity, List.of(), List.of());

        assertThat(dto.getStato()).isEqualTo(it.govpay.pendenze.v2.api.model.StatoPendenza.ANOMALA);
    }

    @Test
    @DisplayName("NON_ESEGUITO con dataScadenzaAvviso nel passato -> SCADUTA, altrimenti NON_ESEGUITA")
    void nonEseguitoScadutaOMeno() {
        confAnagraficaBase();
        Pendenza passata = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        passata.setDataScadenzaAvviso(OffsetDateTime.now().minusDays(1));
        assertThat(mapper.toDto(passata, List.of(), List.of()).getStato())
                .isEqualTo(it.govpay.pendenze.v2.api.model.StatoPendenza.SCADUTA);

        Pendenza futura = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        futura.setDataScadenzaAvviso(OffsetDateTime.now().plusDays(1));
        assertThat(mapper.toDto(futura, List.of(), List.of()).getStato())
                .isEqualTo(it.govpay.pendenze.v2.api.model.StatoPendenza.NON_ESEGUITA);
    }

    @Test
    @DisplayName("segnalazioni: formato legacy \"codice#descrizione|...\" decodificato in piu' elementi")
    void segnalazioniDecodificate() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        entity.setAnomalie("0013#Pagamento rendicontato senza RPT|0099#Altra segnalazione");

        var dto = mapper.toDto(entity, List.of(), List.of());

        assertThat(dto.getSegnalazioni()).hasSize(2);
        assertThat(dto.getSegnalazioni().get(0).getCodice()).isEqualTo("0013");
        assertThat(dto.getSegnalazioni().get(0).getDescrizione()).isEqualTo("Pagamento rendicontato senza RPT");
        assertThat(dto.getSegnalazioni().get(1).getCodice()).isEqualTo("0099");
    }

    @Test
    @DisplayName("documento con opzione SOLUZIONE_UNICA_ENTRO riporta la soglia con giorni")
    void documentoConSogliaEntro() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        entity.getOpzionePagamento().setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO);
        entity.getOpzionePagamento().setGiorni(5);

        var dto = mapper.toDto(entity, List.of(), List.of());

        assertThat(dto.getDocumento().getSoglia().getTipo())
                .isEqualTo(it.govpay.pendenze.v2.api.model.TipoSogliaVincoloPagamento.ENTRO);
        assertThat(dto.getDocumento().getSoglia().getGiorni()).isEqualTo(5);
        assertThat(dto.getDocumento().getRata()).isNull();
    }

    @Test
    @DisplayName("voce BOLLO riporta tipoBollo/hashDocumento/provinciaResidenza, non codEntrata/ibanAccredito")
    void voceBollo() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        VocePendenza bollo = new VocePendenza();
        bollo.setIdVocePendenza("voce-bollo");
        bollo.setImporto(16.00);
        bollo.setDescrizione("Marca da bollo digitale");
        bollo.setIndice(2);
        bollo.setTipoBollo("01");
        bollo.setHashDocumento("aGFzaA==");
        bollo.setProvinciaResidenza("RM");
        entity.addVocePendenza(bollo);

        var dto = mapper.toDto(entity, List.of(), List.of());

        var voceDto = dto.getVoci().stream().filter(v -> "voce-bollo".equals(v.getIdVocePendenza())).findFirst()
                .orElseThrow();
        assertThat(voceDto.getTipoBollo().getValue()).isEqualTo("01");
        assertThat(voceDto.getHashDocumento()).isEqualTo("aGFzaA==");
        assertThat(voceDto.getProvinciaResidenza()).isEqualTo("RM");
        assertThat(voceDto.getCodEntrata()).isNull();
        assertThat(voceDto.getIbanAccredito()).isNull();
    }

    @Test
    @DisplayName("allegati: nome/tipo/descrizione dall'entity, contenuto come URL /allegati/{id}")
    void allegatiMappatiConUrlContenuto() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.NON_ESEGUITO, StatoPagamento.NON_PAGATO, false);
        AllegatoEntity allegato = new AllegatoEntity();
        allegato.setId(42L);
        allegato.setNome("determina.pdf");
        allegato.setTipo("application/pdf");
        allegato.setDescrizione("Determina di accertamento");

        var dto = mapper.toDto(entity, List.of(), List.of(allegato));

        assertThat(dto.getAllegati()).hasSize(1);
        var allegatoDto = dto.getAllegati().get(0);
        assertThat(allegatoDto.getNome()).isEqualTo("determina.pdf");
        assertThat(allegatoDto.getTipo()).isEqualTo("application/pdf");
        assertThat(allegatoDto.getDescrizione()).isEqualTo("Determina di accertamento");
        assertThat(allegatoDto.getContenuto()).isEqualTo("/allegati/42");
    }

    @Test
    @DisplayName("toIndexDto: stessi campi core di toDto, ma rpp/pagamenti sono URL filtrati per idA2A+idPendenza")
    void toIndexDtoUrlRppEPagamenti() {
        confAnagraficaBase();
        Pendenza entity = pendenzaBase(StatoPendenza.ESEGUITO, StatoPagamento.INCASSATO, false);

        var dto = mapper.toIndexDto(entity);

        assertThat(dto.getIdA2A()).isEqualTo("A2A-PROVA");
        assertThat(dto.getIdPendenza()).isEqualTo("pend-1");
        assertThat(dto.getStato()).isEqualTo(it.govpay.pendenze.v2.api.model.StatoPendenza.INCASSATA);
        assertThat(dto.getRpp()).isEqualTo("/rpp?idA2A=A2A-PROVA&idPendenza=pend-1");
        assertThat(dto.getPagamenti()).isEqualTo("/pagamenti?idA2A=A2A-PROVA&idPendenza=pend-1");
    }
}
