package it.govpay.pendenze.v2.pendenza;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.TipoContabilita;
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
import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.comando.DatiOpzionePagamento;
import it.govpay.pendenze.comando.DatiPendenza;
import it.govpay.pendenze.comando.DatiPosizioneDebitoria;
import it.govpay.pendenze.comando.DatiSoggettoDebitore;
import it.govpay.pendenze.comando.DatiVocePendenza;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.VocePendenza;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.security.UtenzaDominioEntity;
import it.govpay.pendenze.security.UtenzaDominioRepository;
import it.govpay.pendenze.security.UtenzaEntity;
import it.govpay.pendenze.security.UtenzaRepository;
import it.govpay.pendenze.security.UtenzaTipoVersamentoRepository;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.v2.api.model.Contabilita;
import it.govpay.pendenze.v2.api.model.Metadata;
import it.govpay.pendenze.v2.api.model.NuovaPendenza;
import it.govpay.pendenze.v2.api.model.NuovaVocePendenza;
import it.govpay.pendenze.v2.api.model.NuovoDocumento;
import it.govpay.pendenze.v2.api.model.Soggetto;
import it.govpay.pendenze.v2.api.model.TipoSogliaVincoloPagamento;
import it.govpay.pendenze.v2.api.model.VincoloPagamento;
import it.govpay.pendenze.web.AccessoNegatoException;
import it.govpay.pendenze.web.AnagraficaNonTrovataException;

/**
 * Converte {@code NuovaPendenza} (PUT {@code /v2/pendenze/{idA2A}/{idPendenza}},
 * {@code addPendenza}) nei comandi di {@code PosizioneDebitoriaService} e applica i campi
 * "solo legacy" senza comando dedicato — stesso ruolo di {@link PendenzaV2Mapper} ma per il
 * verso di scrittura, e stesso principio di duplicazione deliberata delle risoluzioni di
 * anagrafica gia' visto fra {@code PosizioneDebitoriaMapper} (v3-nativo) e
 * {@link PendenzaV2Mapper} (lettura v2): quei metodi sono privati nell'altra classe, la
 * risoluzione stessa non e' logica specifica di v2, solo ripetuta qui.
 *
 * <h2>Decisioni di progetto (vedi proposta-modello-nativo-v3.md, sezione "addPendenza v2")</h2>
 * <ul>
 * <li><b>{@code documento} assente</b>: sintetizza un {@code codDocumento} = UUID random
 * senza trattini (32 hex), generato SOLO in creazione — mai ricalcolato in update.</li>
 * <li><b>Tipologia dell'opzione</b>: {@code documento.rata} presente -&gt; {@code PIANO_RATEALE}
 * (rata dichiarata validata contro la posizione attesa, 1 alla prima chiamata per un
 * documento nuovo, conteggio attuale+1 per le successive — errore esplicito 400, non
 * rinumerazione silenziosa); {@code documento.soglia} presente -&gt; {@code SOLUZIONE_UNICA_ENTRO}/
 * {@code _OLTRE}/{@code _RIDOTTO}/{@code _SCONTATO} secondo {@code soglia.tipo} ({@code giorni}
 * dalla soglia solo per {@code ENTRO}/{@code OLTRE}); nessuno dei due -&gt; {@code SOLUZIONE_UNICA}.
 * {@code rata} e {@code soglia} insieme: 400.</li>
 * <li><b>{@code documento.identificativo} che coincide con un documento esistente</b>:
 * aggiunge una pendenza all'opzione esistente ({@link PosizioneDebitoriaService#aggiungiPendenza}),
 * non crea una nuova posizione. Un mismatch di tipologia rispetto a quella decisa alla
 * creazione del documento e' un errore esplicito (400), la tipologia non cambia mai dopo la
 * creazione.</li>
 * <li><b>Update (idPendenza gia' esistente)</b>: nessuna logica qui, si limita a tradurre il
 * body in {@link AggiornamentoPendenza} per {@link PosizioneDebitoriaService#aggiornaPendenza}
 * — quel metodo di libreria tocca SOLO i campi della pendenza stessa (importo, numeroAvviso
 * se non gia' assegnato, dataValidita, dataScadenzaAvviso, voci). <b>Deviazione consapevole
 * dal legacy</b>: {@code documento}/{@code soggettoPagatore} dichiarati nel body di un PUT di
 * aggiornamento sono IGNORATI, non applicati e non un errore — il legacy li sovrascriverebbe
 * ("ultima scrittura vince"), qui si riusa deliberatamente il metodo di libreria cosi'
 * com'e' (si veda la memoria {@code feedback_wrapper_v2_fedelta_legacy} per la decisione).</li>
 * </ul>
 */
@Component
public class NuovaPendenzaMapper {

    /**
     * Stesso significato gia' usato per nascondere l'UO nelle risposte ({@code PendenzaV2Mapper
     * #risolviUnitaOperativaDto}): "EC" e' l'ente creditore stesso, non una vera unita'
     * operativa.
     */
    private static final String COD_UNITA_OPERATIVA_EC = "EC";

