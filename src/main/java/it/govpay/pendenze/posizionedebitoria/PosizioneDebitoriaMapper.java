package it.govpay.pendenze.posizionedebitoria;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
import it.govpay.pendenze.api.model.DettaglioContabileSconosciuto;
import it.govpay.pendenze.api.model.DettaglioContabileSconosciutoEntriesInner;
import it.govpay.pendenze.api.model.DettaglioLetto;
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
import it.govpay.pendenze.api.model.TipoDettaglioContabile;
import it.govpay.pendenze.api.model.TipoSoggetto;
import it.govpay.pendenze.api.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.api.model.VocePendenzaBollo;
import it.govpay.pendenze.api.model.VocePendenzaEntrata;
import it.govpay.pendenze.api.model.VocePendenzaRiferimentoEntrata;
import it.govpay.pendenze.entity.SoggettoDebitore;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.DettaglioContabile;
import it.govpay.pendenze.model.StatoVocePendenza;
import it.govpay.pendenze.model.TipoRiferimentoVocePendenza;
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
 * {@code Pendenza}/{@code VocePendenza}/{@code TipologiaOpzionePagamento}/
 * {@code StatoOpzionePagamento}/{@code StatoPendenza}/{@code StatoVocePendenza}/
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
     * percorsi di SCRITTURA (v2 applica lo stesso controllo in {@code VersamentoUtils},
     * {@code DOM_001}).
     *
     * @throws AnagraficaNonTrovataException se {@code idDominio} non corrisponde a nessun
     *                                        dominio
     * @throws ValidazioneNonSuperataException se il dominio esiste ma e' disabilitato
     */
    private Long risolviIdDominioAbilitato(String idDominio) {
        DominioEntity dominio = dominioRepository.findByCodDominio(idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun dominio con idDominio [" + idDominio
                        + "]"));
        verificaDominioAbilitato(dominio);
        return dominio.getId();
    }

    /**
     * Come {@link #risolviIdDominioAbilitato}, ma per un dominio gia' risolto per id — usato
     * da {@link #toOpzionePagamento(String, Long, NuovaOpzionePagamento)}, dove il dominio
     * arriva gia' come {@code Long} dalla posizione esistente. Necessario perche' quell'entry
     * point riusa {@code idDominioPosizione} senza ripassare da {@link #toEntity}: senza
     * questo controllo, un dominio disabilitato DOPO la creazione della posizione non
     * impedirebbe di aggiungergli una nuova opzione di pagamento.
     *
     * @throws ValidazioneNonSuperataException se il dominio e' disabilitato
     */
    private void verificaDominioAbilitato(Long idDominio) {
        // idDominio arriva da PosizioneDebitoria.getIdDominio(), gia' risolto: se non
        // esistesse piu' sarebbe un'incoerenza referenziale del DB, non un caso applicativo
        // da gestire qui (nessuna FK reale per M4, ma nessun codice la rimuove mai).
        dominioRepository.findById(idDominio).ifPresent(this::verificaDominioAbilitato);
    }

    private void verificaDominioAbilitato(DominioEntity dominio) {
        if (Boolean.FALSE.equals(dominio.getAbilitato())) {
            throw new ValidazioneNonSuperataException("il dominio [" + dominio.getCodDominio()
                    + "] non e' abilitato");
        }
    }

    private String risolviCodDominio(Long idDominio) {
        return idDominio == null ? null
                : dominioRepository.findById(idDominio).map(DominioEntity::getCodDominio).orElse(null);
    }

    /**
     * Rifiuta un'unita' operativa disabilitata, come {@link #risolviIdTributo}/
     * {@link #risolviIdIban} fanno per i rispettivi controlli — v2 applica lo stesso
     * controllo in {@code VersamentoUtils.setUo}, {@code UOP_001}.
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
     * <p>Rifiuta un tributo disabilitato per il dominio — v2 applica lo stesso controllo in
     * {@code VersamentoUtils}, {@code TRB_001}: senza questo controllo una POST con un
     * tributo disabilitato tornerebbe 201 invece di essere rifiutata.</p>
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
     * Rifiuta un IBAN disabilitato per il dominio — v2 applica lo stesso controllo in
     * {@code VersamentoUtils}, {@code VER_032}/{@code VER_034} per accredito/appoggio.
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

    /** Risoluzione inversa di {@link #risolviIdTributo}, per {@link #toVocePendenzaDto} (lettura). */
    private String risolviCodEntrata(Long idTributo) {
        return idTributo == null ? null
                : tributoRepository.findById(idTributo).map(t -> t.getTipoTributo().getCodTributo()).orElse(null);
    }

    /** Risoluzione inversa di {@link #risolviIdIban}, per {@link #toVocePendenzaDto} (lettura). */
    private String risolviCodIban(Long idIban) {
        return idIban == null ? null : ibanAccreditoRepository.findById(idIban).map(IbanAccreditoEntity::getCodIban)
                .orElse(null);
    }

    /**
     * Rifiuta un tipo pendenza disabilitato, sia a livello globale sia nell'override per
     * questo dominio — v2 applica lo stesso controllo in {@code VersamentoUtils},
     * {@code TVR_001}/{@code TVD_001}. {@code abilitato} e' colonna
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
     * censito/abilitato per il dominio — v2 applica lo stesso controllo in
     * {@code VersamentoUtils.setTipoVersamento}, {@code VER_022}: {@code !applicazione.isTrusted()
     * && !AuthorizationManager.isTipoVersamentoAuthorized(applicazione.getUtenza(),
     * codTipoVersamento)}. Un'applicazione {@code trusted} e' sempre autorizzata a
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
     * {@code findByIdFetchTipoVersamento}, non {@code findById}: senza il {@code join fetch},
     * {@code tvd.getTipoVersamento()} resta un proxy LAZY — qui viene tipicamente letto da
     * {@link #toDto} dopo che la transazione di scrittura di
     * {@code PosizioneDebitoriaService#crea} e' gia' tornata (con {@code open-in-view=false}
     * la sessione Hibernate e' gia' chiusa), sollevando {@code LazyInitializationException}
     * (500 anziche' la risposta 201 — la posizione resterebbe comunque salvata, come si vede
     * rileggendola con un secondo tentativo, che riceve 409).
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

    /**
     * Converte {@code NuovaOpzionePagamento} in entita' per
     * {@code POST .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento}
     * (aggiunta a una posizione GIA' esistente — a differenza di {@link #toEntity}, qui
     * {@code idDominioPosizione} viene dal chiamante, che ha gia' risolto la posizione presso
     * {@code PosizioneDebitoriaService#aggiungiOpzionePagamento} prima di costruire l'opzione).
     * Stessi controlli di risoluzione/abilitazione/autorizzazione di {@link #toEntity} (tipo
     * pendenza, tributo, IBAN): nessuna duplicazione, delega al convertitore privato condiviso.
     *
     * @throws AnagraficaNonTrovataException se {@code idA2A} non corrisponde a nessuna
     *                                        applicazione
     */
    public it.govpay.pendenze.entity.OpzionePagamento toOpzionePagamento(String idA2A, Long idDominioPosizione,
            NuovaOpzionePagamento dto) {
        ApplicazioneEntity applicazione = applicazioneRepository.findByCodApplicazione(idA2A)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna applicazione con idA2A [" + idA2A
                        + "]"));
        verificaDominioAbilitato(idDominioPosizione);
        return toOpzionePagamento(dto, idDominioPosizione, applicazione);
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

    /**
     * Stesso vincolo di {@code NuovaPosizioneDebitoria.descrizione} nello YAML: senza questo
     * controllo la PATCH accetterebbe una descrizione piu' lunga del limite imposto in
     * creazione (140 caratteri).
     */
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

    /**
     * Valida il body di {@code PATCH .../opzioni-pagamento/{idOpzionePagamento}}: l'unica
     * operazione supportata e' la transizione manuale di stato
     * ({@code [{"op": "replace"|"add", "path": "/stato", "value": "ANNULLATA"|"DISPONIBILE"}]}),
     * annullamento o ripristino — a differenza di {@link #applicaPatch} (posizione), qui non
     * c'e' alcuna entita' da mutare direttamente: la transizione vera e propria, inclusa la
     * cascata sulle pendenze dell'opzione, e' interamente a carico di
     * {@code PosizioneDebitoriaService#annulla}/{@code #ripristina}, chiamati dal controller
     * solo se questa validazione passa — nessuna regola di business qui, solo sintassi
     * (quale path, quale valore): tutte le validazioni di transizione vivono nel service,
     * non in questo mapper, perche' anche un consumatore diverso da questo controller (es.
     * govpay-console-api, che richiamera' direttamente il service) deve ottenere le stesse
     * garanzie senza passare da qui. {@code /stato: "ATTIVATA"} non e' raggiungibile da qui
     * (semantica dello YAML v3: l'attivazione e' innescata da un pagamento reale, mai da un
     * PATCH del chiamante) — rifiutato con lo stesso 400 di qualunque altro valore non
     * supportato.
     *
     * @return lo stato di destinazione richiesto ({@code ANNULLATA} o {@code DISPONIBILE})
     * @throws ValidazioneNonSuperataException se il body non e' esattamente quella singola
     *                                          operazione (body nullo/vuoto, piu' di
     *                                          un'operazione, path/op/value diversi), o il
     *                                          valore non e' uno dei due ammessi
     */
    public it.govpay.pendenze.model.StatoOpzionePagamento validaPatchStatoOpzione(List<PatchOp> operazioni) {
        if (operazioni == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        if (operazioni.size() != 1) {
            throw new ValidazioneNonSuperataException("questo endpoint supporta esattamente un'operazione di "
                    + "patch, la transizione di stato (op \"replace\", path \"/stato\", value \"ANNULLATA\" o "
                    + "\"DISPONIBILE\")");
        }
        PatchOp operazione = operazioni.get(0);
        if (operazione == null) {
            throw new ValidazioneNonSuperataException(
                    "operazione di patch nulla non ammessa: deve essere un oggetto {op, path, value}");
        }
        if (!"/stato".equals(operazione.getPath())) {
            throw new ValidazioneNonSuperataException(
                    "path [" + operazione.getPath() + "] non supportato per questa risorsa: solo /stato");
        }
        if (operazione.getOp() == PatchOp.OpEnum.REMOVE) {
            throw new ValidazioneNonSuperataException("'stato' e' obbligatorio: non puo' essere rimosso con 'remove'");
        }
        String valore = valoreStringa(operazione, "stato");
        if (!"ANNULLATA".equals(valore) && !"DISPONIBILE".equals(valore)) {
            throw new ValidazioneNonSuperataException("'stato' puo' essere impostato solo a 'ANNULLATA' o "
                    + "'DISPONIBILE' tramite questo endpoint: l'attivazione avviene a seguito di un pagamento, "
                    + "non di un PATCH del chiamante");
        }
        return it.govpay.pendenze.model.StatoOpzionePagamento.valueOf(valore);
    }

    /**
     * Sostituisce i dati di una pendenza esistente ({@code PUT .../pendenze/{idA2A}/{idPendenza}})
     * — la "PUT profonda" legacy ({@code PendenzeDAO.createOrUpdate}, {@code aggiornaSeEsiste=true}),
     * verificata essere un verbo HTTP PUT letterale che riusa esattamente lo stesso schema
     * della creazione ({@code wars/api-pendenze/.../v2/controller/PendenzeController.java:348},
     * {@code PendenzeConverter.getVersamentoFromPendenza}: stesso DTO {@code NuovaPendenza},
     * stessa {@code .validate()}). Qui {@code dto} e' lo stesso {@link NuovaPendenza} della
     * creazione ({@link #toPendenza}), non una lista di operazioni JSON Patch: il chiamante
     * invia l'intero stato desiderato.
     *
     * <p><b>Campi NON editabili</b> nonostante la struttura condivisa — mirror del legacy
     * ({@code VersamentoUtils.validazioneSemanticaAggiornamento}, {@code jars/core/.../utils/
     * VersamentoUtils.java:161-264}) salvo dove annotato:</p>
     * <ul>
     * <li>{@code idPendenza}: obbligatorio ({@code @NotNull} sul DTO generato, stesso vincolo
     * della creazione) e deve coincidere con quello del path (identificativo di risorsa, non
     * un campo modificabile);</li>
     * <li>{@code numeroAvviso}: non puo' cambiare una volta assegnato (mirror VER_024); se
     * ancora assente puo' essere impostato;</li>
     * <li>per voce MATCHATA (per {@code idVocePendenza}): {@code idTributo}/
     * {@code idIbanAccredito} non possono cambiare (mirror esatto VER_007/VER_023) — quindi
     * nemmeno il passaggio a/da BOLLO, dato che {@code getTipoRiferimento()} si deriva da
     * questi stessi campi.</li>
     * </ul>
     * {@code idTipoPendenza} e {@code idIbanAppoggio} sono invece liberamente modificabili,
     * cosi' come {@code tipoBollo}/{@code hashDocumento}/{@code provinciaResidenza} su una voce
     * che resta BOLLO: il legacy non protegge nessuno di questi campi (li sovrascrive sempre
     * senza controlli, vedi {@code VersamentoUtils.validazioneSemanticaAggiornamento}) — scelta
     * deliberata di restare un mirror esatto, non di introdurre vincoli che il legacy, in anni
     * di uso in produzione, non ha mai avuto.
     *
     * <p><b>Voci: rimozione ammessa</b>, a differenza del legacy ({@code VER_005}/{@code VER_006},
     * che rifiutano sempre una riduzione) — richiesta esplicita per questo endpoint v3, non un
     * mirror. Le voci esistenti non presenti nella richiesta vengono rimosse dalla collezione
     * ({@code Pendenza.voci} ha {@code orphanRemoval}); il minimo di una voce e' garantito sia
     * dallo schema ({@code NuovaPendenza.voci} ha {@code minItems: 1}) sia, a valle, da
     * {@code ValidatorePosizioneDebitoria.validaPendenza} (cardinalita' 1-5). {@code indice}
     * non e' mai preso dalla richiesta: sulle voci matchate resta quello originale, alle voci
     * nuove viene assegnato in sequenza dopo l'ultimo esistente — stessa regola del legacy
     * ({@code indiceDati} mai dalla richiesta).</p>
     *
     * <p>Nessuna validazione manuale delle voci qui (a differenza della precedente PATCH basata
     * su JSON Patch): il controller riceve {@code dto} gia' come {@code @Valid @RequestBody},
     * che Spring cascata automaticamente su ogni elemento di {@code voci} (annotato
     * {@code @Valid} nel modello generato) — stessa garanzia della creazione.</p>
     *
     * <p>Le mutazioni sono applicate direttamente su {@code pendenza}, un aggregato gestito da
     * JPA passato dal chiamante ({@code PosizioneDebitoriaService#aggiornaPendenza}): questo
     * metodo non lo salva ne' lo rivalida (la rivalidazione, inclusa la coerenza importo/voci,
     * avviene dopo, nello stesso punto della creazione — {@code ValidatorePosizioneDebitoria}).</p>
     *
     * @throws AnagraficaNonTrovataException       se {@code idA2A} non corrisponde a nessuna
     *                                              applicazione
     * @throws ValidazioneNonSuperataException     se {@code idPendenza} nel body non coincide
     *                                              con quello del path, o se {@code idTipoPendenza}
     *                                              non e' risolvibile/autorizzato per questa
     *                                              applicazione
     * @throws TransizioneStatoNonAmmessaException se la richiesta cambia {@code numeroAvviso}
     *                                              gia' assegnato, o il riferimento (tributo/
     *                                              IBAN di accredito) di una voce esistente
     */
    public void applicaPutPendenza(String idA2A, it.govpay.pendenze.entity.Pendenza pendenza, NuovaPendenza dto) {
        ApplicazioneEntity applicazione = applicazioneRepository.findByCodApplicazione(idA2A)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna applicazione con idA2A [" + idA2A
                        + "]"));

        if (!dto.getIdPendenza().equals(pendenza.getIdPendenza())) {
            throw new ValidazioneNonSuperataException("'idPendenza' nel body [" + dto.getIdPendenza()
                    + "] non corrisponde a quello nel path [" + pendenza.getIdPendenza() + "]");
        }

        TipoVersamentoDominioEntity tipoVersamentoDominio = risolviTipoVersamentoDominio(dto.getIdTipoPendenza(),
                pendenza.getIdDominio());
        verificaAutorizzazioneTipoVersamento(applicazione, tipoVersamentoDominio);
        pendenza.setIdTipoPendenza(tipoVersamentoDominio.getId());
        pendenza.setIdTipoVersamento(tipoVersamentoDominio.getTipoVersamento().getId());
        pendenza.setCodificaIuvTipoPendenza(codificaIuvEffettiva(tipoVersamentoDominio));

        if (pendenza.getNumeroAvviso() != null && dto.getNumeroAvviso() != null
                && !pendenza.getNumeroAvviso().equals(dto.getNumeroAvviso())) {
            throw new TransizioneStatoNonAmmessaException("'numeroAvviso' non puo' cambiare rispetto all'originale ["
                    + pendenza.getNumeroAvviso() + "]");
        }
        if (pendenza.getNumeroAvviso() == null) {
            pendenza.setNumeroAvviso(dto.getNumeroAvviso());
        }

        pendenza.setImporto(dto.getImporto().doubleValue());
        pendenza.setDataValidita(aInizioGiorno(dto.getDataValidita()));
        pendenza.setDataScadenzaAvviso(aInizioGiorno(dto.getDataScadenzaAvviso()));

        sostituisciVoci(pendenza, dto.getVoci());
    }

    /**
     * Le voci rimosse (non ripresentate nella richiesta, rimozione ammessa per questo
     * endpoint) non vengono cancellate subito: finiscono in {@code daRiusare}, da cui le voci
     * nuove le "pescano" per riusarne la riga (stesso {@code indice}, stesso id JPA — un
     * {@code UPDATE}, non una coppia {@code DELETE}+{@code INSERT}).
     *
     * <p><b>Perche' serve</b>: Hibernate esegue, nello stesso flush, tutti gli insert PRIMA di
     * tutti i delete. Una voce nuova che riusasse l'indice di una voce rimossa SENZA riusarne
     * anche la riga (cioe' un vero {@code INSERT} con lo stesso indice appena "liberato" in
     * memoria) violerebbe quindi transitoriamente il vincolo di unicita'
     * {@code (id_versamento, indice_dati)}, perche' al momento dell'{@code INSERT} la riga
     * rimossa e' ancora presente — il suo {@code DELETE} non e' ancora partito. Bug
     * riscontrato riproducendo esattamente questo caso (sostituzione dell'unica voce di una
     * pendenza): assegnare alle voci nuove "il massimo indice esistente + 1" evitava la
     * collisione ma cresceva senza limite a ogni ciclo rimozione+aggiunta, superando 5 (il
     * {@code maximum} dello schema di {@code VocePendenza.indice}) dopo poche sostituzioni,
     * pur restando sempre entro la cardinalita' massima di 5 voci.</p>
     *
     * <p>Solo le voci nuove in eccesso rispetto alle righe disponibili da riusare (quando le
     * voci nuove superano quelle rimosse) ottengono un indice libero genuino — mai occupato da
     * nessuna riga, nemmeno in rimozione, quindi senza alcun rischio di collisione: essendo il
     * totale delle voci sempre {@code <=} 5 (verificato a valle da
     * {@code ValidatorePosizioneDebitoria}), ne esiste sempre uno.</p>
     */
    private void sostituisciVoci(it.govpay.pendenze.entity.Pendenza pendenza, List<NuovaVocePendenza> richieste) {
        Map<String, VocePendenza> esistentiPerId = pendenza.getVoci().stream()
                .collect(Collectors.toMap(VocePendenza::getIdVocePendenza, v -> v));

        List<VocePendenza> candidate = richieste.stream()
                .map(dto -> toVocePendenza(dto, pendenza.getIdDominio()))
                .toList();
        // Stessa materializzazione fatta dal service in creazione
        // (PosizioneDebitoriaService#crea/#aggiungiOpzionePagamento, "se voce.idDominio e'
        // null lo valorizzo con quello della posizione"): qui non si passa da quel codice,
        // quindi senza questo passaggio una voce senza override esplicito perderebbe idDominio.
        for (VocePendenza candidata : candidate) {
            if (candidata.getIdDominio() == null) {
                candidata.setIdDominio(pendenza.getIdDominio());
            }
        }

        /*
         * Unicita' di idVocePendenza DENTRO la richiesta, verificata qui e non solo a valle da
         * ValidatorePosizioneDebitoria: se l'id duplicato corrisponde a una voce NUOVA, il
         * duplicato sopravvive nell'aggregato finale e il controllo comune lo intercetta — ma
         * se corrisponde a una voce ESISTENTE, il ciclo sotto applica entrambe le occorrenze
         * alla STESSA entity (match per idVocePendenza), quindi nell'aggregato finale non c'e'
         * mai un duplicato da intercettare: la richiesta viene accettata con 200 e prevale
         * silenziosamente l'ultima occorrenza (bug riscontrato). Controllo esplicito qui,
         * prima di qualunque match/mutazione, per rifiutare entrambi i casi allo stesso modo.
         */
        Set<String> idVisti = new HashSet<>();
        for (VocePendenza candidata : candidate) {
            if (!idVisti.add(candidata.getIdVocePendenza())) {
                throw new ValidazioneNonSuperataException(
                        "la voce [" + candidata.getIdVocePendenza() + "] e' presente piu' volte nella richiesta");
            }
        }

        Set<String> idPresentiNellaRichiesta = candidate.stream()
                .map(VocePendenza::getIdVocePendenza).collect(Collectors.toSet());

        Deque<VocePendenza> daRiusare = pendenza.getVoci().stream()
                .filter(v -> !idPresentiNellaRichiesta.contains(v.getIdVocePendenza()))
                .collect(Collectors.toCollection(ArrayDeque::new));
        Set<Integer> indiciOccupati = pendenza.getVoci().stream()
                .map(VocePendenza::getIndice)
                .collect(Collectors.toCollection(HashSet::new));
        int prossimoIndiceLibero = 1;

        for (VocePendenza candidata : candidate) {
            VocePendenza esistente = esistentiPerId.get(candidata.getIdVocePendenza());
            if (esistente == null) {
                VocePendenza riusata = daRiusare.poll();
                if (riusata != null) {
                    sovrascriviCampiVoce(riusata, candidata);
                    continue;
                }
                while (indiciOccupati.contains(prossimoIndiceLibero)) {
                    prossimoIndiceLibero++;
                }
                candidata.setIndice(prossimoIndiceLibero);
                indiciOccupati.add(prossimoIndiceLibero);
                pendenza.addVocePendenza(candidata);
                continue;
            }
            if (!Objects.equals(esistente.getIdTributo(), candidata.getIdTributo())
                    || !Objects.equals(esistente.getIdIbanAccredito(), candidata.getIdIbanAccredito())) {
                throw new TransizioneStatoNonAmmessaException(
                        "la voce [" + candidata.getIdVocePendenza() + "] non puo' cambiare il riferimento "
                                + "(tributo/IBAN di accredito) rispetto all'originale");
            }
            sovrascriviCampiVoce(esistente, candidata);
        }

        // Voci rimaste senza una voce nuova che le riusi: rimozione effettiva — orphanRemoval
        // su Pendenza.voci le elimina su DB.
        pendenza.getVoci().removeAll(daRiusare);
    }

    /** Non tocca {@code indice} (va preservato sia sulle voci matchate sia su quelle riusate — vedi Javadoc del chiamante) ne' l'id JPA. */
    private void sovrascriviCampiVoce(VocePendenza target, VocePendenza candidata) {
        target.setIdVocePendenza(candidata.getIdVocePendenza());
        target.setImporto(candidata.getImporto());
        target.setDescrizione(candidata.getDescrizione());
        target.setIdDominio(candidata.getIdDominio());
        target.setIdTributo(candidata.getIdTributo());
        target.setIdIbanAccredito(candidata.getIdIbanAccredito());
        target.setIdIbanAppoggio(candidata.getIdIbanAppoggio());
        target.setTassonomia(candidata.getTassonomia());
        target.setTipoBollo(candidata.getTipoBollo());
        target.setHashDocumento(candidata.getHashDocumento());
        target.setProvinciaResidenza(candidata.getProvinciaResidenza());
        target.setDettaglioContabile(candidata.getDettaglioContabile());
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

    /**
     * Converte {@code NuovaPendenza} in entita' per
     * {@code POST .../posizioni-debitorie/{idA2A}/{idPosizioneDebitoria}/opzioni-pagamento/
     * {idOpzionePagamento}/pendenze} (aggiunta a un'opzione GIA' esistente — a differenza di
     * {@link #toOpzionePagamento(NuovaOpzionePagamento, Long, ApplicazioneEntity)}, qui
     * {@code idDominioPosizione} viene dal chiamante, che ha gia' risolto l'opzione presso
     * {@code PosizioneDebitoriaService#aggiungiPendenza} prima di costruire la pendenza).
     * Stessi controlli di risoluzione/abilitazione/autorizzazione del tipo pendenza di
     * {@link #toPendenza(NuovaPendenza, Long, ApplicazioneEntity)}: nessuna duplicazione,
     * delega al convertitore privato condiviso — stesso pattern di
     * {@link #toOpzionePagamento(String, Long, NuovaOpzionePagamento)}.
     *
     * @throws AnagraficaNonTrovataException se {@code idA2A} non corrisponde a nessuna
     *                                        applicazione
     */
    public it.govpay.pendenze.entity.Pendenza toPendenza(String idA2A, Long idDominioPosizione, NuovaPendenza dto) {
        ApplicazioneEntity applicazione = applicazioneRepository.findByCodApplicazione(idA2A)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna applicazione con idA2A [" + idA2A
                        + "]"));
        verificaDominioAbilitato(idDominioPosizione);
        return toPendenza(dto, idDominioPosizione, applicazione);
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
        // dominio: senza questo campo la creazione fallisce con 500 per ogni dominio il cui
        // prefisso usa quel placeholder.
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

    /**
     * {@code dto == null} e' un elemento {@code null} nell'array {@code voci} della richiesta
     * (es. {@code "voci": [null]}): la bean validation su {@code List<@Valid NuovaVocePendenza>}
     * cascata su ogni elemento non {@code null}, ma non rifiuta un elemento {@code null} in se'
     * — senza questo controllo, il successivo {@code instanceof} non matcha nessun ramo e
     * cade nell'{@code else} che chiama {@code dto.getClass()} su un riferimento nullo,
     * risultando in un 500 non gestito invece di un 400. Condiviso da creazione
     * ({@link #toPendenza}) e PUT ({@link #sostituisciVoci}), gli unici due chiamanti.
     */
    private VocePendenza toVocePendenza(NuovaVocePendenza dto, Long idDominioPosizione) {
        if (dto == null) {
            throw new ValidazioneNonSuperataException("un elemento di 'voci' non puo' essere null");
        }
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

    private static Double doubleValue(BigDecimal valore) {
        return valore == null ? null : valore.doubleValue();
    }

    // ── Entita' -> risposta ──────────────────────────────────────────────────

    /**
     * Risoluzione inversa di {@link #toDettaglioContabile}: le classi generate dallo YAML per
     * {@code dettaglioLetto} (schema di sola lettura, usato qui) e {@code Dettaglio} (schema di
     * sola scrittura, usato da {@link #toDettaglioContabile}) collassano sugli STESSI 4 nomi
     * Java per le varianti in comune ({@code DettaglioContabileCorrispettivoDL118}/
     * {@code IncassoTipico}/{@code Civilistico}/{@code ImportoNotifica} — verificato nel
     * sorgente generato: implementano entrambe le interfacce marker {@code Dettaglio} e
     * {@code DettaglioLetto}), quindi qui si riusano le stesse classi via i loro setter, non
     * se ne creano di nuove. {@code DettaglioContabileSconosciuto} (UNKNOWN_ENTRIES) e' invece
     * esclusiva della lettura: nessuna scrittura la produce mai.
     */
    private DettaglioLetto toDettaglioLetto(DettaglioContabile modello) {
        if (modello instanceof DettaglioContabile.CorrispettivoDl118 d) {
            DettaglioContabileCorrispettivoDL118 dto = new DettaglioContabileCorrispettivoDL118();
            dto.setTipo(TipoDettaglioContabile.CORRISPETTIVO_DL118);
            dto.setAnnoCompetenza(d.annoCompetenza());
            dto.setCodiceUfficio(d.codiceUfficio());
            dto.setCapitolo(d.capitolo());
            dto.setAccertamento(d.accertamento());
            dto.setArticolo(d.articolo());
            dto.setPianoFinanziario5Livello(d.pianoFinanziario5Livello());
            dto.setImporto(doubleValue(d.importo()));
            return dto;
        }
        if (modello instanceof DettaglioContabile.IncassoTipico d) {
            DettaglioContabileIncassoTipico dto = new DettaglioContabileIncassoTipico();
            dto.setTipo(TipoDettaglioContabile.INCASSO_TIPICO);
            dto.setAnnoCompetenza(d.annoCompetenza());
            dto.setCodiceUfficio(d.codiceUfficio());
            dto.setTipoIncasso(d.tipoIncasso());
            dto.setEmissioneFattura(d.emissioneFattura() == null ? null
                    : DettaglioContabileIncassoTipico.EmissioneFatturaEnum.fromValue(d.emissioneFattura()));
            dto.setNrDocumento(d.nrDocumento());
            dto.setImporto(doubleValue(d.importo()));
            return dto;
        }
        if (modello instanceof DettaglioContabile.Civilistico d) {
            DettaglioContabileCivilistico dto = new DettaglioContabileCivilistico();
            dto.setTipo(TipoDettaglioContabile.CIVILISTICO);
            dto.setAnnoCompetenza(d.annoCompetenza());
            dto.setCodiceUfficio(d.codiceUfficio());
            dto.setConto(d.conto());
            dto.setCommessa(d.commessa());
            dto.setNrDocumento(d.nrDocumento());
            dto.setImporto(doubleValue(d.importo()));
            return dto;
        }
        if (modello instanceof DettaglioContabile.SpeseNotifica d) {
            DettaglioContabileImportoNotifica dto = new DettaglioContabileImportoNotifica();
            dto.setTipo(TipoDettaglioContabile.SPESE_NOTIFICA);
            dto.setImporto(doubleValue(d.importo()));
            return dto;
        }
        if (modello instanceof DettaglioContabile.Sconosciuto d) {
            DettaglioContabileSconosciuto dto = new DettaglioContabileSconosciuto();
            dto.setTipo(TipoDettaglioContabile.UNKNOWN_ENTRIES);
            dto.setEntries(d.entries().stream().map(voce -> {
                DettaglioContabileSconosciutoEntriesInner entry = new DettaglioContabileSconosciutoEntriesInner();
                entry.setChiave(voce.chiave());
                entry.setValore(voce.valore());
                return entry;
            }).toList());
            return dto;
        }
        throw new IllegalStateException("tipo di DettaglioContabile non gestito: " + modello.getClass());
    }

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
     * Lo YAML impone {@code minItems: 1} su {@code soggettiDebitori}: va sempre copiato in
     * {@link #toDto}, anche per una posizione con un solo debitore.
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

    public it.govpay.pendenze.api.model.OpzionePagamento toOpzionePagamentoDto(
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
        // dataCaricamento non e' una colonna propria (vedi Javadoc di classe di Pendenza):
        // si deriva da dataCreazione, sempre valorizzata.
        dto.setDataCaricamento(entity.getDataCreazione().toLocalDate());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenzaAvviso(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        dto.setSpeseNotificaSendImporto(
                entity.getSendImportoTotale() == null ? null : BigDecimal.valueOf(entity.getSendImportoTotale()));
        dto.setSpeseNotificaSendDataAggiornamento(entity.getSendDataAggiornamento());
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
        dto.setSpeseNotificaSendImporto(
                entity.getSendImportoTotale() == null ? null : BigDecimal.valueOf(entity.getSendImportoTotale()));
        dto.setSpeseNotificaSendDataAggiornamento(entity.getSendDataAggiornamento());
        dto.setPosizioneDebitoria(toIndexDto(entity.getOpzionePagamento().getPosizioneDebitoria()));
        return dto;
    }

    /**
     * Costruisce {@code GET /pendenze/{idA2A}/{idPendenza}}: come {@link #toPendenzaIndexDto}
     * ma con in piu' {@code voci} (richiesto dallo schema {@code Pendenza}, a differenza di
     * {@code PendenzaIndex}) — l'unico punto di questa API che espone le voci in lettura,
     * quindi anche l'unico che chiama {@link #toVocePendenzaDto}/{@link #toDettaglioLetto}.
     *
     * @throws IllegalStateException se {@code entity.getOpzionePagamento()} e' {@code null} —
     *         stessa guardia di {@link #toPendenzaIndexDto}, stessa motivazione (nessun caso
     *         reale la esercita oggi).
     */
    public it.govpay.pendenze.api.model.Pendenza toPendenzaDto(it.govpay.pendenze.entity.Pendenza entity) {
        if (entity.getOpzionePagamento() == null) {
            throw new IllegalStateException("la pendenza [" + entity.getIdPendenza() + "] non ha un'opzione di "
                    + "pagamento (creata da v2/migrazione): non rappresentabile in Pendenza, il chiamante doveva "
                    + "escluderla prima di chiamare questo metodo");
        }
        it.govpay.pendenze.api.model.Pendenza dto = new it.govpay.pendenze.api.model.Pendenza();
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
        dto.setSpeseNotificaSendImporto(
                entity.getSendImportoTotale() == null ? null : BigDecimal.valueOf(entity.getSendImportoTotale()));
        dto.setSpeseNotificaSendDataAggiornamento(entity.getSendDataAggiornamento());
        dto.setPosizioneDebitoria(toIndexDto(entity.getOpzionePagamento().getPosizioneDebitoria()));
        dto.setVoci(entity.getVoci().stream().map(this::toVocePendenzaDto).toList());
        return dto;
    }

    /**
     * Costruisce la voce in lettura — tre varianti come in scrittura ({@link #toVocePendenza}),
     * ma discriminate da {@link VocePendenza#getTipoRiferimento()} (derivato, vedi Javadoc
     * dell'entita') invece che dal tipo del DTO in ingresso. {@code idDominio} torna
     * valorizzato solo se la voce ha un override esplicito (multi-beneficiario pagoPA) — mai
     * quello ereditato dalla posizione, che il chiamante gia' conosce (stessa asimmetria
     * lettura/scrittura di {@code VocePendenza.idDominio}, vedi il suo Javadoc).
     *
     * @throws IllegalStateException se la voce non ha ne' {@code idTributo} ne'
     *         {@code idIbanAccredito} ne' {@code tipoBollo} valorizzati — non dovrebbe mai
     *         accadere per una voce creata da questa libreria (uno dei tre e' sempre richiesto
     *         in scrittura).
     */
    private it.govpay.pendenze.api.model.VocePendenza toVocePendenzaDto(VocePendenza entity) {
        TipoRiferimentoVocePendenza tipoRiferimento = entity.getTipoRiferimento();
        StatoVocePendenza statoEntity = entity.getStato();
        it.govpay.pendenze.api.model.StatoVocePendenza stato = statoEntity == null ? null
                : it.govpay.pendenze.api.model.StatoVocePendenza.valueOf(statoEntity.name());
        String idDominio = risolviCodDominio(entity.getIdDominio());
        List<DettaglioLetto> dettaglioContabile = entity.getDettaglioContabile().stream()
                .map(this::toDettaglioLetto).toList();

        if (tipoRiferimento == TipoRiferimentoVocePendenza.RIFERIMENTO_ENTRATA) {
            VocePendenzaRiferimentoEntrata dto = new VocePendenzaRiferimentoEntrata();
            dto.setIdVocePendenza(entity.getIdVocePendenza());
            dto.setImporto(BigDecimal.valueOf(entity.getImporto()));
            dto.setDescrizione(entity.getDescrizione());
            dto.setIndice(BigDecimal.valueOf(entity.getIndice()));
            dto.setStato(stato);
            dto.setIdDominio(idDominio);
            dto.setCodEntrata(risolviCodEntrata(entity.getIdTributo()));
            dto.setDettaglioContabile(dettaglioContabile);
            return dto;
        }
        if (tipoRiferimento == TipoRiferimentoVocePendenza.ENTRATA) {
            VocePendenzaEntrata dto = new VocePendenzaEntrata();
            dto.setIdVocePendenza(entity.getIdVocePendenza());
            dto.setImporto(BigDecimal.valueOf(entity.getImporto()));
            dto.setDescrizione(entity.getDescrizione());
            dto.setIndice(BigDecimal.valueOf(entity.getIndice()));
            dto.setStato(stato);
            dto.setIdDominio(idDominio);
            dto.setIbanAccredito(risolviCodIban(entity.getIdIbanAccredito()));
            dto.setIbanAppoggio(risolviCodIban(entity.getIdIbanAppoggio()));
            dto.setTassonomia(entity.getTassonomia());
            dto.setDettaglioContabile(dettaglioContabile);
            return dto;
        }
        if (tipoRiferimento == TipoRiferimentoVocePendenza.BOLLO) {
            VocePendenzaBollo dto = new VocePendenzaBollo();
            dto.setIdVocePendenza(entity.getIdVocePendenza());
            dto.setImporto(BigDecimal.valueOf(entity.getImporto()));
            dto.setDescrizione(entity.getDescrizione());
            dto.setIndice(BigDecimal.valueOf(entity.getIndice()));
            dto.setStato(stato);
            dto.setIdDominio(idDominio);
            dto.setTipoBollo(VocePendenzaBollo.TipoBolloEnum.fromValue(entity.getTipoBollo()));
            dto.setHashDocumento(entity.getHashDocumento());
            dto.setProvinciaResidenza(entity.getProvinciaResidenza());
            dto.setTassonomia(entity.getTassonomia());
            return dto;
        }
        throw new IllegalStateException("la voce [" + entity.getIdVocePendenza() + "] non ha ne' idTributo ne' "
                + "idIbanAccredito ne' tipoBollo valorizzati: non rappresentabile in lettura");
    }
}
