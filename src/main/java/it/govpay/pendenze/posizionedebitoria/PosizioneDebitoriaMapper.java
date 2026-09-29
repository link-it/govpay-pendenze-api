package it.govpay.pendenze.posizionedebitoria;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TipoTributoEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.TributoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.repository.IbanAccreditoRepository;
import it.govpay.common.repository.TipoTributoRepository;
import it.govpay.common.repository.TipoVersamentoDominioRepository;
import it.govpay.common.repository.TributoRepository;
import it.govpay.common.repository.UnitaOperativaRepository;
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
import it.govpay.pendenze.api.model.PatchOp;
import it.govpay.pendenze.api.model.PendenzaOpzionePagamento;
import it.govpay.pendenze.api.model.Soggetto;
import it.govpay.pendenze.api.model.StatoOpzionePagamento;
import it.govpay.pendenze.api.model.StatoPendenza;
import it.govpay.pendenze.api.model.TipoSoggetto;
import it.govpay.pendenze.api.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.security.UtenzaTipoVersamentoRepository;
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
    private final TipoTributoRepository tipoTributoRepository;
    private final TributoRepository tributoRepository;
    private final IbanAccreditoRepository ibanAccreditoRepository;
    private final UtenzaRepository utenzaRepository;
    private final UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final jakarta.validation.Validator validator;

    public PosizioneDebitoriaMapper(ApplicazioneRepository applicazioneRepository,
            DominioRepository dominioRepository, UnitaOperativaRepository unitaOperativaRepository,
            TipoVersamentoDominioRepository tipoVersamentoDominioRepository, TipoTributoRepository tipoTributoRepository,
            TributoRepository tributoRepository, IbanAccreditoRepository ibanAccreditoRepository,
            UtenzaRepository utenzaRepository, UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository,
            Clock clock, ObjectMapper objectMapper, jakarta.validation.Validator validator) {
        this.applicazioneRepository = applicazioneRepository;
        this.dominioRepository = dominioRepository;
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.tipoVersamentoDominioRepository = tipoVersamentoDominioRepository;
        this.tipoTributoRepository = tipoTributoRepository;
        this.tributoRepository = tributoRepository;
        this.ibanAccreditoRepository = ibanAccreditoRepository;
        this.utenzaRepository = utenzaRepository;
        this.utenzaTipoVersamentoRepository = utenzaTipoVersamentoRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    // ── Risoluzione anagrafiche esterne ─────────────────────────────────────────

    private String risolviCodApplicazione(Long idApplicazione) {
        return applicazioneRepository.findById(idApplicazione).map(ApplicazioneEntity::getCodApplicazione)
                .orElse(null);
    }

    /**
     * Nessun controllo su {@code abilitato} qui (a differenza di
     * {@link #risolviIdDominioAbilitato}): usato anche per il filtro {@code idDominio} di
     * {@code PendenzaController#findPendenze} (lettura), dove v2 valida solo il FORMATO di
     * idDominio, non lo stato di abilitazione (verificato in
     * {@code v2/controller/PendenzeController#pendenzeGET}, che chiama solo
     * {@code validatoreId.validaIdDominio}).
     *
     * @throws AnagraficaNonTrovataException se {@code idDominio} non corrisponde a nessun
     *                                        dominio
     */
    public Long risolviIdDominio(String idDominio) {
        return dominioRepository.findByCodDominio(idDominio)
                .map(DominioEntity::getId)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun dominio con idDominio [" + idDominio
                        + "]"));
    }

    /**
     * Come {@link #risolviIdDominio}, ma rifiuta anche un dominio disabilitato — solo per i
     * percorsi di SCRITTURA (bug del lead, 2026-09-29: mancava — v2 lo fa in
     * {@code VersamentoUtils}, {@code DOM_001}).
     *
     * @throws AnagraficaNonTrovataException se {@code idDominio} non corrisponde a nessun
     *                                        dominio
     * @throws ValidazioneNonSuperataException se il dominio esiste ma e' disabilitato
     */
    private Long risolviIdDominioAbilitato(String idDominio) {
        DominioEntity dominio = dominioRepository.findByCodDominio(idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun dominio con idDominio [" + idDominio
                        + "]"));
        if (Boolean.FALSE.equals(dominio.getAbilitato())) {
            throw new ValidazioneNonSuperataException("il dominio [" + idDominio + "] non e' abilitato");
        }
        return dominio.getId();
    }

    private String risolviCodDominio(Long idDominio) {
        return idDominio == null ? null
                : dominioRepository.findById(idDominio).map(DominioEntity::getCodDominio).orElse(null);
    }

    /**
     * Rifiuta un'unita' operativa disabilitata (bug del lead, 2026-09-29: mancava, come per
     * {@link #risolviIdTributo}/{@link #risolviIdIban} — v2 lo fa in
     * {@code VersamentoUtils.setUo}, {@code UOP_001}).
     *
     * @return {@code null} se {@code idUnitaOperativa} e' {@code null} (campo opzionale)
     * @throws AnagraficaNonTrovataException se {@code idUnitaOperativa} e' valorizzato ma
     *                                        non corrisponde a nessuna unita' operativa del
     *                                        dominio indicato
     * @throws ValidazioneNonSuperataException se l'unita' operativa esiste per il dominio ma
     *                                          e' disabilitata
     */
    private Long risolviIdUnitaOperativa(Long idDominio, String idUnitaOperativa) {
        if (idUnitaOperativa == null) {
            return null;
        }
        UnitaOperativaEntity unitaOperativa = unitaOperativaRepository
                .findByCodUoAndDominioId(idUnitaOperativa, idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna unita' operativa con idUnitaOperativa ["
                        + idUnitaOperativa + "] per il dominio [id:" + idDominio + "]"));
        if (Boolean.FALSE.equals(unitaOperativa.getAbilitato())) {
            throw new ValidazioneNonSuperataException("l'unita' operativa [" + idUnitaOperativa
                    + "] per il dominio [id:" + idDominio + "] non e' abilitata");
        }
        return unitaOperativa.getId();
    }

    private String risolviCodUnitaOperativa(Long idUnitaOperativa) {
        return idUnitaOperativa == null ? null
                : unitaOperativaRepository.findById(idUnitaOperativa).map(UnitaOperativaEntity::getCodUo)
                        .orElse(null);
    }

    /**
     * {@code codEntrata} della richiesta REST condivide il namespace di
     * {@code tipi_tributo.cod_tributo} (catalogo globale), ma {@code VocePendenza.idTributo}
     * punta a {@code tributi.id} (l'override/configurazione IBAN e contabilita' per QUESTO
     * dominio) — stessa risoluzione a due livelli di {@link #risolviTipoVersamentoDominio}
     * per {@code idTipoPendenza}/{@code idTipoVersamento}.
     *
     * <p>Rifiuta un tributo disabilitato per il dominio (bug del lead, 2026-09-29: mancava —
     * v2 lo fa in {@code VersamentoUtils}, {@code TRB_001}: una POST con un tributo
     * disabilitato tornava 201 invece di essere rifiutata).</p>
     *
     * @throws AnagraficaNonTrovataException se {@code codEntrata} non esiste nel catalogo
     *                                        globale, o non e' configurato per il dominio
     *                                        indicato (nessun fallback su un dominio di
     *                                        default)
     * @throws ValidazioneNonSuperataException se il tributo esiste per il dominio ma e'
     *                                          disabilitato
     */
    private Long risolviIdTributo(String codEntrata, Long idDominio) {
        TipoTributoEntity tipoTributo = tipoTributoRepository.findByCodTributo(codEntrata)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun tipo tributo con codEntrata ["
                        + codEntrata + "]"));
        TributoEntity tributo = tributoRepository.findByDominioIdAndTipoTributoId(idDominio, tipoTributo.getId())
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun tributo [" + codEntrata
                        + "] configurato per il dominio [id:" + idDominio + "]"));
        if (Boolean.FALSE.equals(tributo.getAbilitato())) {
            throw new ValidazioneNonSuperataException("il tributo [" + codEntrata + "] per il dominio [id:"
                    + idDominio + "] non e' abilitato");
        }
        return tributo.getId();
    }

    /**
     * L'IBAN di {@code ENTRATA} e' sempre un IBAN censito in anagrafica (mai una stringa
     * libera — v2 lo referenzia gia' cosi'), per questo dominio.
     *
     * Rifiuta un IBAN disabilitato per il dominio (bug del lead, 2026-09-29: mancava — v2 lo
     * fa in {@code VersamentoUtils}, {@code VER_032}/{@code VER_034} per accredito/appoggio).
     *
     * @return {@code null} se {@code codIban} e' {@code null} (campo opzionale, es.
     *         {@code ibanAppoggio})
     * @throws AnagraficaNonTrovataException se {@code codIban} e' valorizzato ma non censito
     *                                        per il dominio indicato
     * @throws ValidazioneNonSuperataException se l'IBAN esiste per il dominio ma e'
     *                                          disabilitato
     */
    private Long risolviIdIban(String codIban, Long idDominio) {
        if (codIban == null) {
            return null;
        }
        IbanAccreditoEntity iban = ibanAccreditoRepository.findByCodIbanAndDominioId(codIban, idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun IBAN [" + codIban
                        + "] censito per il dominio [id:" + idDominio + "]"));
        if (Boolean.FALSE.equals(iban.getAbilitato())) {
            throw new ValidazioneNonSuperataException("l'IBAN [" + codIban + "] per il dominio [id:" + idDominio
                    + "] non e' abilitato");
        }
        return iban.getId();
    }

    /**
     * Rifiuta un tipo pendenza disabilitato, sia a livello globale sia nell'override per
     * questo dominio (bug del lead, 2026-09-29: mancavano entrambi — v2 lo fa in
     * {@code VersamentoUtils}, {@code TVR_001}/{@code TVD_001}). {@code abilitato} e' colonna
     * condivisa tra {@code TipoVersamentoEntity} (globale, NOT NULL) e
     * {@code TipoVersamentoDominioEntity} (override per dominio, nullable — {@code null}
     * significa "nessun override, eredita il globale", gia' verificato sopra; solo un
     * {@code false} esplicito sull'override disabilita per questo specifico dominio).
     *
     * @throws AnagraficaNonTrovataException se {@code idTipoPendenza} non e' configurato per
     *                                        il dominio indicato (anche se esiste nel
     *                                        catalogo globale: nessun fallback su un dominio
     *                                        di default, stesso comportamento del legacy —
     *                                        vedi Javadoc di
     *                                        {@link TipoVersamentoDominioRepository#findByCodTipoVersamentoAndDominioId})
     * @throws ValidazioneNonSuperataException se il tipo pendenza e' disabilitato a livello
     *                                          globale, o disabilitato esplicitamente per
     *                                          questo dominio
     */
    private TipoVersamentoDominioEntity risolviTipoVersamentoDominio(String idTipoPendenza, Long idDominio) {
        TipoVersamentoDominioEntity tipoVersamentoDominio = tipoVersamentoDominioRepository
                .findByCodTipoVersamentoAndDominioId(idTipoPendenza, idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun tipo pendenza [" + idTipoPendenza
                        + "] configurato per il dominio [id:" + idDominio + "]"));
        if (Boolean.FALSE.equals(tipoVersamentoDominio.getTipoVersamento().getAbilitato())) {
            throw new ValidazioneNonSuperataException("il tipo pendenza [" + idTipoPendenza
                    + "] non e' abilitato");
        }
        if (Boolean.FALSE.equals(tipoVersamentoDominio.getAbilitato())) {
            throw new ValidazioneNonSuperataException("il tipo pendenza [" + idTipoPendenza
                    + "] non e' abilitato per il dominio [id:" + idDominio + "]");
        }
        return tipoVersamentoDominio;
    }

    /**
     * Rifiuta un tipo pendenza che il CHIAMANTE non e' autorizzato a usare, anche se e'
     * censito/abilitato per il dominio (bug del lead, 2026-09-29: mancava — v2 lo fa in
     * {@code VersamentoUtils.setTipoVersamento}, {@code VER_022}: {@code !applicazione.isTrusted()
     * && !AuthorizationManager.isTipoVersamentoAuthorized(applicazione.getUtenza(),
     * codTipoVersamento)}). Un'applicazione {@code trusted} e' sempre autorizzata a
     * qualunque tipo pendenza censito (nessun controllo aggiuntivo); una non-trusted deve
     * avere {@code utenze.autorizzazione_tipi_vers_star} oppure una riga esplicita in
     * {@code utenze_tipo_vers} per quel tipo versamento.
     *
     * @throws ValidazioneNonSuperataException se l'applicazione non e' trusted e non e'
     *                                          autorizzata al tipo versamento risolto
     */
    private void verificaAutorizzazioneTipoVersamento(ApplicazioneEntity applicazione,
            TipoVersamentoDominioEntity tipoVersamentoDominio) {
        if (Boolean.TRUE.equals(applicazione.getTrusted())) {
            return;
        }
        UtenzaEntity utenza = utenzaRepository.findById(applicazione.getIdUtenza())
                .orElseThrow(() -> new IllegalStateException("applicazione [id:" + applicazione.getId()
                        + "] non ha un'utenza valida (idUtenza [" + applicazione.getIdUtenza() + "])"));
        if (Boolean.TRUE.equals(utenza.getAutorizzazioneTipiVersStar())) {
            return;
        }
        Long idTipoVersamento = tipoVersamentoDominio.getTipoVersamento().getId();
        if (!utenzaTipoVersamentoRepository.existsByIdUtenzaAndIdTipoVersamento(utenza.getId(), idTipoVersamento)) {
            throw new ValidazioneNonSuperataException("l'applicazione [" + applicazione.getCodApplicazione()
                    + "] non e' autorizzata alla gestione del tipo pendenza ["
                    + tipoVersamentoDominio.getTipoVersamento().getCodTipoVersamento() + "]");
        }
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

    /**
     * Override per questo dominio di {@code TipoVersamentoEntity.getCodificaIuv()} se
     * presente, altrimenti il default del catalogo globale — stessa semantica gia' della
     * rimossa {@code TipoVersamentoDominio.getCodificaIuvEffettiva()} di questa libreria,
     * ora che {@code TipoVersamentoEntity}/{@code TipoVersamentoDominioEntity} vengono da
     * govpay-common (issue govpay-common#9) e non hanno quel metodo di comodo.
     */
    private static String codificaIuvEffettiva(TipoVersamentoDominioEntity tvd) {
        return tvd.getCodificaIuv() != null ? tvd.getCodificaIuv() : tvd.getTipoVersamento().getCodificaIuv();
    }

    // ── Richiesta -> entita' ─────────────────────────────────────────────────

    public it.govpay.pendenze.entity.PosizioneDebitoria toEntity(String idA2A, NuovaPosizioneDebitoria dto) {
        ApplicazioneEntity applicazione = applicazioneRepository.findByCodApplicazione(idA2A)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna applicazione con idA2A [" + idA2A
                        + "]"));
        Long idApplicazione = applicazione.getId();
        Long idDominio = risolviIdDominioAbilitato(dto.getIdDominio());
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
            posizione.addOpzionePagamento(toOpzionePagamento(opzione, idDominio, applicazione));
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

    // ── PATCH (RFC 6902) ────────────────────────────────────────────────────────

    /**
     * Applica un sottoinsieme di operazioni JSON Patch (RFC 6902, add/remove/replace) a una
     * posizione debitoria ({@code PATCH .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}}).
     * Path supportati: {@code /descrizione}, {@code /dataPubblicazione},
     * {@code /notificaSend}, {@code /navNotifica}, {@code /soggettiDebitori} (solo
     * sostituzione dell'intero array: {@code /soggettiDebitori/{indice}} e
     * {@code /soggettiDebitori/-} non sono supportati in questa prima versione). Qualunque
     * altro path (incluso {@code /opzioniPagamento}: si aggiornano con
     * {@code POST}/{@code PATCH .../opzioni-pagamento} dedicati) e' rifiutato con 400.
     *
     * <p>Le mutazioni sono applicate direttamente su {@code posizione}, un aggregato gestito
     * da JPA passato dal chiamante ({@link it.govpay.pendenze.service.PosizioneDebitoriaService#aggiorna}):
     * questo metodo non lo salva ne' lo rivalida.</p>
     *
     * @throws ValidazioneNonSuperataException se un'operazione ha un path non supportato,
     *                                          un {@code op} non ammesso per quel path, un
     *                                          {@code value} assente dove richiesto, o di
     *                                          tipo/forma sbagliata
     */
    public void applicaPatch(it.govpay.pendenze.entity.PosizioneDebitoria posizione, List<PatchOp> operazioni) {
        for (PatchOp operazione : operazioni) {
            if (operazione == null) {
                throw new ValidazioneNonSuperataException(
                        "operazione di patch nulla non ammessa: ogni elemento dell'array deve essere un "
                                + "oggetto {op, path, value}");
            }
            switch (operazione.getPath()) {
                case "/descrizione" -> applicaDescrizione(posizione, operazione);
                case "/dataPubblicazione" -> applicaDataPubblicazione(posizione, operazione);
                case "/notificaSend" -> applicaNotificaSend(posizione, operazione);
                case "/navNotifica" -> applicaNavNotifica(posizione, operazione);
                case "/soggettiDebitori" -> applicaSoggettiDebitori(posizione, operazione);
                default -> throw new ValidazioneNonSuperataException(
                        "path [" + operazione.getPath() + "] non supportato per questa risorsa");
            }
        }
    }

    /** Stesso vincolo di {@code NuovaPosizioneDebitoria.descrizione} nello YAML (bug del lead, 2026-09-29: la PATCH non lo verificava — una descrizione di 141 caratteri passava con 200, mentre in creazione lo YAML impone 140). */
    private static final int DESCRIZIONE_MAX_LENGTH = 140;

    private void applicaDescrizione(it.govpay.pendenze.entity.PosizioneDebitoria posizione, PatchOp operazione) {
        if (operazione.getOp() == PatchOp.OpEnum.REMOVE) {
            throw new ValidazioneNonSuperataException(
                    "'descrizione' e' obbligatoria: non puo' essere rimossa con 'remove'");
        }
        String descrizione = valoreStringa(operazione, "descrizione");
        if (descrizione.length() > DESCRIZIONE_MAX_LENGTH) {
            throw new ValidazioneNonSuperataException(
                    "'descrizione' non puo' superare " + DESCRIZIONE_MAX_LENGTH + " caratteri (" + descrizione.length()
                            + " forniti)");
        }
        posizione.setDescrizione(descrizione);
    }

    private void applicaDataPubblicazione(it.govpay.pendenze.entity.PosizioneDebitoria posizione,
            PatchOp operazione) {
        if (operazione.getOp() == PatchOp.OpEnum.REMOVE) {
            posizione.setDataPubblicazione(null);
            return;
        }
        Object valore = valorePresente(operazione, "dataPubblicazione");
        try {
            posizione.setDataPubblicazione(objectMapper.convertValue(valore, LocalDate.class));
        } catch (RuntimeException e) {
            throw new ValidazioneNonSuperataException(
                    "'dataPubblicazione' non e' una data valida (formato atteso: AAAA-MM-GG)");
        }
    }

    private void applicaNotificaSend(it.govpay.pendenze.entity.PosizioneDebitoria posizione, PatchOp operazione) {
        if (operazione.getOp() == PatchOp.OpEnum.REMOVE) {
            throw new ValidazioneNonSuperataException(
                    "'notificaSend' e' un booleano: non puo' essere rimosso con 'remove', usare 'replace' con false");
        }
        Object valore = valorePresente(operazione, "notificaSend");
        if (!(valore instanceof Boolean booleano)) {
            throw new ValidazioneNonSuperataException("'notificaSend' richiede un valore booleano");
        }
        posizione.setNotificaSend(booleano);
    }

    private void applicaNavNotifica(it.govpay.pendenze.entity.PosizioneDebitoria posizione, PatchOp operazione) {
        if (operazione.getOp() == PatchOp.OpEnum.REMOVE) {
            posizione.setNavNotifica(null);
            return;
        }
        posizione.setNavNotifica(valoreStringa(operazione, "navNotifica"));
    }

    private void applicaSoggettiDebitori(it.govpay.pendenze.entity.PosizioneDebitoria posizione, PatchOp operazione) {
        if (operazione.getOp() == PatchOp.OpEnum.REMOVE) {
            throw new ValidazioneNonSuperataException(
                    "'soggettiDebitori' non puo' essere rimosso interamente: la posizione debitoria "
                            + "deve avere almeno un soggetto debitore");
        }
        Object valore = valorePresente(operazione, "soggettiDebitori");
        if (valore == null) {
            throw new ValidazioneNonSuperataException(
                    "'soggettiDebitori' non puo' essere impostato a null: fornire un array di soggetti");
        }
        List<Soggetto> dto;
        try {
            dto = objectMapper.convertValue(valore, new TypeReference<List<Soggetto>>() {
            });
        } catch (RuntimeException e) {
            throw new ValidazioneNonSuperataException(
                    "'soggettiDebitori' non e' una lista valida di soggetti (tipo/identificativo, ecc.)");
        }
        for (Soggetto soggetto : dto) {
            if (soggetto == null) {
                throw new ValidazioneNonSuperataException("'soggettiDebitori' non puo' contenere elementi null");
            }
            var violazioni = validator.validate(soggetto);
            if (!violazioni.isEmpty()) {
                throw new ValidazioneNonSuperataException(
                        "soggetto debitore non valido: " + violazioni.iterator().next().getMessage());
            }
        }
        posizione.sostituisciSoggettiDebitori(dto.stream().map(this::toSoggettoDebitore).toList());
    }

    private String valoreStringa(PatchOp operazione, String nomeCampo) {
        Object valore = valorePresente(operazione, nomeCampo);
        if (!(valore instanceof String stringa) || stringa.isBlank()) {
            throw new ValidazioneNonSuperataException("'" + nomeCampo + "' richiede un valore testuale non vuoto");
        }
        return stringa;
    }

    private Object valorePresente(PatchOp operazione, String nomeCampo) {
        if (!operazione.getValue().isPresent()) {
            throw new ValidazioneNonSuperataException(
                    "operazione '" + operazione.getOp().getValue() + "' su '" + nomeCampo + "' richiede 'value'");
        }
        return operazione.getValue().get();
    }

    private it.govpay.pendenze.entity.OpzionePagamento toOpzionePagamento(NuovaOpzionePagamento dto,
            Long idDominioPosizione, ApplicazioneEntity applicazione) {
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
            opzione.addPendenza(toPendenza(pendenza, idDominioPosizione, applicazione));
        }
        return opzione;
    }

    private it.govpay.pendenze.entity.Pendenza toPendenza(NuovaPendenza dto, Long idDominioPosizione,
            ApplicazioneEntity applicazione) {
        it.govpay.pendenze.entity.Pendenza pendenza = new it.govpay.pendenze.entity.Pendenza();
        pendenza.setIdApplicazione(applicazione.getId());
        pendenza.setIdPendenza(dto.getIdPendenza());

        TipoVersamentoDominioEntity tipoVersamentoDominio = risolviTipoVersamentoDominio(dto.getIdTipoPendenza(),
                idDominioPosizione);
        verificaAutorizzazioneTipoVersamento(applicazione, tipoVersamentoDominio);
        pendenza.setIdTipoPendenza(tipoVersamentoDominio.getId());
        pendenza.setIdTipoVersamento(tipoVersamentoDominio.getTipoVersamento().getId());
        // Necessaria a GeneratoreIuvStandard per risolvere %(p)/%(t) nel prefisso IUV di
        // dominio (bug del lead, 2026-09-27: dimenticata nel primo giro — la creazione
        // falliva con 500 per ogni dominio il cui prefisso usa quel placeholder).
        pendenza.setCodificaIuvTipoPendenza(codificaIuvEffettiva(tipoVersamentoDominio));

        pendenza.setImporto(dto.getImporto().doubleValue());
        pendenza.setNumeroAvviso(dto.getNumeroAvviso());
        pendenza.setDataValidita(aInizioGiorno(dto.getDataValidita()));
        pendenza.setDataScadenzaAvviso(aInizioGiorno(dto.getDataScadenzaAvviso()));

        for (NuovaVocePendenza voce : dto.getVoci()) {
            pendenza.addVocePendenza(toVocePendenza(voce, idDominioPosizione));
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

    private VocePendenza toVocePendenza(NuovaVocePendenza dto, Long idDominioPosizione) {
        VocePendenza voce = new VocePendenza();
        // Il servizio non lo valorizza (a differenza di OpzionePagamento/Pendenza): sta al
        // chiamante — vedi Javadoc del campo VocePendenza.stato.
        voce.setStato(StatoVocePendenza.NON_ESEGUITO);

        if (dto instanceof NuovaVocePendenzaRiferimentoEntrata v) {
            Long idDominioEffettivo = popolaCampiComuni(voce, v.getIdVocePendenza(), v.getImporto(),
                    v.getDescrizione(), v.getIdDominio(), idDominioPosizione);
            // getTipoRiferimento() e' derivato da idTributo (vedi Javadoc di VocePendenza):
            // nessun setTipoRiferimento esplicito, a differenza del primo giro.
            voce.setIdTributo(risolviIdTributo(v.getCodEntrata(), idDominioEffettivo));
            for (Dettaglio dettaglio : v.getDettaglioContabile()) {
                voce.getDettaglioContabile().add(toDettaglioContabile(dettaglio));
            }
        } else if (dto instanceof NuovaVocePendenzaEntrata v) {
            Long idDominioEffettivo = popolaCampiComuni(voce, v.getIdVocePendenza(), v.getImporto(),
                    v.getDescrizione(), v.getIdDominio(), idDominioPosizione);
            voce.setIdIbanAccredito(risolviIdIban(v.getIbanAccredito(), idDominioEffettivo));
            voce.setIdIbanAppoggio(risolviIdIban(v.getIbanAppoggio(), idDominioEffettivo));
            voce.setTassonomia(v.getTassonomia());
            for (Dettaglio dettaglio : v.getDettaglioContabile()) {
                voce.getDettaglioContabile().add(toDettaglioContabile(dettaglio));
            }
        } else if (dto instanceof NuovaVocePendenzaBollo v) {
            popolaCampiComuni(voce, v.getIdVocePendenza(), v.getImporto(), v.getDescrizione(), v.getIdDominio(),
                    idDominioPosizione);
            voce.setTipoBollo(v.getTipoBollo().getValue());
            voce.setHashDocumento(v.getHashDocumento());
            voce.setProvinciaResidenza(v.getProvinciaResidenza());
            voce.setTassonomia(v.getTassonomia());
            // Bollo non ammette dettaglioContabile (vincolo dello schema Bollo dello YAML).
        } else {
            throw new IllegalArgumentException("tipo di NuovaVocePendenza non gestito: " + dto.getClass());
        }
        return voce;
    }

    /**
     * {@code voce.idDominio} non viene mai materializzato al default (a differenza di
     * {@code Pendenza.idDominio}): {@code null} resta {@code null}, e' compito di
     * {@code PosizioneDebitoriaService#crea} valorizzarlo con quello della posizione se il
     * chiamante non ha indicato un override esplicito — vedi Javadoc di
     * {@code VocePendenza.idDominio}. Il valore di ritorno serve invece SUBITO al chiamante,
     * per risolvere {@code idTributo}/{@code idIbanAccredito}/{@code idIbanAppoggio} —
     * l'anagrafica del tributo/IBAN va cercata nel dominio effettivo della voce (l'override,
     * se presente — caso multi-beneficiario pagoPA — altrimenti quello della posizione), non
     * necessariamente in quello di default che il servizio applichera' solo dopo.
     *
     * @return il dominio effettivo di questa voce (mai {@code null})
     */
    private Long popolaCampiComuni(VocePendenza voce, String idVocePendenza, BigDecimal importo, String descrizione,
            String idDominioVoce, Long idDominioPosizione) {
        voce.setIdVocePendenza(idVocePendenza);
        voce.setImporto(importo.doubleValue());
        voce.setDescrizione(descrizione);
        if (idDominioVoce == null) {
            return idDominioPosizione;
        }
        Long idDominioRisolto = risolviIdDominioAbilitato(idDominioVoce);
        voce.setIdDominio(idDominioRisolto);
        return idDominioRisolto;
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
        // dataCaricamento non e' una colonna propria (decisione del lead, 2026-09-28, su
        // richiesta esplicita — vedi Javadoc di classe di Pendenza): si deriva da
        // dataCreazione, sempre valorizzata.
        dto.setDataCaricamento(entity.getDataCreazione().toLocalDate());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenzaAvviso(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        return dto;
    }

    /**
     * Costruisce un elemento di {@code GET /pendenze/{idA2A}} (ricerca per numero avviso):
     * {@code PendenzaIndex}, come {@link #toPendenzaOpzionePagamentoDto} ma con in piu'
     * {@code posizioneDebitoria} ({@code PosizioneDebitoriaIndex}, richiesto dallo schema —
     * a differenza di {@code PendenzaOpzionePagamento}, qui il chiamante non la conosce gia').
     *
     * @throws IllegalStateException se {@code entity.getOpzionePagamento()} e' {@code null} —
     *         una pendenza creata da v2/migrazione, priva del concetto di opzione/posizione:
     *         lo schema richiede sia {@code opzionePagamento} sia {@code posizioneDebitoria},
     *         quindi non e' rappresentabile in questa risposta. Il chiamante (controller) deve
     *         escludere questi elementi PRIMA di chiamare questo metodo, non affidarsi a
     *         questa eccezione — resta qui solo come guardia esplicita, non come percorso
     *         normale (nessun caso reale la esercita oggi: nessuna pendenza v2 e' mai stata
     *         migrata finora).
     */
    public it.govpay.pendenze.api.model.PendenzaIndex toPendenzaIndexDto(it.govpay.pendenze.entity.Pendenza entity) {
        if (entity.getOpzionePagamento() == null) {
            throw new IllegalStateException("la pendenza [" + entity.getIdPendenza() + "] non ha un'opzione di "
                    + "pagamento (creata da v2/migrazione): non rappresentabile in PendenzaIndex, il chiamante "
                    + "doveva escluderla prima di chiamare questo metodo");
        }
        it.govpay.pendenze.api.model.PendenzaIndex dto = new it.govpay.pendenze.api.model.PendenzaIndex();
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
        dto.setDataCaricamento(entity.getDataCreazione().toLocalDate());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenzaAvviso(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        dto.setPosizioneDebitoria(toIndexDto(entity.getOpzionePagamento().getPosizioneDebitoria()));
        return dto;
    }
}