    private final ApplicazioneRepository applicazioneRepository;
    private final DominioRepository dominioRepository;
    private final UnitaOperativaRepository unitaOperativaRepository;
    private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository;
    private final TipoTributoRepository tipoTributoRepository;
    private final TributoRepository tributoRepository;
    private final IbanAccreditoRepository ibanAccreditoRepository;
    private final UtenzaRepository utenzaRepository;
    private final UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository;
    private final UtenzaDominioRepository utenzaDominioRepository;
    private final PosizioneDebitoriaRepository posizioneDebitoriaRepository;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final Clock clock;

    /** Solo per serializzare {@code datiAllegati}/{@code proprieta} (passthrough JSON verso le
     *  colonne grezze di {@code Pendenza}): stesso pattern di {@code RicevutaJsonConverter}
     *  (registra {@link JavaTimeModule} esplicitamente, un {@code ObjectMapper} semplice non lo
     *  fa da solo — serve per {@code ProprietaPendenza.dataScandenzaAvviso}, un {@code LocalDate}). */
    private final ObjectMapper jsonMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public NuovaPendenzaMapper(ApplicazioneRepository applicazioneRepository, DominioRepository dominioRepository,
            UnitaOperativaRepository unitaOperativaRepository,
            TipoVersamentoDominioRepository tipoVersamentoDominioRepository,
            TipoTributoRepository tipoTributoRepository, TributoRepository tributoRepository,
            IbanAccreditoRepository ibanAccreditoRepository, UtenzaRepository utenzaRepository,
            UtenzaTipoVersamentoRepository utenzaTipoVersamentoRepository,
            UtenzaDominioRepository utenzaDominioRepository,
            PosizioneDebitoriaRepository posizioneDebitoriaRepository,
            PosizioneDebitoriaService posizioneDebitoriaService, Clock clock) {
        this.applicazioneRepository = applicazioneRepository;
        this.dominioRepository = dominioRepository;
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.tipoVersamentoDominioRepository = tipoVersamentoDominioRepository;
        this.tipoTributoRepository = tipoTributoRepository;
        this.tributoRepository = tributoRepository;
        this.ibanAccreditoRepository = ibanAccreditoRepository;
        this.utenzaRepository = utenzaRepository;
        this.utenzaTipoVersamentoRepository = utenzaTipoVersamentoRepository;
        this.utenzaDominioRepository = utenzaDominioRepository;
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.clock = clock;
    }

    // ── Risoluzione anagrafiche esterne (duplicato deliberatamente, vedi Javadoc di classe) ──

