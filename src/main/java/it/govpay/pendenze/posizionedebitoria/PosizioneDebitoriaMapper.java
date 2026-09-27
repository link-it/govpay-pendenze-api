package it.govpay.pendenze.posizionedebitoria;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.api.model.Dettaglio;
import it.govpay.pendenze.api.model.DettaglioContabileCivilistico;
import it.govpay.pendenze.api.model.DettaglioContabileCorrispettivoDL118;
import it.govpay.pendenze.api.model.DettaglioContabileImportoNotifica;
import it.govpay.pendenze.api.model.DettaglioContabileIncassoTipico;
import it.govpay.pendenze.api.model.NuovaOpzionePagamento;
import it.govpay.pendenze.api.model.NuovaOpzionePagamentoPianoRateale;
import it.govpay.pendenze.api.model.NuovaOpzionePagamentoSoluzioneUnica;
import it.govpay.pendenze.api.model.NuovaOpzionePagamentoSoluzioneUnicaEntro;
import it.govpay.pendenze.api.model.NuovaOpzionePagamentoSoluzioneUnicaOltre;
import it.govpay.pendenze.api.model.NuovaPendenza;
import it.govpay.pendenze.api.model.NuovaPosizioneDebitoria;
import it.govpay.pendenze.api.model.NuovaVocePendenza;
import it.govpay.pendenze.api.model.NuovaVocePendenzaBollo;
import it.govpay.pendenze.api.model.NuovaVocePendenzaEntrata;
import it.govpay.pendenze.api.model.NuovaVocePendenzaRiferimentoEntrata;
import it.govpay.pendenze.api.model.OpzionePagamentoIndex;
import it.govpay.pendenze.api.model.OpzionePagamentoIndexPianoRateale;
import it.govpay.pendenze.api.model.OpzionePagamentoIndexSoluzioneUnica;
import it.govpay.pendenze.api.model.OpzionePagamentoIndexSoluzioneUnicaEntro;
import it.govpay.pendenze.api.model.OpzionePagamentoIndexSoluzioneUnicaOltre;
import it.govpay.pendenze.api.model.OpzionePagamentoPianoRateale;
import it.govpay.pendenze.api.model.OpzionePagamentoSoluzioneUnica;
import it.govpay.pendenze.api.model.OpzionePagamentoSoluzioneUnicaEntro;
import it.govpay.pendenze.api.model.OpzionePagamentoSoluzioneUnicaOltre;
import it.govpay.pendenze.api.model.PendenzaOpzionePagamento;
import it.govpay.pendenze.api.model.Soggetto;
import it.govpay.pendenze.api.model.StatoOpzionePagamento;
import it.govpay.pendenze.api.model.StatoPendenza;
import it.govpay.pendenze.api.model.TipoSoggetto;
import it.govpay.pendenze.api.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.TipoVersamentoDominio;
import it.govpay.pendenze.entity.UnitaOperativa;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
import it.govpay.pendenze.repository.TipoVersamentoDominioRepository;
import it.govpay.pendenze.repository.UnitaOperativaRepository;
import it.govpay.pendenze.web.AnagraficaNonTrovataException;

/**
 * Converte tra i bean REST dello YAML v3 e l'aggregato {@code PosizioneDebitoria} di
 * {@code govpay-common-pendenze}, per {@code POST /posizioni-debitorie/{idA2A}}.
 *
 * <p><b>Chi risolve le anagrafiche esterne.</b> {@code idA2A}/{@code idDominio}/
 * {@code idUnitaOperativa}/{@code idTipoPendenza} sono sempre codici testuali, mai id
 * numerici interni (convenzione GovPay, verificata nel legacy per ciascuno di essi — vedi
 * {@code proposta-modello-nativo-v3.md}). La libreria tratta questi riferimenti solo come FK
 * piatte {@code Long} (M4) e non li risolve mai da sola: e' compito di questo mapper, che per
 * questo ha bisogno di accesso ai repository, non di un mapper "puro" senza I/O come
 * {@code DominioMapper} di {@code govpay-console-api}.</p>
 *
 * <p><b>Collisioni di nome</b>: {@code PosizioneDebitoria}/{@code OpzionePagamento}/
 * {@code TipologiaOpzionePagamento}/{@code StatoOpzionePagamento}/{@code StatoPendenza}/
 * {@code TipoSoggetto} esistono identici sia in {@code it.govpay.pendenze.entity}/
 * {@code it.govpay.pendenze.model} (libreria) sia in {@code it.govpay.pendenze.api.model}
 * (generati dallo YAML). Import "a nudo" per i tipi DTO (piu' numerosi in questo file),
 * nome completo inline per i tipi della libreria che collidono.</p>
 */