    public ApplicazioneEntity risolviApplicazione(String idA2A) {
        return applicazioneRepository.findByCodApplicazione(idA2A)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessuna applicazione con idA2A [" + idA2A
                        + "]"));
    }

    private Long risolviIdDominioAbilitato(String idDominio) {
        DominioEntity dominio = dominioRepository.findByCodDominio(idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun dominio con idDominio [" + idDominio
                        + "]"));
        if (Boolean.FALSE.equals(dominio.getAbilitato())) {
            throw new ValidazioneNonSuperataException("il dominio [" + idDominio + "] non e' abilitato");
        }
        return dominio.getId();
    }

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

    private TipoVersamentoDominioEntity risolviTipoVersamentoDominio(String idTipoPendenza, Long idDominio) {
        TipoVersamentoDominioEntity tvd = tipoVersamentoDominioRepository
                .findByCodTipoVersamentoAndDominioId(idTipoPendenza, idDominio)
                .orElseThrow(() -> new AnagraficaNonTrovataException("nessun tipo pendenza [" + idTipoPendenza
                        + "] configurato per il dominio [id:" + idDominio + "]"));
        if (Boolean.FALSE.equals(tvd.getTipoVersamento().getAbilitato())) {
            throw new ValidazioneNonSuperataException("il tipo pendenza [" + idTipoPendenza + "] non e' abilitato");
        }
        if (Boolean.FALSE.equals(tvd.getAbilitato())) {
            throw new ValidazioneNonSuperataException("il tipo pendenza [" + idTipoPendenza
                    + "] non e' abilitato per il dominio [id:" + idDominio + "]");
        }
        return tvd;
    }

    /**
     * Verifica dominio E tipo versamento insieme — replica {@code AuthorizationManager
     * #isTipoVersamentoDominioAuthorized} del legacy ({@code PendenzeDAO#patch}, riga 591,
     * stesso controllo anche su {@code createOrUpdate}/{@code leggiPendenza}): un'applicazione
     * non "trusted" deve essere autorizzata SIA sul dominio SIA sul tipo versamento, altrimenti
     * 403 ({@link AccessoNegatoException}, non 400 — il legacy lancia qui
     * {@code NotAuthorizedException}). Unico punto usato da {@link #crea}/
     * {@link #toAggiornamentoPendenza} (PUT) e da {@code PendenzaV2Controller#updatePendenza}
     * (PATCH, chiamato direttamente su {@code idDominio}/{@code idTipoVersamento} della
     * pendenza esistente, prima di qualunque operazione — stesso ordine del legacy).
     * Un'applicazione "trusted" bypassa entrambi i controlli, stesso principio del legacy.
     */
    public void verificaAutorizzazioneDominioETipoVersamento(ApplicazioneEntity applicazione, Long idDominio,
            Long idTipoVersamento) {
        if (Boolean.TRUE.equals(applicazione.getTrusted())) {
            return;
        }
        UtenzaEntity utenza = utenzaRepository.findById(applicazione.getIdUtenza())
                .orElseThrow(() -> new IllegalStateException("applicazione [id:" + applicazione.getId()
                        + "] non ha un'utenza valida (idUtenza [" + applicazione.getIdUtenza() + "])"));
        if (!Boolean.TRUE.equals(utenza.getAutorizzazioneDominiStar()) && !autorizzataSulDominio(utenza, idDominio)) {
            throw new AccessoNegatoException("l'applicazione [" + applicazione.getCodApplicazione()
                    + "] non e' autorizzata al dominio [id:" + idDominio + "]");
        }
        if (!Boolean.TRUE.equals(utenza.getAutorizzazioneTipiVersStar())
                && !utenzaTipoVersamentoRepository.existsByIdUtenzaAndIdTipoVersamento(utenza.getId(),
                        idTipoVersamento)) {
            throw new AccessoNegatoException("l'applicazione [" + applicazione.getCodApplicazione()
                    + "] non e' autorizzata alla gestione del tipo pendenza [id:" + idTipoVersamento + "]");
        }
    }

    /**
     * Stessa risoluzione di {@code ProfiloMapper#risolviDomini}, duplicata deliberatamente
     * (vedi Javadoc di classe): una riga di {@code utenze_domini} ha {@code idDominio} oppure
     * {@code idUo} (mai entrambi) — quando e' una UO il dominio autorizzato e' quello del
     * padre.
     */
    private boolean autorizzataSulDominio(UtenzaEntity utenza, Long idDominio) {
        for (UtenzaDominioEntity riga : utenzaDominioRepository.findByIdUtenza(utenza.getId())) {
            if (idDominio.equals(riga.getIdDominio())) {
                return true;
            }
            if (riga.getIdUo() != null) {
                Optional<UnitaOperativaEntity> uo = unitaOperativaRepository.findById(riga.getIdUo());
                if (uo.isPresent() && idDominio.equals(uo.get().getDominio().getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String codificaIuvEffettiva(TipoVersamentoDominioEntity tvd) {
        return tvd.getCodificaIuv() != null ? tvd.getCodificaIuv() : tvd.getTipoVersamento().getCodificaIuv();
    }

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

    private OffsetDateTime aInizioGiorno(LocalDate data) {
        return data == null ? null : data.atStartOfDay(clock.getZone()).toOffsetDateTime();
    }

    // ── Ramo di creazione (idPendenza non ancora esistente) ─────────────────────────────

    /**
     * Crea la pendenza, sintetizzando o riusando il documento secondo le decisioni di
     * progetto (vedi Javadoc di classe). Restituisce l'entita' gestita appena
     * creata/aggiunta, nella stessa transazione del chiamante.
     */
    public Pendenza crea(String idA2A, String idPendenza, NuovaPendenza dto, ApplicazioneEntity applicazione) {
        if (dto.getVoci() == null || dto.getVoci().isEmpty()) {
            throw new ValidazioneNonSuperataException("'voci' deve contenere almeno un elemento");
        }
        Long idDominio = risolviIdDominioAbilitato(dto.getIdDominio());

        NuovoDocumento documento = dto.getDocumento();
        boolean documentoEsplicito = documento != null;
        Integer rataRichiesta = documentoEsplicito ? documento.getRata() : null;
        VincoloPagamento soglia = documentoEsplicito ? documento.getSoglia() : null;
        String codDocumento;
        if (documentoEsplicito) {
            codDocumento = documento.getIdentificativo();
        } else {
            // Decisione #1: v3 non ammette una Pendenza senza PosizioneDebitoria/
            // OpzionePagamento. UUID random SENZA trattini (32 hex, entro cod_documento
            // VARCHAR(35)) generato SOLO qui, in creazione — mai ricalcolato in update (in
            // update la pendenza esiste gia', si risale al documento gia' persistito).
            // Deliberatamente diverso dal prefisso "MIGR-" usato dalla migrazione (riservato
            // a quel percorso, vedi PendenzaV2Mapper.PREFISSO_DOCUMENTO_SINTETIZZATO).
            codDocumento = UUID.randomUUID().toString().replace("-", "");
        }
        String descrizioneDocumento = risolviDescrizioneDocumento(dto);

        Optional<PosizioneDebitoria> posizioneEsistente = posizioneDebitoriaRepository
                .findByIdApplicazioneAndIdDominioAndCodDocumento(applicazione.getId(), idDominio, codDocumento);

        DatiPendenza datiPendenza = toDatiPendenza(idPendenza, dto, idDominio, applicazione);

        if (posizioneEsistente.isPresent()) {
            // Decisione #3: un documento esplicito che coincide con uno gia' esistente
            // aggiunge una pendenza all'opzione esistente, non crea una nuova posizione.
            return aggiungiAPosizioneEsistente(idA2A, posizioneEsistente.get(), rataRichiesta, soglia, datiPendenza,
                    descrizioneDocumento);
        }

        TipologiaEGiorni tipologiaEGiorni = risolviTipologia(rataRichiesta, soglia);
        if (tipologiaEGiorni.tipologia() == TipologiaOpzionePagamento.PIANO_RATEALE && rataRichiesta != 1) {
            throw new ValidazioneNonSuperataException("documento.rata [" + rataRichiesta + "] non valida: per un "
                    + "documento nuovo la prima rata deve essere 1");
        }

        DatiOpzionePagamento datiOpzione = new DatiOpzionePagamento(tipologiaEGiorni.tipologia(),
                tipologiaEGiorni.giorni(), null, null, List.of(datiPendenza));
        Long idUnitaOperativa = risolviIdUnitaOperativa(idDominio, dto.getIdUnitaOperativa());
        DatiSoggettoDebitore soggetto = toDatiSoggettoDebitore(dto.getSoggettoPagatore());
        DatiPosizioneDebitoria comando = new DatiPosizioneDebitoria(codDocumento, applicazione.getId(), idDominio,
                idUnitaOperativa, descrizioneDocumento, null, false, null, List.of(soggetto), List.of(datiOpzione));

        PosizioneDebitoria creata = posizioneDebitoriaService.crea(comando);
        return creata.getOpzioniPagamento().get(0).getPendenze().get(0);
    }

    /**
     * {@code documento.descrizione} se {@code documento} e' presente, altrimenti {@code causale}
     * — 'documenti.descrizione' e' {@code NOT NULL} nello schema, e {@code causale} e' sempre
     * obbligatoria su {@code NuovaPendenza}, quindi garantisce sempre un valore.
     */
    static String risolviDescrizioneDocumento(NuovaPendenza dto) {
        NuovoDocumento documento = dto.getDocumento();
        return documento != null ? documento.getDescrizione() : dto.getCausale();
    }

    /**
     * Decisione #3 (continua): calcola la tipologia richiesta da {@code rata}/{@code soglia} e
     * cerca, fra tutte le opzioni di pagamento del documento, quella con la stessa tipologia.
     * Una sola corrispondenza: aggiunge la pendenza li' (se {@code PIANO_RATEALE}, la rata
     * dichiarata deve coincidere con la prossima posizione attesa — errore esplicito, nessuna
     * rinumerazione silenziosa). Nessuna corrispondenza ma una complementare presente
     * ({@code ENTRO}/{@code OLTRE} o {@code RIDOTTO}/{@code SCONTATO}): crea una seconda
     * opzione ({@link #completaCoppiaSoglia}). Nessuna corrispondenza e nessuna complementare,
     * o piu' di una corrispondenza: errore esplicito (400). {@code descrizioneDocumento}
     * sovrascrive sempre quella della posizione esistente, indipendentemente dall'esito sopra.
     */
    private Pendenza aggiungiAPosizioneEsistente(String idA2A, PosizioneDebitoria posizione, Integer rataRichiesta,
            VincoloPagamento soglia, DatiPendenza datiPendenza, String descrizioneDocumento) {
        posizione.setDescrizione(descrizioneDocumento);
        TipologiaEGiorni tipologiaEGiorni = risolviTipologia(rataRichiesta, soglia);
        List<OpzionePagamento> opzioni = posizione.getOpzioniPagamento();

        List<OpzionePagamento> corrispondenti = opzioni.stream()
                .filter(o -> o.getTipologia() == tipologiaEGiorni.tipologia())
                .toList();
        if (corrispondenti.size() > 1) {
            throw new ValidazioneNonSuperataException("il documento [" + posizione.getCodDocumento() + "] ha "
                    + corrispondenti.size() + " opzioni di tipologia " + tipologiaEGiorni.tipologia()
                    + ": riferimento ambiguo, non e' possibile scegliere a quale aggiungere la pendenza");
        }
        if (corrispondenti.size() == 1) {
            OpzionePagamento opzione = corrispondenti.get(0);
            if (tipologiaEGiorni.tipologia() == TipologiaOpzionePagamento.PIANO_RATEALE) {
                int prossimaRata = opzione.getPendenze().stream().mapToInt(Pendenza::getNumeroRata).max()
                        .orElse(0) + 1;
                if (rataRichiesta != prossimaRata) {
                    throw new ValidazioneNonSuperataException("documento.rata [" + rataRichiesta + "] non valida: "
                            + "la prossima rata attesa per il documento [" + posizione.getCodDocumento() + "] e' ["
                            + prossimaRata + "]");
                }
            }
            String idPosizioneDebitoriaPubblica = posizioneDebitoriaService.idPosizioneDebitoriaPubblica(posizione);
            return posizioneDebitoriaService.aggiungiPendenza(idA2A, idPosizioneDebitoriaPubblica,
                    opzione.getIdOpzionePagamento(), datiPendenza);
        }

        boolean complementarePresente = opzioni.stream()
                .anyMatch(o -> sonoSoglieComplementari(o.getTipologia(), tipologiaEGiorni.tipologia()));
        if (complementarePresente) {
            return completaCoppiaSoglia(idA2A, posizione, tipologiaEGiorni, datiPendenza);
        }
        throw new ValidazioneNonSuperataException("il documento [" + posizione.getCodDocumento() + "] non ha "
                + "un'opzione di tipologia " + tipologiaEGiorni.tipologia() + " ne' una complementare a cui "
                + "aggiungere la pendenza (tipologie presenti: " + opzioni.stream().map(o -> o.getTipologia()
                        .toString()).collect(Collectors.joining(", ")) + ")");
    }

    private record TipologiaEGiorni(TipologiaOpzionePagamento tipologia, Integer giorni) {
    }

    /**
     * {@code true} se le due tipologie sono la coppia {@code ENTRO}/{@code OLTRE} o la coppia
     * {@code RIDOTTO}/{@code SCONTATO} (in qualunque ordine) — condizione per completare la
     * coppia su un documento esistente con {@link #completaCoppiaSoglia} invece di rifiutare la
     * seconda soglia con 400.
     */
    private static boolean sonoSoglieComplementari(TipologiaOpzionePagamento a, TipologiaOpzionePagamento b) {
        return (a == TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO && b == TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE)
                || (a == TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE && b == TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO)
                || (a == TipologiaOpzionePagamento.SOLUZIONE_UNICA_RIDOTTO && b == TipologiaOpzionePagamento.SOLUZIONE_UNICA_SCONTATO)
                || (a == TipologiaOpzionePagamento.SOLUZIONE_UNICA_SCONTATO && b == TipologiaOpzionePagamento.SOLUZIONE_UNICA_RIDOTTO);
    }

    /**
     * Seconda soglia dichiarata su un documento esistente con una sola opzione DISPONIBILE di
     * tipologia complementare: aggiunge una SECONDA opzione alla posizione (le due soglie sono
     * alternative distinte nel modello v3, non due pendenze della stessa opzione), tramite
     * {@link PosizioneDebitoriaService#aggiungiOpzionePagamento}.
     */
    private Pendenza completaCoppiaSoglia(String idA2A, PosizioneDebitoria posizione,
            TipologiaEGiorni tipologiaEGiorni, DatiPendenza datiPendenza) {
        String idPosizioneDebitoriaPubblica = posizioneDebitoriaService.idPosizioneDebitoriaPubblica(posizione);
        DatiOpzionePagamento comando = new DatiOpzionePagamento(tipologiaEGiorni.tipologia(),
                tipologiaEGiorni.giorni(), null, null, List.of(datiPendenza));
        OpzionePagamento nuovaOpzione = posizioneDebitoriaService.aggiungiOpzionePagamento(idA2A,
                idPosizioneDebitoriaPubblica, comando);
        return nuovaOpzione.getPendenze().get(0);
    }

    /**
     * {@code rata} e {@code soglia} mutuamente esclusivi: 400 se entrambi presenti.
     * {@code rata} -&gt; {@code PIANO_RATEALE}; {@code soglia.tipo} {@code ENTRO}/{@code OLTRE}
     * -&gt; {@code SOLUZIONE_UNICA_ENTRO}/{@code _OLTRE} con {@code giorni} dalla soglia,
     * obbligatorio (400 se assente — lo schema non puo' esprimere questo vincolo condizionale);
     * {@code RIDOTTO}/{@code SCONTATO} -&gt; {@code SOLUZIONE_UNICA_RIDOTTO}/{@code _SCONTATO},
     * {@code giorni} ignorato se presente; nessuno dei due -&gt; {@code SOLUZIONE_UNICA}.
     */
    private TipologiaEGiorni risolviTipologia(Integer rataRichiesta, VincoloPagamento soglia) {
        if (rataRichiesta != null && soglia != null) {
            throw new ValidazioneNonSuperataException(
                    "documento.rata e documento.soglia sono mutuamente esclusivi");
        }
        if (rataRichiesta != null) {
            return new TipologiaEGiorni(TipologiaOpzionePagamento.PIANO_RATEALE, null);
        }
        if (soglia != null) {
            return switch (soglia.getTipo()) {
                case ENTRO, OLTRE -> {
                    if (soglia.getGiorni() == null) {
                        throw new ValidazioneNonSuperataException(
                                "documento.soglia.giorni e' obbligatorio per soglia.tipo [" + soglia.getTipo() + "]");
                    }
                    yield new TipologiaEGiorni(soglia.getTipo() == TipoSogliaVincoloPagamento.ENTRO
                            ? TipologiaOpzionePagamento.SOLUZIONE_UNICA_ENTRO
                            : TipologiaOpzionePagamento.SOLUZIONE_UNICA_OLTRE, soglia.getGiorni());
                }
                // giorni non e' un valore dichiarabile per RIDOTTO/SCONTATO: ignorato se presente.
                case RIDOTTO -> new TipologiaEGiorni(TipologiaOpzionePagamento.SOLUZIONE_UNICA_RIDOTTO, null);
                case SCONTATO -> new TipologiaEGiorni(TipologiaOpzionePagamento.SOLUZIONE_UNICA_SCONTATO, null);
            };
        }
        return new TipologiaEGiorni(TipologiaOpzionePagamento.SOLUZIONE_UNICA, null);
    }

    private DatiPendenza toDatiPendenza(String idPendenza, NuovaPendenza dto, Long idDominio,
            ApplicazioneEntity applicazione) {
        TipoVersamentoDominioEntity tvd = risolviTipoVersamentoDominio(dto.getIdTipoPendenza(), idDominio);
        verificaAutorizzazioneDominioETipoVersamento(applicazione, idDominio, tvd.getTipoVersamento().getId());
        List<DatiVocePendenza> voci = toDatiVoci(dto.getVoci(), idDominio);
        return new DatiPendenza(idPendenza, tvd.getId(), tvd.getTipoVersamento().getId(), codificaIuvEffettiva(tvd),
                dto.getImporto(), dto.getNumeroAvviso(), null, aInizioGiorno(dto.getDataValidita()),
                aInizioGiorno(dto.getDataScadenza()), voci, dto.getCausale());
    }

    private DatiSoggettoDebitore toDatiSoggettoDebitore(Soggetto dto) {
        return new DatiSoggettoDebitore(it.govpay.pendenze.model.TipoSoggetto.valueOf(dto.getTipo().name()),
                dto.getIdentificativo(), dto.getAnagrafica(), dto.getIndirizzo(), dto.getCivico(), dto.getCap(),
                dto.getLocalita(), dto.getProvincia(), dto.getNazione(), dto.getEmail(), dto.getCellulare());
    }

    // ── Ramo di aggiornamento (idPendenza gia' esistente) ───────────────────────────────

    /**
     * Converte il PUT in {@link AggiornamentoPendenza} per
     * {@link PosizioneDebitoriaService#aggiornaPendenza}, che e' l'unico punto che applica
     * davvero la modifica. <b>{@code documento.identificativo}/{@code soggettoPagatore} del
     * body non sono letti qui</b>: deviazione consapevole dal legacy (vedi Javadoc di classe)
     * — se il chiamante li dichiara diversi dall'originale, sono semplicemente ignorati, non
     * un errore. <b>{@code documento.descrizione}/{@code causale} fanno eccezione</b>
     * (decisione del committente del 2026-10-27): sovrascrivono sempre la descrizione della
     * posizione esistente — vedi {@link PendenzaV2Controller#addPendenza}, che applica questa
     * sovrascrittura separatamente, non tramite questo comando (che non porta campi della
     * posizione). <b>{@code idDominio}/{@code idUnitaOperativa} invece NON sono ignorati</b>:
     * vedi {@link #validaBeneficiarioInvariato}, da chiamare dal controller PRIMA di questo
     * metodo.
     */
    public AggiornamentoPendenza toAggiornamentoPendenza(String idPendenza, NuovaPendenza dto, Long idDominio,
            ApplicazioneEntity applicazione) {
        if (dto.getVoci() == null || dto.getVoci().isEmpty()) {
            throw new ValidazioneNonSuperataException("'voci' deve contenere almeno un elemento");
        }
        TipoVersamentoDominioEntity tvd = risolviTipoVersamentoDominio(dto.getIdTipoPendenza(), idDominio);
        verificaAutorizzazioneDominioETipoVersamento(applicazione, idDominio, tvd.getTipoVersamento().getId());
        List<DatiVocePendenza> voci = toDatiVoci(dto.getVoci(), idDominio);
        return new AggiornamentoPendenza(idPendenza, tvd.getId(), tvd.getTipoVersamento().getId(),
                codificaIuvEffettiva(tvd), dto.getImporto(), dto.getNumeroAvviso(),
                aInizioGiorno(dto.getDataValidita()), aInizioGiorno(dto.getDataScadenza()), voci, dto.getCausale());
    }

    /**
     * Rifiuta un aggiornamento che proverebbe a cambiare il beneficiario (dominio + unita'
     * operativa) di una pendenza esistente — stessa validazione del legacy
     * ({@code VersamentoUtils#validazioneSemanticaAggiornamento}, righe 167-172: confronto su
     * {@code idUo}, che implica il dominio — un'unita' operativa appartiene a un solo dominio —
     * {@code GovPayException(VER_004)}). A differenza della decisione sul Javadoc di classe
     * ({@code documento}/{@code soggettoPagatore} ignorati): qui non si tratta di un campo
     * ridondante ignorabile, ma del contenitore ({@link PosizioneDebitoria}) a cui la pendenza
     * appartiene — cambiarlo silenziosamente (o peggio, ignorarlo lasciando la risposta
     * riferita al valore richiesto invece che a quello reale) produce una risposta incoerente.
     * Va chiamato dal controller PRIMA di {@link #toAggiornamentoPendenza}, con la
     * {@link PosizioneDebitoria} della pendenza esistente (non quella — eventualmente
     * inesistente o diversa — dichiarata nel body).
     */
    public void validaBeneficiarioInvariato(NuovaPendenza dto, PosizioneDebitoria posizioneEsistente) {
        Long idDominioRichiesto = risolviIdDominioAbilitato(dto.getIdDominio());
        if (!idDominioRichiesto.equals(posizioneEsistente.getIdDominio())) {
            throw new ValidazioneNonSuperataException("non e' consentito modificare il dominio di una pendenza "
                    + "esistente (richiesto [" + dto.getIdDominio() + "], originale [id:"
                    + posizioneEsistente.getIdDominio() + "])");
        }
        Long idUnitaOperativaRichiesta = risolviIdUnitaOperativa(idDominioRichiesto, dto.getIdUnitaOperativa());
        if (!Objects.equals(normalizzaUnitaOperativaEc(idUnitaOperativaRichiesta),
                normalizzaUnitaOperativaEc(posizioneEsistente.getIdUnitaOperativa()))) {
            throw new ValidazioneNonSuperataException("non e' consentito modificare l'unita' operativa di una "
                    + "pendenza esistente");
        }
    }

    /**
     * {@code null} e l'id della UO "EC" sono equivalenti per questo confronto: il legacy
     * interpreta l'omissione di {@code idUnitaOperativa} come EC (l'ente creditore stesso), e
     * questo wrapper la nasconde comunque nelle risposte ({@code PendenzaV2Mapper
     * #risolviUnitaOperativaDto}) — un aggiornamento che omette il campo su una pendenza gia'
     * associata a EC non e' un cambio di beneficiario, va ammesso.
     */
    private Long normalizzaUnitaOperativaEc(Long idUnitaOperativa) {
        if (idUnitaOperativa == null) {
            return null;
        }
        boolean ec = unitaOperativaRepository.findById(idUnitaOperativa).map(UnitaOperativaEntity::getCodUo)
                .map(COD_UNITA_OPERATIVA_EC::equals).orElse(false);
        return ec ? null : idUnitaOperativa;
    }

    // ── Voci (comuni a creazione e aggiornamento) ───────────────────────────────────────

    private List<DatiVocePendenza> toDatiVoci(List<NuovaVocePendenza> voci, Long idDominioPosizione) {
        List<DatiVocePendenza> risultato = new ArrayList<>();
        for (NuovaVocePendenza voce : voci) {
            if (voce == null) {
                throw new ValidazioneNonSuperataException("un elemento di 'voci' non puo' essere null");
            }
            risultato.add(toDatiVocePendenza(voce, idDominioPosizione));
        }
        return risultato;
    }

    /**
     * Smistamento identico, per priorita', a {@code PendenzeConverter
     * .fillSingoliVersamentiFromVociPendenza} del legacy: bollo (se tutti e tre i campi sono
     * presenti) prima di codEntrata, prima del ramo IBAN diretto per default. <b>Deviazione
     * deliberata dal legacy</b>: se nessuno dei tre rami e' identificabile, il legacy va in
     * NPE non gestita su {@code getTipoContabilita().name()} (bug, verificato in
     * {@code VersamentiBD}); qui si solleva invece un errore di validazione esplicito (400) —
     * stesso principio gia' adottato altrove in questa libreria (es.
     * {@code PosizioneDebitoriaMapper#toVocePendenza} per un elemento {@code null} della
     * lista), per evitare un 500 non gestito al posto di un 400 controllato.
     */
    private DatiVocePendenza toDatiVocePendenza(NuovaVocePendenza v, Long idDominioPosizione) {
        verificaCampiVoceSenzaEquivalenteV3(v);
        Long idDominioEffettivo = v.getIdDominio() != null ? risolviIdDominioAbilitato(v.getIdDominio())
                : idDominioPosizione;
        Long idTributo = null;
        Long idIbanAccredito = null;
        Long idIbanAppoggio = null;
        String tipoBollo = null;
        String hashDocumento = null;
        String provinciaResidenza = null;

        if (v.getTipoBollo() != null && v.getHashDocumento() != null && v.getProvinciaResidenza() != null) {
            tipoBollo = v.getTipoBollo().getValue();
            hashDocumento = v.getHashDocumento();
            provinciaResidenza = v.getProvinciaResidenza();
        } else if (v.getCodEntrata() != null) {
            idTributo = risolviIdTributo(v.getCodEntrata(), idDominioEffettivo);
        } else if (v.getIbanAccredito() != null && v.getTipoContabilita() != null
                && v.getCodiceContabilita() != null) {
            idIbanAccredito = risolviIdIban(v.getIbanAccredito(), idDominioEffettivo);
            idIbanAppoggio = risolviIdIban(v.getIbanAppoggio(), idDominioEffettivo);
        } else {
            throw new ValidazioneNonSuperataException("la voce [" + v.getIdVocePendenza() + "] non indica ne' un "
                    + "bollo (tipoBollo+hashDocumento+provinciaResidenza), ne' codEntrata, ne' un IBAN diretto "
                    + "(ibanAccredito+tipoContabilita+codiceContabilita)");
        }

        return new DatiVocePendenza(v.getIdVocePendenza(), v.getImporto(), v.getDescrizione(), idDominioEffettivo,
                idTributo, idIbanAccredito, idIbanAppoggio, null, tipoBollo, hashDocumento, provinciaResidenza,
                List.of());
    }

    /**
     * {@code voci[].datiAllegati}/{@code contabilita}/{@code metadata} (v2) non hanno alcun
     * campo equivalente in {@code DatiVocePendenza}/{@code VocePendenza} (v3) —
     * {@code VocePendenza.dettaglioContabile} mappa la STESSA colonna fisica
     * {@code contabilita} ma per un concetto diverso e tipizzato (riconciliazione pagoPA
     * v3-nativa, non il JSON libero v2): non e' nemmeno disponibile come passthrough, a
     * differenza di {@code Pendenza.datiAllegati}/{@code proprieta} (livello pendenza, non
     * voce). Decisione del committente: una richiesta che popola uno di questi campi con dati
     * reali va rifiutata esplicitamente (400) — non accettata con successo e perduta
     * silenziosamente.
     */
    private void verificaCampiVoceSenzaEquivalenteV3(NuovaVocePendenza v) {
        if (v.getDatiAllegati() != null) {
            throw new ValidazioneNonSuperataException("la voce [" + v.getIdVocePendenza() + "] indica "
                    + "'datiAllegati': nessun campo equivalente in questo modello v3, non puo' essere persistito");
        }
        Contabilita contabilita = v.getContabilita();
        if (contabilita != null && ((contabilita.getQuote() != null && !contabilita.getQuote().isEmpty())
                || contabilita.getProprietaCustom() != null)) {
            throw new ValidazioneNonSuperataException("la voce [" + v.getIdVocePendenza() + "] indica "
                    + "'contabilita': nessun campo equivalente in questo modello v3, non puo' essere persistito");
        }
        Metadata metadata = v.getMetadata();
        if (metadata != null && metadata.getMapEntries() != null && !metadata.getMapEntries().isEmpty()) {
            throw new ValidazioneNonSuperataException("la voce [" + v.getIdVocePendenza() + "] indica "
                    + "'metadata': nessun campo equivalente in questo modello v3, non puo' essere persistito");
        }
    }

    // ── Campi "solo legacy" senza comando dedicato: diretti sull'entity gestita ─────────

    /**
     * Applica, con i setter dell'entity, i campi di {@code Pendenza}/{@code VocePendenza} che
     * non hanno un comando dedicato nella libreria ({@code direzione}, {@code divisione},
     * {@code tassonomia}, {@code tassonomiaAvviso}, {@code datiAllegati}, {@code codLotto},
     * {@code codAnnoTributario}, {@code proprieta}, {@code sendAbilitato} sulla pendenza;
     * {@code tipoContabilita}/{@code codiceContabilita}/{@code descrizioneCausaleRpt} sulle
     * sue voci, appaiate per {@code idVocePendenza}). {@code causaleVersamento} NON e' qui:
     * ha un comando dedicato ({@code DatiPendenza.causale()}/{@code
     * AggiornamentoPendenza.causale()}), applicato da {@code crea}/{@code aggiungiPendenza}/
     * {@code aggiornaPendenza} stessi. Va chiamato dopo quelle chiamate, nella stessa
     * transazione: l'entity restituita e' gestita, questi setter diretti vengono scaricati su
     * commit senza bisogno di un {@code save} esplicito.
     *
     * <p>Applicato SIA in creazione SIA in aggiornamento (a differenza di {@code documento}/
     * {@code soggettoPagatore}, ignorati in aggiornamento): questi sono campi della PENDENZA
     * stessa, non della posizione/del documento — nessuna delle due deviazioni decise dal
     * committente (decisione #4) li riguarda, quindi qui si resta fedeli al comportamento
     * del legacy, che li sovrascrive a ogni chiamata.</p>
     */
    public void applicaCampiLegacy(Pendenza pendenza, NuovaPendenza dto) {
        pendenza.setDirezione(dto.getDirezione());
        pendenza.setDivisione(dto.getDivisione());
        pendenza.setTassonomia(dto.getTassonomia());
        pendenza.setTassonomiaAvviso(dto.getTassonomiaAvviso() == null ? null : dto.getTassonomiaAvviso().getValue());
        pendenza.setDatiAllegati(toJson(dto.getDatiAllegati()));
        pendenza.setCodLotto(dto.getCartellaPagamento());
        pendenza.setCodAnnoTributario(dto.getAnnoRiferimento() == null ? null
                : String.valueOf(dto.getAnnoRiferimento()));
        pendenza.setProprieta(toJson(dto.getProprieta()));
        pendenza.setSendAbilitato(Boolean.TRUE.equals(dto.getSendAbilitato()));

        Map<String, NuovaVocePendenza> vociPerId = dto.getVoci().stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(NuovaVocePendenza::getIdVocePendenza, v -> v, (a, b) -> a));
        for (VocePendenza voce : pendenza.getVoci()) {
            NuovaVocePendenza origine = vociPerId.get(voce.getIdVocePendenza());
            if (origine == null) {
                // Voce riusata di un aggiornamento che non la menziona piu' nella richiesta
                // corrente (sostituisciVoci l'ha gia' rimossa, non puo' capitare qui), oppure
                // -- teoricamente -- un idVocePendenza che non matcha per un bug altrove: per
                // sicurezza si lascia l'entity cosi' com'e', nessun campo legacy da applicare.
                continue;
            }
            if (origine.getTipoContabilita() != null) {
                voce.setTipoContabilita(TipoContabilita.valueOf(origine.getTipoContabilita().name()));
            }
            voce.setCodiceContabilita(origine.getCodiceContabilita());
            voce.setDescrizioneCausaleRpt(origine.getDescrizioneCausaleRPT());
        }
    }

    private String toJson(Object valore) {
        if (valore == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(valore);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("errore di serializzazione JSON", e);
        }
    }
}