@Component
public class PosizioneDebitoriaMapper {

    private final ApplicazioneRepository applicazioneRepository;
    private final DominioRepository dominioRepository;
    private final UnitaOperativaRepository unitaOperativaRepository;
    private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository;
    private final Clock clock;

    public PosizioneDebitoriaMapper(ApplicazioneRepository applicazioneRepository,
            DominioRepository dominioRepository, UnitaOperativaRepository unitaOperativaRepository,
            TipoVersamentoDominioRepository tipoVersamentoDominioRepository, Clock clock) {
        this.applicazioneRepository = applicazioneRepository;
        this.dominioRepository = dominioRepository;
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.tipoVersamentoDominioRepository = tipoVersamentoDominioRepository;
        this.clock = clock;
    }

    // ── Risoluzione anagrafiche esterne ─────────────────────────────────────────

    /**
     * @throws AnagraficaNonTrovataException se {@code idA2A} non corrisponde a nessuna
     *                                        applicazione
     */
    public Long risolviIdApplicazione(String idA2A) {
        return applicazioneRepository.findByCodApplicazione(idA2A)
                .map(ApplicazioneEntity::getId)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna applicazione con idA2A [" + idA2A
                        + "]"));
    }

    private String risolviCodApplicazione(Long idApplicazione) {
        return applicazioneRepository.findById(idApplicazione).map(ApplicazioneEntity::getCodApplicazione)
                .orElse(null);
    }

    /**
     * @throws AnagraficaNonTrovataException se {@code idDominio} non corrisponde a nessun
     *                                        dominio
     */
    public Long risolviIdDominio(String idDominio) {
        return dominioRepository.findByCodDominio(idDominio)
                .map(DominioEntity::getId)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun dominio con idDominio [" + idDominio
                        + "]"));
    }

    private String risolviCodDominio(Long idDominio) {
        return idDominio == null ? null
                : dominioRepository.findById(idDominio).map(DominioEntity::getCodDominio).orElse(null);
    }

    /**
     * @return {@code null} se {@code idUnitaOperativa} e' {@code null} (campo opzionale)
     * @throws AnagraficaNonTrovataException se {@code idUnitaOperativa} e' valorizzato ma
     *                                        non corrisponde a nessuna unita' operativa del
     *                                        dominio indicato
     */
    private Long risolviIdUnitaOperativa(Long idDominio, String idUnitaOperativa) {
        if (idUnitaOperativa == null) {
            return null;
        }
        return unitaOperativaRepository.findByIdDominioAndCodUo(idDominio, idUnitaOperativa)
                .map(UnitaOperativa::getId)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna unita' operativa con idUnitaOperativa ["
                        + idUnitaOperativa + "] per il dominio [id:" + idDominio + "]"));
    }

    private String risolviCodUnitaOperativa(Long idUnitaOperativa) {
        return idUnitaOperativa == null ? null
                : unitaOperativaRepository.findById(idUnitaOperativa).map(UnitaOperativa::getCodUo).orElse(null);
    }

    /**
     * @throws AnagraficaNonTrovataException se {@code idTipoPendenza} non e' configurato per
     *                                        il dominio indicato (anche se esiste nel
     *                                        catalogo globale: nessun fallback su un dominio
     *                                        di default, stesso comportamento del legacy —
     *                                        vedi Javadoc di
     *                                        {@link TipoVersamentoDominioRepository#findByCodTipoVersamentoAndIdDominio})
     */
    private TipoVersamentoDominio risolviTipoVersamentoDominio(String idTipoPendenza, Long idDominio) {
        return tipoVersamentoDominioRepository.findByCodTipoVersamentoAndIdDominio(idTipoPendenza, idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun tipo pendenza [" + idTipoPendenza
                        + "] configurato per il dominio [id:" + idDominio + "]"));
    }

    /**
     * {@code findByIdFetchTipoVersamento}, non {@code findById} (bug del lead, 2026-09-27):
     * senza il {@code join fetch}, {@code tvd.getTipoVersamento()} resta un proxy LAZY —
     * qui viene tipicamente letto da {@link #toDto} dopo che la transazione di scrittura di
     * {@code PosizioneDebitoriaService#crea} e' gia' tornata (con {@code open-in-view=false}
     * la sessione Hibernate è già chiusa), sollevando {@code LazyInitializationException}
     * (500 anziché la risposta 201 — la posizione restava comunque salvata, riproducibile
     * anche solo rileggendola con un secondo tentativo, che riceveva 409).
     */
    private String risolviCodTipoVersamento(Long idTipoVersamentoDominio) {
        return idTipoVersamentoDominio == null ? null
                : tipoVersamentoDominioRepository.findByIdFetchTipoVersamento(idTipoVersamentoDominio)
                        .map(tvd -> tvd.getTipoVersamento().getCodTipoVersamento())
                        .orElse(null);
    }

    // ── Richiesta -> entita' ─────────────────────────────────────────────────

    public it.govpay.pendenze.entity.PosizioneDebitoria toEntity(String idA2A, NuovaPosizioneDebitoria dto) {
        Long idApplicazione = risolviIdApplicazione(idA2A);
        Long idDominio = risolviIdDominio(dto.getIdDominio());
        Long idUnitaOperativa = risolviIdUnitaOperativa(idDominio, dto.getIdUnitaOperativa());

        it.govpay.pendenze.entity.PosizioneDebitoria posizione = new it.govpay.pendenze.entity.PosizioneDebitoria();
        posizione.setIdApplicazione(idApplicazione);
        posizione.setIdPosizioneDebitoria(dto.getIdPosizioneDebitoria());
        posizione.setIdDominio(idDominio);
        posizione.setIdUnitaOperativa(idUnitaOperativa);
        posizione.setDescrizione(dto.getDescrizione());
        posizione.setDataPubblicazione(dto.getDataPubblicazione());
        posizione.setNotificaSend(Boolean.TRUE.equals(dto.getNotificaSend()));
        posizione.setNavNotifica(dto.getNavNotifica());

        for (Soggetto soggetto : dto.getSoggettiDebitori()) {
            posizione.addSoggettoDebitore(toSoggettoDebitore(soggetto));
        }
        for (NuovaOpzionePagamento opzione : dto.getOpzioniPagamento()) {
            posizione.addOpzionePagamento(toOpzionePagamento(opzione, idDominio, idApplicazione));
        }
        return posizione;
    }

    private SoggettoDebitore toSoggettoDebitore(Soggetto dto) {
        SoggettoDebitore soggetto = new SoggettoDebitore();
        soggetto.setTipo(it.govpay.pendenze.model.TipoSoggetto.valueOf(dto.getTipo().name()));
        soggetto.setIdentificativo(dto.getIdentificativo());
        soggetto.setAnagrafica(dto.getAnagrafica());
        soggetto.setIndirizzo(dto.getIndirizzo());
        soggetto.setCivico(dto.getCivico());
        soggetto.setCap(dto.getCap());
        soggetto.setLocalita(dto.getLocalita());
        soggetto.setProvincia(dto.getProvincia());
        soggetto.setNazione(dto.getNazione());
        soggetto.setEmail(dto.getEmail());
        return soggetto;
    }

    private it.govpay.pendenze.entity.OpzionePagamento toOpzionePagamento(NuovaOpzionePagamento dto,
            Long idDominioPosizione, Long idApplicazione) {
        it.govpay.pendenze.entity.OpzionePagamento opzione = new it.govpay.pendenze.entity.OpzionePagamento();
        opzione.setTipologia(it.govpay.pendenze.model.TipologiaOpzionePagamento.valueOf(dto.getTipologia().name()));

        java.util.List<NuovaPendenza> pendenze;
        if (dto instanceof NuovaOpzionePagamentoPianoRateale o) {
            opzione.setDataInizioValidita(o.getDataInizioValidita());
            opzione.setDataScadenza(o.getDataScadenza());
            pendenze = o.getPendenze();
        } else if (dto instanceof NuovaOpzionePagamentoSoluzioneUnica o) {
            opzione.setDataInizioValidita(o.getDataInizioValidita());
            opzione.setDataScadenza(o.getDataScadenza());
            pendenze = o.getPendenze();
        } else if (dto instanceof NuovaOpzionePagamentoSoluzioneUnicaEntro o) {
            opzione.setGiorni(o.getGiorni());
            opzione.setDataInizioValidita(o.getDataInizioValidita());
            opzione.setDataScadenza(o.getDataScadenza());
            pendenze = o.getPendenze();
        } else if (dto instanceof NuovaOpzionePagamentoSoluzioneUnicaOltre o) {
            opzione.setGiorni(o.getGiorni());
            opzione.setDataInizioValidita(o.getDataInizioValidita());
            opzione.setDataScadenza(o.getDataScadenza());
            pendenze = o.getPendenze();
        } else {
            throw new IllegalArgumentException("tipo di NuovaOpzionePagamento non gestito: " + dto.getClass());
        }

        for (NuovaPendenza pendenza : pendenze) {
            opzione.addPendenza(toPendenza(pendenza, idDominioPosizione, idApplicazione));
        }
        return opzione;
    }

    private it.govpay.pendenze.entity.Pendenza toPendenza(NuovaPendenza dto, Long idDominioPosizione,
            Long idApplicazione) {
        it.govpay.pendenze.entity.Pendenza pendenza = new it.govpay.pendenze.entity.Pendenza();
        pendenza.setIdApplicazione(idApplicazione);
        pendenza.setIdPendenza(dto.getIdPendenza());

        TipoVersamentoDominio tipoVersamentoDominio = risolviTipoVersamentoDominio(dto.getIdTipoPendenza(),
                idDominioPosizione);
        pendenza.setIdTipoPendenza(tipoVersamentoDominio.getId());
        pendenza.setIdTipoVersamento(tipoVersamentoDominio.getTipoVersamento().getId());
        // Necessaria a GeneratoreIuvStandard per risolvere %(p)/%(t) nel prefisso IUV di
        // dominio (bug del lead, 2026-09-27: dimenticata nel primo giro — la creazione
        // falliva con 500 per ogni dominio il cui prefisso usa quel placeholder).
        pendenza.setCodificaIuvTipoPendenza(tipoVersamentoDominio.getCodificaIuvEffettiva());

        pendenza.setImporto(dto.getImporto().doubleValue());
        pendenza.setNumeroAvviso(dto.getNumeroAvviso());
        pendenza.setDataValidita(aInizioGiorno(dto.getDataValidita()));
        pendenza.setDataScadenzaAvviso(aInizioGiorno(dto.getDataScadenzaAvviso()));

        for (NuovaVocePendenza voce : dto.getVoci()) {
            pendenza.addVocePendenza(toVocePendenza(voce));
        }
        return pendenza;
    }

    /**
     * {@code Pendenza.dataValidita}/{@code dataScadenzaAvviso} sono {@code TIMESTAMP} in
     * produzione (non {@code DATE} — vedi Javadoc di classe di {@code Pendenza}), lo YAML v3
     * li espone come semplice {@code date}: mezzanotte nel fuso della libreria (stesso
     * {@link Clock} di {@code PendenzeAutoConfiguration}), non quello di sistema.
     */
    private java.time.OffsetDateTime aInizioGiorno(LocalDate data) {
        return data == null ? null : data.atStartOfDay(clock.getZone()).toOffsetDateTime();
    }

    private VocePendenza toVocePendenza(NuovaVocePendenza dto) {
        VocePendenza voce = new VocePendenza();
        // Il servizio non lo valorizza (a differenza di OpzionePagamento/Pendenza): sta al
        // chiamante — vedi Javadoc del campo VocePendenza.stato.
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);

        if (dto instanceof NuovaVocePendenzaRiferimentoEntrata v) {
            voce.setTipoRiferimento(TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA);
            voce.setCodEntrata(v.getCodEntrata());
            popolaCampiComuni(voce, v.getIdVocePendenza(), v.getImporto(), v.getDescrizione(), v.getIdDominio());
            for (Dettaglio dettaglio : v.getDettaglioContabile()) {
                voce.getDettaglioContabile().add(toDettaglioContabile(dettaglio));
            }
        } else if (dto instanceof NuovaVocePendenzaEntrata v) {
            voce.setTipoRiferimento(TipoRiferimentoVocePendenza.ENTRATA);
            voce.setIbanAccredito(v.getIbanAccredito());
            voce.setIbanAppoggio(v.getIbanAppoggio());
            voce.setTassonomia(v.getTassonomia());
            popolaCampiComuni(voce, v.getIdVocePendenza(), v.getImporto(), v.getDescrizione(), v.getIdDominio());
            for (Dettaglio dettaglio : v.getDettaglioContabile()) {
                voce.getDettaglioContabile().add(toDettaglioContabile(dettaglio));
            }
        } else if (dto instanceof NuovaVocePendenzaBollo v) {
            voce.setTipoRiferimento(TipoRiferimentoVocePendenza.BOLLO);
            voce.setTipoBollo(v.getTipoBollo().getValue());
            voce.setHashDocumento(v.getHashDocumento());
            voce.setProvinciaResidenza(v.getProvinciaResidenza());
            voce.setTassonomia(v.getTassonomia());
            popolaCampiComuni(voce, v.getIdVocePendenza(), v.getImporto(), v.getDescrizione(), v.getIdDominio());
            // Bollo non ammette dettaglioContabile (vincolo dello schema Bollo dello YAML).
        } else {
            throw new IllegalArgumentException("tipo di NuovaVocePendenza non gestito: " + dto.getClass());
        }
        return voce;
    }

    /**
     * {@code idDominio} qui non viene mai materializzato al default (a differenza di
     * {@code Pendenza.idDominio}): {@code null} resta {@code null}, e' compito di
     * {@code PosizioneDebitoriaService#crea} valorizzarlo con quello della posizione se il
     * chiamante non ha indicato un override esplicito — vedi Javadoc di
     * {@code VocePendenza.idDominio}.
     */
    private void popolaCampiComuni(VocePendenza voce, String idVocePendenza, BigDecimal importo, String descrizione,
            String idDominioVoce) {
        voce.setIdVocePendenza(idVocePendenza);
        voce.setImporto(importo.doubleValue());
        voce.setDescrizione(descrizione);
        if (idDominioVoce != null) {
            voce.setIdDominio(risolviIdDominio(idDominioVoce));
        }
    }

    private DettaglioContabile toDettaglioContabile(Dettaglio dto) {
        if (dto instanceof DettaglioContabileCorrispettivoDL118 d) {
            return new DettaglioContabile.CorrispettivoDl118(d.getAnnoCompetenza(), d.getCodiceUfficio(),
                    d.getCapitolo(), d.getAccertamento(), d.getArticolo(), d.getPianoFinanziario5Livello(),
                    bigDecimal(d.getImporto()));
        }
        if (dto instanceof DettaglioContabileIncassoTipico d) {
            String emissioneFattura = d.getEmissioneFattura() != null ? d.getEmissioneFattura().getValue() : null;
            return new DettaglioContabile.IncassoTipico(d.getAnnoCompetenza(), d.getCodiceUfficio(),
                    d.getTipoIncasso(), emissioneFattura, d.getNrDocumento(), bigDecimal(d.getImporto()));
        }
        if (dto instanceof DettaglioContabileCivilistico d) {
            return new DettaglioContabile.Civilistico(d.getAnnoCompetenza(), d.getCodiceUfficio(), d.getConto(),
                    d.getCommessa(), d.getNrDocumento(), bigDecimal(d.getImporto()));
        }
        if (dto instanceof DettaglioContabileImportoNotifica d) {
            return new DettaglioContabile.SpeseNotifica(bigDecimal(d.getImporto()));
        }
        throw new IllegalArgumentException("tipo di Dettaglio non gestito: " + dto.getClass());
    }

    private static BigDecimal bigDecimal(Double valore) {
        return valore == null ? null : BigDecimal.valueOf(valore);
    }

    // ── Entita' -> risposta ──────────────────────────────────────────────────

    /**
     * Costruisce la risposta di {@code POST /posizioni-debitorie/{idA2A}} ("stesso contenuto
     * di una GET sulla risorsa appena creata"). Non include le voci di ciascuna pendenza:
     * {@code PendenzaOpzionePagamento} (a differenza dello schema {@code Pendenza}, usato
     * altrove) non le espone — restano visibili solo dal dettaglio della singola pendenza,
     * sviluppo successivo.
     */
    public it.govpay.pendenze.api.model.PosizioneDebitoria toDto(
            it.govpay.pendenze.entity.PosizioneDebitoria entity) {
        it.govpay.pendenze.api.model.PosizioneDebitoria dto = new it.govpay.pendenze.api.model.PosizioneDebitoria();
        dto.setIdA2A(risolviCodApplicazione(entity.getIdApplicazione()));
        dto.setIdPosizioneDebitoria(entity.getIdPosizioneDebitoria());
        dto.setIdDominio(risolviCodDominio(entity.getIdDominio()));
        dto.setIdUnitaOperativa(risolviCodUnitaOperativa(entity.getIdUnitaOperativa()));
        dto.setDescrizione(entity.getDescrizione());
        dto.setDataPubblicazione(entity.getDataPubblicazione());
        dto.setNotificaSend(entity.isNotificaSend());
        dto.setNavNotifica(entity.getNavNotifica());
        for (SoggettoDebitore soggetto : entity.getSoggettiDebitori()) {
            dto.addSoggettiDebitoriItem(toSoggettoDto(soggetto));
        }
        for (it.govpay.pendenze.entity.OpzionePagamento opzione : entity.getOpzioniPagamento()) {
            dto.addOpzioniPagamentoItem(toOpzionePagamentoDto(opzione));
        }
        return dto;
    }

    /**
     * Costruisce un elemento di {@code GET /posizioni-debitorie/{idA2A}} (ricerca per
     * debitore): {@code PosizioneDebitoriaIndex}, senza {@code opzioniPagamento} — coerente
     * con lo YAML, che riserva l'elenco completo delle opzioni al solo dettaglio puntuale.
     */
    public it.govpay.pendenze.api.model.PosizioneDebitoriaIndex toIndexDto(
            it.govpay.pendenze.entity.PosizioneDebitoria entity) {
        it.govpay.pendenze.api.model.PosizioneDebitoriaIndex dto =
                new it.govpay.pendenze.api.model.PosizioneDebitoriaIndex();
        dto.setIdA2A(risolviCodApplicazione(entity.getIdApplicazione()));
        dto.setIdPosizioneDebitoria(entity.getIdPosizioneDebitoria());
        dto.setIdDominio(risolviCodDominio(entity.getIdDominio()));
        dto.setIdUnitaOperativa(risolviCodUnitaOperativa(entity.getIdUnitaOperativa()));
        dto.setDescrizione(entity.getDescrizione());
        for (SoggettoDebitore soggetto : entity.getSoggettiDebitori()) {
            dto.addSoggettiDebitoriItem(toSoggettoDto(soggetto));
        }
        dto.setDataPubblicazione(entity.getDataPubblicazione());
        dto.setNotificaSend(entity.isNotificaSend());
        dto.setNavNotifica(entity.getNavNotifica());
        return dto;
    }

    /**
     * Bug del lead, 2026-09-27: {@code toDto} non copiava affatto {@code soggettiDebitori} —
     * una posizione con un solo debitore tornava con {@code "soggettiDebitori":[]},
     * contrario al {@code minItems: 1} dello YAML.
     */
    private Soggetto toSoggettoDto(SoggettoDebitore entity) {
        Soggetto dto = new Soggetto();
        dto.setTipo(TipoSoggetto.valueOf(entity.getTipo().name()));
        dto.setIdentificativo(entity.getIdentificativo());
        dto.setAnagrafica(entity.getAnagrafica());
        dto.setIndirizzo(entity.getIndirizzo());
        dto.setCivico(entity.getCivico());
        dto.setCap(entity.getCap());
        dto.setLocalita(entity.getLocalita());
        dto.setProvincia(entity.getProvincia());
        dto.setNazione(entity.getNazione());
        dto.setEmail(entity.getEmail());
        return dto;
    }

    private it.govpay.pendenze.api.model.OpzionePagamento toOpzionePagamentoDto(
            it.govpay.pendenze.entity.OpzionePagamento entity) {
        java.util.List<PendenzaOpzionePagamento> pendenze = entity.getPendenze().stream()
                .map(this::toPendenzaOpzionePagamentoDto)
                .toList();
        StatoOpzionePagamento stato = StatoOpzionePagamento.valueOf(entity.getStato().name());
        switch (entity.getTipologia()) {
            case PIANO_RATEALE -> {
                OpzionePagamentoPianoRateale dto = new OpzionePagamentoPianoRateale();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.PIANO_RATEALE);
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                dto.setPendenze(pendenze);
                return dto;
            }
            case SOLUZIONE_UNICA -> {
                OpzionePagamentoSoluzioneUnica dto = new OpzionePagamentoSoluzioneUnica();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                dto.setPendenze(pendenze);
                return dto;
            }
            case SOLUZIONE_UNICA_ENTRO -> {
                OpzionePagamentoSoluzioneUnicaEntro dto = new OpzionePagamentoSoluzioneUnicaEntro();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO);
                dto.setGiorni(entity.getGiorni());
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                dto.setPendenze(pendenze);
                return dto;
            }
            case SOLUZIONE_UNICA_OLTRE -> {
                OpzionePagamentoSoluzioneUnicaOltre dto = new OpzionePagamentoSoluzioneUnicaOltre();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE);
                dto.setGiorni(entity.getGiorni());
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                dto.setPendenze(pendenze);
                return dto;
            }
        }
        throw new IllegalStateException("tipologia non gestita: " + entity.getTipologia());
    }

    private OpzionePagamentoIndex toOpzionePagamentoIndexDto(it.govpay.pendenze.entity.OpzionePagamento entity) {
        StatoOpzionePagamento stato = StatoOpzionePagamento.valueOf(entity.getStato().name());
        switch (entity.getTipologia()) {
            case PIANO_RATEALE -> {
                OpzionePagamentoIndexPianoRateale dto = new OpzionePagamentoIndexPianoRateale();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.PIANO_RATEALE);
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                return dto;
            }
            case SOLUZIONE_UNICA -> {
                OpzionePagamentoIndexSoluzioneUnica dto = new OpzionePagamentoIndexSoluzioneUnica();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA);
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                return dto;
            }
            case SOLUZIONE_UNICA_ENTRO -> {
                OpzionePagamentoIndexSoluzioneUnicaEntro dto = new OpzionePagamentoIndexSoluzioneUnicaEntro();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO);
                dto.setGiorni(entity.getGiorni());
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                return dto;
            }
            case SOLUZIONE_UNICA_OLTRE -> {
                OpzionePagamentoIndexSoluzioneUnicaOltre dto = new OpzionePagamentoIndexSoluzioneUnicaOltre();
                dto.setIdOpzionePagamento(entity.getIdOpzionePagamento());
                dto.setTipologia(TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE);
                dto.setGiorni(entity.getGiorni());
                dto.setStato(stato);
                dto.setDataInizioValidita(entity.getDataInizioValidita());
                dto.setDataScadenza(entity.getDataScadenza());
                return dto;
            }
        }
        throw new IllegalStateException("tipologia non gestita: " + entity.getTipologia());
    }

    private PendenzaOpzionePagamento toPendenzaOpzionePagamentoDto(it.govpay.pendenze.entity.Pendenza entity) {
        PendenzaOpzionePagamento dto = new PendenzaOpzionePagamento();
        dto.setIdA2A(risolviCodApplicazione(entity.getIdApplicazione()));
        dto.setIdPendenza(entity.getIdPendenza());
        dto.setIdTipoPendenza(risolviCodTipoVersamento(entity.getIdTipoPendenza()));
        dto.setIdDominio(risolviCodDominio(entity.getIdDominio()));
        dto.setStato(StatoPendenza.valueOf(entity.getStato().name()));
        dto.setIuv(entity.getIuv());
        dto.setDataPagamento(entity.getDataPagamento() == null ? null : entity.getDataPagamento().toLocalDate());
        dto.setOpzionePagamento(toOpzionePagamentoIndexDto(entity.getOpzionePagamento()));
        dto.setNumeroRata(entity.getNumeroRata());
        dto.setImporto(BigDecimal.valueOf(entity.getImporto()));
        dto.setNumeroAvviso(entity.getNumeroAvviso());
        dto.setDataCaricamento(entity.getDataCaricamento());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenzaAvviso(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        return dto;
    }
}
