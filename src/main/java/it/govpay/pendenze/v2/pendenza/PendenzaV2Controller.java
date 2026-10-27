package it.govpay.pendenze.v2.pendenza;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.metrics.ExternalCallMetricsRecorder;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.avviso.AvvisoAnagraficaAssenteException;
import it.govpay.pendenze.avviso.AvvisoDocumentoNonDisponibileException;
import it.govpay.pendenze.avviso.AvvisoMbtException;
import it.govpay.pendenze.avviso.AvvisoPdfPayloadMapper;
import it.govpay.pendenze.avviso.StampeClient;
import it.govpay.pendenze.comando.AggiornamentoPendenza;
import it.govpay.pendenze.criteri.FiltriPendenze;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.exception.RicercaNonUnivocaException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.TransizioneStatoNonAmmessaException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.PosizioneDebitoriaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.v2.api.model.LinguaSecondaria;
import it.govpay.pendenze.v2.api.model.NuovaPendenza;
import it.govpay.pendenze.v2.api.model.NuovoAllegatoPendenza;
import it.govpay.pendenze.v2.api.model.PatchOp;
import it.govpay.pendenze.v2.api.model.PendenzaCreata;
import it.govpay.pendenze.v2.api.model.Pendenze;
import it.govpay.pendenze.v2.api.model.PendenzaIndex;
import it.govpay.pendenze.v2.api.model.ProprietaPendenza;
import it.govpay.pendenze.v2.api.model.RppIndex;
import it.govpay.pendenze.v2.api.model.StatoPendenza;
import it.govpay.pendenze.v2.api.rest.PendenzeApi;
import it.govpay.pendenze.v2.ricevuta.RppMapper;
import it.govpay.pendenze.web.NotAcceptableMediaTypeException;
import it.govpay.pendenze.web.SelezioneCampi;
import it.govpay.stampe.client.model.PaymentNotice;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link PendenzeApi}: per ora {@link #findPendenze}/{@link #getPendenza}/
 * {@link #getPendenzaByAvviso}/{@link #addPendenza}/{@link #updatePendenza}/
 * {@link #getAvvisiDocumento}, le altre operazioni restano sul default generato (501) fino
 * al loro sviluppo — stesso approccio incrementale di
 * {@code it.govpay.pendenze.pendenza.PendenzaController} (v3).
 *
 * <p>Riusa deliberatamente l'infrastruttura trasversale del servizio (sicurezza,
 * risoluzione della pendenza, gestione degli errori): nessuna di queste e' specifica della
 * v3, sono la stessa autenticazione/ACL e lo stesso {@code ProblemExceptionHandler} con cui
 * risponde anche la v2 (il suo schema prevede {@code application/problem+json} negli stessi
 * punti). Solo {@link PendenzaV2Mapper}/{@link RppMapper}/{@link AllegatoEntity} sono
 * scoped al wrapper.</p>
 */
@RestController
@RequestMapping("${govpay.pendenze.api.v2.base-path:}")
public class PendenzaV2Controller implements PendenzeApi {

    /** Unico ordinamento di default del legacy ({@code ListaPendenzeDTO.addDefaultSort}). */
    private static final String ORDINAMENTO_DI_DEFAULT = "dataCreazione";

    /**
     * I 4 campi ordinabili del legacy per questo endpoint ({@code ListaPendenzeDTO}, costruttore):
     * nome esterno (quello nell'URL, formato {@code +campo}/{@code -campo}) → percorso JPA su
     * {@link Pendenza}. {@code stato} e' il valore grezzo persistito ({@code Pendenza.stato}),
     * non lo stato derivato di visualizzazione ({@link PendenzaV2Mapper#risolviStato}) — stesso
     * principio del legacy, che ordina sulla colonna fisica {@code STATO_VERSAMENTO}.
     */
    private static final Map<String, String> CAMPI_ORDINABILI = Map.of(
            "dataCaricamento", "dataCreazione",
            "dataValidita", "dataValidita",
            "dataScadenza", "dataScadenzaAvviso",
            "stato", "stato");

    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final PendenzaRepository pendenzaRepository;
    private final PosizioneDebitoriaRepository posizioneDebitoriaRepository;
    private final DominioRepository dominioRepository;
    private final ApplicazioneRepository applicazioneRepository;
    private final RptRepository rptRepository;
    private final AllegatoRepository allegatoRepository;
    private final PendenzaV2Mapper pendenzaV2Mapper;
    private final NuovaPendenzaMapper nuovaPendenzaMapper;
    private final RppMapper rppMapper;
    private final AclAuthorizer aclAuthorizer;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;
    private final AvvisoPdfPayloadMapper avvisoPdfPayloadMapper;
    private final StampeClient stampeClient;
    private final ExternalCallMetricsRecorder externalCallMetricsRecorder;

    public PendenzaV2Controller(PosizioneDebitoriaService posizioneDebitoriaService,
            PendenzaRepository pendenzaRepository, PosizioneDebitoriaRepository posizioneDebitoriaRepository,
            DominioRepository dominioRepository,
            ApplicazioneRepository applicazioneRepository, RptRepository rptRepository,
            AllegatoRepository allegatoRepository, PendenzaV2Mapper pendenzaV2Mapper,
            NuovaPendenzaMapper nuovaPendenzaMapper, RppMapper rppMapper,
            AclAuthorizer aclAuthorizer, CurrentApplicazioneService currentApplicazioneService,
            ObjectMapper objectMapper, HttpServletRequest currentRequest,
            AvvisoPdfPayloadMapper avvisoPdfPayloadMapper, StampeClient stampeClient,
            ExternalCallMetricsRecorder externalCallMetricsRecorder) {
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.pendenzaRepository = pendenzaRepository;
        this.posizioneDebitoriaRepository = posizioneDebitoriaRepository;
        this.dominioRepository = dominioRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.rptRepository = rptRepository;
        this.allegatoRepository = allegatoRepository;
        this.pendenzaV2Mapper = pendenzaV2Mapper;
        this.nuovaPendenzaMapper = nuovaPendenzaMapper;
        this.rppMapper = rppMapper;
        this.aclAuthorizer = aclAuthorizer;
        this.currentApplicazioneService = currentApplicazioneService;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
        this.avvisoPdfPayloadMapper = avvisoPdfPayloadMapper;
        this.stampeClient = stampeClient;
        this.externalCallMetricsRecorder = externalCallMetricsRecorder;
    }

    /**
     * Porting di {@code PendeleDAO.listaPendenze}/{@code VersamentoFilter} del legacy — fedele
     * al comportamento reale, bug/scollegamenti compresi (vedi memoria
     * {@code feedback_wrapper_v2_fedelta_legacy}, decisione del committente: questa API nasce
     * deprecata, non va "corretta").
     *
     * <p><b>{@code idA2A} e' sempre forzato</b> all'applicazione autenticata corrente, IGNORANDO
     * il valore eventualmente passato come query param — stesso comportamento del legacy
     * ("filtro sull'applicazione e' ignorato, un'applicazione vede solo le sue pendenze").</p>
     *
     * <p><b>{@code idPendenza}/{@code idPagamento}/{@code maxRisultati} sono accettati ma non
     * cablati a nessun filtro</b> — stessa disconnessione (bug) del legacy:
     * {@code idPendenza}/{@code idPagamento} non sono mai passati al filtro SQL reale nel
     * legacy nonostante siano dichiarati; {@code maxRisultati} (conteggio "capped") non ha
     * equivalente v3-nativo, il conteggio quando richiesto e' sempre esatto.
     * {@code mostraSpontaneiNonPagati} INVECE non e' un no-op (anche se le pendenze scritte dal
     * percorso v3-nativo/wrapper hanno sempre {@code tipo=DOVUTO}): {@code Pendenza} e' mappata
     * sulla tabella legacy {@code versamenti} RIUSATA in-place (M17), righe storiche con
     * {@code tipo=SPONTANEO} restano visibili attraverso questa stessa entity — vedi Javadoc di
     * {@link FiltriPendenze#escludiSpontaneiNonEseguiti}.</p>
     *
     * <p>Vedi Javadoc di {@link #traduciStato} per la traduzione di {@code stato} (il punto piu'
     * delicato, asimmetrie comprese) e di {@link #parseOrdinamento} per il formato di
     * {@code ordinamento}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Pendenze> findPendenze(Integer pagina, Integer risultatiPerPagina, String campi,
            String ordinamento, OffsetDateTime dataDa, OffsetDateTime dataA, String idDominio, String idA2A,
            String idPendenza, String idDebitore, StatoPendenza stato, String idPagamento, String direzione,
            String divisione, Boolean mostraSpontaneiNonPagati, Boolean metadatiPaginazione, Boolean maxRisultati) {
        aclAuthorizer.richiedeLettura();
        String idA2ACorrente = currentApplicazioneService.get().getCodApplicazione();

        int paginaEffettiva = pagina != null ? pagina : 1;
        int limiteEffettivo = risultatiPerPagina != null ? risultatiPerPagina : 25;
        boolean conMetadati = !Boolean.FALSE.equals(metadatiPaginazione);

        Long idDominioRisolto = null;
        if (idDominio != null) {
            Optional<Long> idDominioOpt = dominioRepository.findByCodDominio(idDominio).map(DominioEntity::getId);
            if (idDominioOpt.isEmpty()) {
                return ResponseEntity.ok(rispostaVuota(paginaEffettiva, limiteEffettivo, conMetadati));
            }
            idDominioRisolto = idDominioOpt.get();
        }

        FiltroStato filtroStato = traduciStato(stato);
        FiltriPendenze filtri = new FiltriPendenze(idDominioRisolto, idDebitore, direzione, divisione, dataDa,
                dataA, filtroStato.statoVersamento(), filtroStato.statoVersamentoAlternativo(),
                filtroStato.statoPagamento(), filtroStato.scaduta(),
                !Boolean.TRUE.equals(mostraSpontaneiNonPagati));

        Sort sort = parseOrdinamento(ordinamento);
        Pageable pageable = OffsetPageRequest.of((long) (paginaEffettiva - 1) * limiteEffettivo, limiteEffettivo,
                sort);

        Pendenze dto = new Pendenze();
        List<PendenzaIndex> risultati;
        if (conMetadati) {
            PaginaRisultati<Pendenza> paginaRisultato = posizioneDebitoriaService.cercaPendenzeFiltrate(
                    idA2ACorrente, filtri, pageable);
            risultati = paginaRisultato.risultati().stream().map(pendenzaV2Mapper::toIndexDto).toList();
            dto.setNumRisultati(Math.toIntExact(paginaRisultato.numeroRisultatiTotali()));
            dto.setNumPagine(
                    Math.max(1, (int) Math.ceil(paginaRisultato.numeroRisultatiTotali() / (double) limiteEffettivo)));
            if (paginaRisultato.haAltriRisultati()) {
                dto.setProssimiRisultati(urlProssimaPagina(paginaEffettiva + 1));
            }
        } else {
            PaginaSenzaConteggio<Pendenza> paginaRisultato = posizioneDebitoriaService
                    .cercaPendenzeFiltrateSenzaConteggio(idA2ACorrente, filtri, pageable);
            risultati = paginaRisultato.risultati().stream().map(pendenzaV2Mapper::toIndexDto).toList();
            if (paginaRisultato.haAltriRisultati()) {
                dto.setProssimiRisultati(urlProssimaPagina(paginaEffettiva + 1));
            }
        }
        dto.setPagina(paginaEffettiva);
        dto.setRisultatiPerPagina(limiteEffettivo);
        dto.setRisultati(SelezioneCampi.applica(risultati, campi, objectMapper));
        return ResponseEntity.ok(dto);
    }

    /** Risposta con lista vuota, stessi metadati di paginazione coerenti — vedi chiamante. */
    private Pendenze rispostaVuota(int pagina, int limite, boolean conMetadati) {
        Pendenze dto = new Pendenze();
        dto.setPagina(pagina);
        dto.setRisultatiPerPagina(limite);
        dto.setRisultati(List.of());
        if (conMetadati) {
            dto.setNumRisultati(0);
            dto.setNumPagine(1);
        }
        return dto;
    }

    /**
     * URL (path relativo) della pagina successiva per {@code Pendenze.prossimiRisultati} — stessa
     * query string della richiesta corrente, con {@code pagina} sostituito.
     */
    private String urlProssimaPagina(int nuovaPagina) {
        String queryString = currentRequest.getQueryString();
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(currentRequest.getRequestURI());
        if (queryString != null) {
            builder.query(queryString);
        }
        return builder.replaceQueryParam("pagina", nuovaPagina).build().toUriString();
    }

    /**
     * Traduce il parametro {@code ordinamento} (formato legacy {@code +campo}/{@code -campo},
     * separato da virgole per ordinamenti multipli — {@code BasicFindRequestDTO.setOrderBy} del
     * monolite, non il formato {@code campo:asc}/{@code campo:desc} usato dallo YAML v3) sui 4
     * campi ordinabili di {@link #CAMPI_ORDINABILI}. Un campo non riconosciuto e' un 400
     * ({@link ValidazioneNonSuperataException}), stesso trattamento del legacy
     * ({@code RequestParamException}). Assente → {@code dataCreazione DESC} (stesso default del
     * legacy).
     */
    private Sort parseOrdinamento(String ordinamento) {
        if (ordinamento == null || ordinamento.isBlank()) {
            return Sort.by(Sort.Direction.DESC, ORDINAMENTO_DI_DEFAULT);
        }
        List<Sort.Order> ordini = new ArrayList<>();
        for (String token : ordinamento.split(",")) {
            String pulito = token.trim();
            if (pulito.isEmpty()) {
                continue;
            }
            Sort.Direction direzione = Sort.Direction.ASC;
            String nomeCampo = pulito;
            if (pulito.startsWith("-")) {
                direzione = Sort.Direction.DESC;
                nomeCampo = pulito.substring(1);
            } else if (pulito.startsWith("+")) {
                nomeCampo = pulito.substring(1);
            }
            String percorso = CAMPI_ORDINABILI.get(nomeCampo);
            if (percorso == null) {
                throw new ValidazioneNonSuperataException("campo di ordinamento [" + nomeCampo
                        + "] non riconosciuto: ammessi " + CAMPI_ORDINABILI.keySet());
            }
            ordini.add(new Sort.Order(direzione, percorso));
        }
        return ordini.isEmpty() ? Sort.by(Sort.Direction.DESC, ORDINAMENTO_DI_DEFAULT) : Sort.by(ordini);
    }

    /**
     * Valori di basso livello di {@link FiltriPendenze} per il filtro {@code stato} — vedi
     * {@link #traduciStato}.
     */
    private record FiltroStato(it.govpay.pendenze.model.StatoPendenza statoVersamento,
            it.govpay.pendenze.model.StatoPendenza statoVersamentoAlternativo,
            it.govpay.pendenze.model.StatoPagamento statoPagamento, Boolean scaduta) {
        static final FiltroStato NESSUNO = new FiltroStato(null, null, null, null);
    }

    /**
     * Traduce l'enum v2 {@code StatoPendenza} nelle condizioni di basso livello su
     * {@code Pendenza.stato}/{@code statoPagamento}/{@code dataScadenzaAvviso} — replica ESATTA
     * delle condizioni SQL del legacy ({@code VersamentoFilter.toExpressionEngine}), asimmetrie e
     * bug compresi (decisione del committente, vedi memoria
     * {@code feedback_wrapper_v2_fedelta_legacy}):
     *
     * <ul>
     * <li>{@code ESEGUITA}: {@code stato=ESEGUITO AND statoPagamento=PAGATO} — ASIMMETRICO
     * rispetto a {@link PendenzaV2Mapper#risolviStato} (lettura singola), che mostra ESEGUITA
     * anche per {@code ESEGUITO_SENZA_RPT} e per qualunque {@code statoPagamento} tranne
     * INCASSATO: una pendenza ESEGUITA con pagamento NON_PAGATO compare in lettura singola ma
     * non in questa ricerca.</li>
     * <li>{@code ANOMALA}: {@code stato=ANOMALO} — filtro "morto" per design: nessun percorso di
     * scrittura v3-nativo assegna mai questo valore a {@code Pendenza.stato} (e' tollerato solo
     * in lettura, vedi Javadoc di {@code it.govpay.pendenze.model.StatoPendenza}), quindi questo
     * filtro restituisce sempre zero risultati — esattamente come nel legacy, dove lo stesso
     * valore enum non e' mai scritto sulla colonna reale. Non "corretto" con un OR su
     * {@code anomalo=true}: l'intento e' replicare il filtro morto, non renderlo utile.</li>
     * <li>{@code INCASSATA}: {@code stato IN (ESEGUITO, ESEGUITO_SENZA_RPT) AND
     * statoPagamento=INCASSATO} — simmetrico con la lettura singola.</li>
     * <li>{@code NON_ESEGUITA}/{@code SCADUTA}: {@code stato=NON_ESEGUITO} + rispettivamente non
     * scaduta/scaduta, stessa soglia di {@link PendenzaV2Mapper#isScaduta} (scaduta solo dal
     * giorno DOPO {@code dataScadenzaAvviso}).</li>
     * <li>{@code ANNULLATA}/{@code ESEGUITA_PARZIALE}: diretti, simmetrici.</li>
     * </ul>
     *
     * @return {@link FiltroStato#NESSUNO} se {@code stato} e' assente dalla richiesta
     */
    private FiltroStato traduciStato(StatoPendenza stato) {
        if (stato == null) {
            return FiltroStato.NESSUNO;
        }
        return switch (stato) {
            case ANNULLATA -> new FiltroStato(it.govpay.pendenze.model.StatoPendenza.ANNULLATO, null, null, null);
            case ESEGUITA -> new FiltroStato(it.govpay.pendenze.model.StatoPendenza.ESEGUITO, null,
                    it.govpay.pendenze.model.StatoPagamento.PAGATO, null);
            case ESEGUITA_PARZIALE ->
                new FiltroStato(it.govpay.pendenze.model.StatoPendenza.PARZIALMENTE_ESEGUITO, null, null, null);
            case NON_ESEGUITA -> new FiltroStato(it.govpay.pendenze.model.StatoPendenza.NON_ESEGUITO, null, null,
                    false);
            case SCADUTA -> new FiltroStato(it.govpay.pendenze.model.StatoPendenza.NON_ESEGUITO, null, null, true);
            case INCASSATA -> new FiltroStato(it.govpay.pendenze.model.StatoPendenza.ESEGUITO,
                    it.govpay.pendenze.model.StatoPendenza.ESEGUITO_SENZA_RPT,
                    it.govpay.pendenze.model.StatoPagamento.INCASSATO, null);
            case ANOMALA -> new FiltroStato(it.govpay.pendenze.model.StatoPendenza.ANOMALO, null, null, null);
        };
    }

    /**
     * {@code @Transactional} qui, non solo sul servizio (stesso motivo di
     * {@code PendenzaController#getPendenza}, v3): {@link #costruisciDto} attraversa
     * {@code Pendenza.voci}/{@code opzionePagamento}/
     * {@code opzionePagamento.posizioneDebitoria.soggettiDebitori} (tutte LAZY), lette dopo
     * che la transazione di sola lettura del servizio sarebbe altrimenti gia' chiusa.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<it.govpay.pendenze.v2.api.model.Pendenza> getPendenza(String idA2A, String idPendenza) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        Pendenza entity = posizioneDebitoriaService.trovaPendenzaPerIdentificativo(idA2A, idPendenza)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna pendenza con idPendenza [" + idPendenza
                        + "] per idA2A [" + idA2A + "]"));
        return ResponseEntity.ok(costruisciDto(entity));
    }

    /**
     * A differenza di {@link #getPendenza}, {@code idA2A} non e' nella richiesta:
     * {@code idDominio}+{@code numeroAvviso} identificano la pendenza nella maggioranza dei
     * casi, ma **non sempre**: lo stesso NAV e' condiviso, legittimamente, da tutte le rate di
     * uno stesso piano rateale con avviso cumulativo (nessun vincolo DB lo impedisce — vedi
     * Javadoc di {@link PendenzaRepository#findByIdDominioAndNumeroAvviso}). Scelta esplicita
     * (in deroga alla fedelta' al legacy, che ha lo stesso problema mai gestito — vedi
     * {@code VersamentiBD#getVersamentoByDominioIuv}, mai intercettato a monte): quando la
     * ricerca trova piu' di una pendenza, questo endpoint non può disambiguare quale
     * restituire e lo segnala esplicitamente con {@link RicercaNonUnivocaException} (409),
     * invece di propagare un errore interno o restituirne una a caso. Il chiamante deve
     * comunque essere l'applicazione proprietaria — stesso controllo del legacy
     * ({@code PendenzeController.getPendenzaByAvviso}, "filtro sull'applicazione"), qui fatto
     * dopo aver trovato la pendenza (non si puo' verificare prima, l'idA2A proprietario non e'
     * ancora noto). Un {@code idDominio} inesistente e' trattato come "pendenza non trovata"
     * (404), non un errore di anagrafica separato: dal punto di vista del chiamante nessuna
     * pendenza puo' comunque esistere su un dominio che non esiste.
     *
     * <p><b>Non replica {@code LeggiPendenzaDTO.verificaAvviso} del legacy</b> (quando la
     * pendenza non e' nel DB, interrogazione sincrona del connettore dell'applicazione
     * proprietaria — {@code VersamentoUtils.acquisisciVersamento}/{@code IVerificaClient
     * .verificaPendenza} — per recuperarla al volo, senza persisterla: solo per questa
     * risposta). Punto aperto esplicito, non implementato per scelta: il committente ha
     * confermato che resta "da valutare" se la v3 nativa debba mai supportare questo
     * protocollo — fino a quella decisione, questo wrapper gestisce solo pendenze gia'
     * presenti nel DB (migrate o create dal vero percorso di scrittura v3); un avviso
     * recuperabile dall'applicazione esterna ma non ancora migrato/creato risulta 404.</p>
     *
     * <p>Vedi Javadoc di {@link #validaFormatoNumeroAvviso} per l'unico controllo di formato
     * replicato dal legacy.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<it.govpay.pendenze.v2.api.model.Pendenza> getPendenzaByAvviso(String idDominio,
            String numeroAvviso) {
        aclAuthorizer.richiedeLettura();
        validaFormatoNumeroAvviso(numeroAvviso);
        List<Pendenza> trovate = dominioRepository.findByCodDominio(idDominio)
                .map(dominio -> pendenzaRepository.findByIdDominioAndNumeroAvviso(dominio.getId(), numeroAvviso))
                .orElseGet(List::of);
        if (trovate.isEmpty()) {
            throw new RisorsaNonTrovataException(
                    "nessuna pendenza con idDominio [" + idDominio + "] e numeroAvviso [" + numeroAvviso + "]");
        }
        if (trovate.size() > 1) {
            throw new RicercaNonUnivocaException("piu' di una pendenza (" + trovate.size()
                    + ") con idDominio [" + idDominio + "] e numeroAvviso [" + numeroAvviso
                    + "]: tipicamente le rate di uno stesso piano rateale con avviso cumulativo, non "
                    + "disambiguabili da questo endpoint");
        }
        Pendenza entity = trovate.get(0);

        String codApplicazioneProprietaria = applicazioneRepository.findById(entity.getIdApplicazione())
                .map(ApplicazioneEntity::getCodApplicazione)
                .orElseThrow(() -> new IllegalStateException(
                        "applicazione [id:" + entity.getIdApplicazione() + "] non trovata in anagrafica"));
        currentApplicazioneService.verificaIdA2A(codApplicazioneProprietaria);

        return ResponseEntity.ok(costruisciDto(entity));
    }

    /**
     * Porting di {@code IuvUtils.toIuv(String)} del monolite (jars/core — non il piu' recente
     * {@code it.govpay.common.utils.IuvUtils.convertiDaNumeroAvviso}, che richiede la
     * configurazione del dominio e verifica anche il check digit: qui, come nel legacy per
     * questo stesso endpoint, basta il formato grezzo del numero avviso, indipendente dal
     * dominio — l'eventuale disallineamento con i dati reali produce comunque un 404 naturale
     * dalla ricerca che segue). Lo schema v2 valida gia' 18 cifre numeriche (pattern
     * {@code ^[0-9]{18}$}); qui resta solo la prima cifra, che deve essere l'AuxDigit pagoPA
     * (0/1/2/3) — altrimenti non e' un numero avviso valido, indipendentemente dal fatto che
     * esista una pendenza con quel valore letterale.
     */
    private void validaFormatoNumeroAvviso(String numeroAvviso) {
        char primaCifra = numeroAvviso.charAt(0);
        if (primaCifra < '0' || primaCifra > '3') {
            throw new ValidazioneNonSuperataException("numeroAvviso [" + numeroAvviso + "] fornito non valido: "
                    + "prima cifra non e' [0|1|2|3]");
        }
    }

    /**
     * Avviso PDF cumulativo di tutte le rate pagabili di un documento. Il documento e' la
     * {@link PosizioneDebitoria} con {@code codDocumento = numeroDocumento} dell'applicazione
     * autenticata: {@code idA2A} non e' nella richiesta, la ricerca e' scoped all'applicazione
     * corrente. Un documento di un'altra applicazione e' 404. Dominio o documento inesistente:
     * 404.
     *
     * <p>{@code numeriAvviso}: ogni elemento e' validato ({@link #validaFormatoNumeroAvviso},
     * 400 se la prima cifra non e' un AuxDigit) ma non e' applicato come filtro — il PDF
     * include sempre tutte le rate pagabili.</p>
     *
     * <p><b>Content negotiation</b>: solo un {@code Accept} che CONTIENE
     * {@code application/pdf} ottiene il PDF; qualunque altro valore — compresi
     * {@code Accept} assente e {@code *}{@code /*} — e' un 406.</p>
     *
     * <p><b>Rate pagabili</b>: le pendenze {@code NON_ESEGUITO} di ciascuna opzione di
     * pagamento del documento, per {@code numeroRata} crescente. Un documento con un numero di
     * opzioni ANCORA PAGABILI diverso da uno e' un 400 esplicito, a meno che siano esattamente
     * la coppia {@code SOLUZIONE_UNICA_ENTRO}/{@code _OLTRE} (vedi Layout). Nessuna rata
     * pagabile in nessuna opzione: 422. Una sola rata MBT o con anagrafica debitore assente fa
     * fallire l'intero avviso con lo stesso 422 di {@link #addPendenza}.</p>
     *
     * <p><b>Layout</b>: una sola rata pagabile produce {@code full}, o {@code reduced_payments}
     * (con {@code threshold_type}/{@code threshold_days} dalla tipologia/{@code giorni}
     * dell'opzione) se l'opzione e' {@code SOLUZIONE_UNICA_ENTRO}/{@code _OLTRE}; piu' di una
     * rata pagabile produce sempre {@code instalments}. Se le opzioni ancora pagabili sono
     * esattamente la coppia {@code SOLUZIONE_UNICA_ENTRO}/{@code _OLTRE}, un solo avviso con
     * {@code reduced_payments} a due elementi, invece del 400 — vedi
     * {@code AvvisoPdfPayloadMapper#toPaymentNoticeSoglie}. Se sono esattamente la coppia
     * {@code SOLUZIONE_UNICA_RIDOTTO}/{@code _SCONTATO} (violazione del Codice della Strada),
     * un PDF da {@code POST /cds_violation} di {@code govpay-stampe} — vedi
     * {@code AvvisoPdfPayloadMapper#toCdsViolation}.</p>
     *
     * <p>{@code postal} e' {@code true} solo se TUTTE le rate hanno un IBAN postale, altrimenti
     * l'intero avviso e' non postale.</p>
     *
     * <p>{@code linguaSecondaria} diversa da {@code false}: nessun parametro causale/
     * informativa tradotte in questo schema (come nel vero legacy — non sono valori di
     * richiesta, ma dati del documento) — vengono letti da {@code proprieta
     * .linguaSecondariaCausale}/{@code .informativaImportoAvviso}/
     * {@code .linguaSecondariaInformativaImportoAvviso} della pendenza, persistiti da
     * {@link #addPendenza}, e solo quando il layout stampa una SINGOLA rata rappresentativa
     * (una sola rata pagabile, non piano rateale, non coppia soglie/CdS). 422 esplicito se la
     * pendenza non ha una causale tradotta persistita, o se il layout ne ha piu' di una.
     * {@code informativaImportoAvviso} (italiano) e' invece sempre letto in quel caso, anche
     * senza bilingue.</p>
     *
     * <p>Il PDF viene restituito come {@link ByteArrayResource}. Nome file:
     * {@code <idDominio>_<numeroDocumento>.pdf}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    @RequestMapping(method = RequestMethod.GET, value = PendenzeApi.PATH_GET_AVVISI_DOCUMENTO,
            produces = MediaType.ALL_VALUE)
    public ResponseEntity<Resource> getAvvisiDocumento(String idDominio, String numeroDocumento,
            LinguaSecondaria linguaSecondaria, List<String> numeriAvviso) {
        aclAuthorizer.richiedeLettura();
        ApplicazioneEntity applicazioneCorrente = currentApplicazioneService.get();
        if (numeriAvviso != null) {
            numeriAvviso.forEach(this::validaFormatoNumeroAvviso);
        }
        String accept = currentRequest.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || !accept.toLowerCase(Locale.ROOT).contains(MediaType.APPLICATION_PDF_VALUE)) {
            throw new NotAcceptableMediaTypeException("Documento di pagamento non disponibile nel formato indicato "
                    + "nell'header Accept, ricevuto: '" + (accept == null ? "" : accept)
                    + "', consentito: 'application/pdf'");
        }

        PosizioneDebitoria documento = dominioRepository.findByCodDominio(idDominio)
                .flatMap(dominio -> posizioneDebitoriaRepository.findByIdApplicazioneAndIdDominioAndCodDocumento(
                        applicazioneCorrente.getId(), dominio.getId(), numeroDocumento))
                .orElseThrow(() -> new RisorsaNonTrovataException("nessun documento con idDominio [" + idDominio
                        + "] e numeroDocumento [" + numeroDocumento + "]"));

        List<OpzionePagamento> opzioni = documento.getOpzioniPagamento();
        List<List<Pendenza>> opzioniPagabili = opzioni.stream()
                .map(o -> o.getPendenze().stream()
                        .filter(p -> p.getStato() == it.govpay.pendenze.model.StatoPendenza.NON_ESEGUITO)
                        .sorted(Comparator.comparingInt(Pendenza::getNumeroRata))
                        .toList())
                .filter(pendenze -> !pendenze.isEmpty())
                .toList();

        java.util.function.Consumer<OutputStream> chiamataStampe;
        if (opzioniPagabili.size() > 1) {
            if (richiedeBilingue(linguaSecondaria)) {
                throw new AvvisoDocumentoNonDisponibileException("Avviso bilingue non disponibile per il documento ["
                        + numeroDocumento + "]: con piu' di un'opzione di pagamento pagabile (soglie o violazione "
                        + "CdS) nessuna causale tradotta e' associabile a una singola pendenza rappresentativa.");
            }
            if (AvvisoPdfPayloadMapper.sonoRidottoEScontato(opzioniPagabili)) {
                verificaMbtEAnagrafica(opzioniPagabili.stream().map(o -> o.get(0)).toList());
                var payload = avvisoPdfPayloadMapper.toCdsViolation(opzioniPagabili);
                chiamataStampe = output -> stampeClient.streamCdsViolation(payload, output);
            } else if (AvvisoPdfPayloadMapper.sonoTutteSoglie(opzioniPagabili)) {
                List<Pendenza> soglie = opzioniPagabili.stream().map(o -> o.get(0)).toList();
                verificaMbtEAnagrafica(soglie);
                PaymentNotice payload = avvisoPdfPayloadMapper.toPaymentNoticeSoglie(soglie);
                chiamataStampe = output -> stampeClient.streamPaymentNotice(payload, output);
            } else {
                throw new ValidazioneNonSuperataException("il documento [" + numeroDocumento + "] ha "
                        + opzioniPagabili.size() + " opzioni di pagamento ancora con rate pagabili: questo wrapper "
                        + "gestisce solo documenti con esattamente un'opzione pagabile (quelli creati da questa "
                        + "stessa API)");
            }
        } else {
            List<Pendenza> ratePagabili = opzioniPagabili.isEmpty() ? List.of() : opzioniPagabili.get(0);
            if (ratePagabili.isEmpty()) {
                throw new AvvisoDocumentoNonDisponibileException("Non sono state trovate pendenze da includere "
                        + "nella stampa del documento [IDA2A: " + applicazioneCorrente.getCodApplicazione()
                        + " | CodDocumento: " + numeroDocumento + "].");
            }
            verificaMbtEAnagrafica(ratePagabili);
            PaymentNotice payload;
            if (ratePagabili.size() == 1) {
                Pendenza pendenza = ratePagabili.get(0);
                ParametriBilingue bilingue = risolviParametriBilingue(linguaSecondaria, pendenza);
                payload = avvisoPdfPayloadMapper.toPaymentNotice(pendenza, bilingue.linguaSecondaria(),
                        bilingue.causaleTradotta(), bilingue.informativaImporto(),
                        bilingue.informativaImportoTradotta());
            } else {
                if (richiedeBilingue(linguaSecondaria)) {
                    throw new AvvisoDocumentoNonDisponibileException("Avviso bilingue non disponibile per il "
                            + "documento [" + numeroDocumento + "]: con piu' di una rata pagabile nessuna causale "
                            + "tradotta e' associabile a una singola rata rappresentativa.");
                }
                payload = avvisoPdfPayloadMapper.toPaymentNoticeRate(ratePagabili);
            }
            chiamataStampe = output -> stampeClient.streamPaymentNotice(payload, output);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        externalCallMetricsRecorder.record("stampe", "payment_notice", () -> chiamataStampe.accept(output));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + idDominio + "_" + documento.getCodDocumento() + ".pdf\"")
                .body(new ByteArrayResource(output.toByteArray()));
    }

    private static boolean richiedeBilingue(LinguaSecondaria linguaSecondaria) {
        return linguaSecondaria != null && linguaSecondaria != LinguaSecondaria.FALSE;
    }

    /**
     * Causale/informativa tradotte della pendenza, dalla sua {@code proprieta} persistita da
     * {@link #addPendenza} — nessun parametro di richiesta per questi valori in questo schema.
     * 422 se {@code linguaSecondaria} e' richiesta ma {@code linguaSecondariaCausale} non e'
     * presente. {@code informativaImporto} (italiano) e' risolto anche senza bilingue.
     */
    private ParametriBilingue risolviParametriBilingue(LinguaSecondaria linguaSecondaria, Pendenza pendenza) {
        ProprietaPendenza proprieta = pendenzaV2Mapper.toProprietaPendenzaDto(pendenza.getProprieta());
        String informativaImporto = proprieta == null ? null : proprieta.getInformativaImportoAvviso();
        if (!richiedeBilingue(linguaSecondaria)) {
            return new ParametriBilingue(null, null, informativaImporto, null);
        }
        String causaleTradotta = proprieta == null ? null : proprieta.getLinguaSecondariaCausale();
        if (causaleTradotta == null || causaleTradotta.isBlank()) {
            throw new AvvisoDocumentoNonDisponibileException("Avviso bilingue non disponibile per la pendenza ["
                    + pendenza.getIdPendenza() + "]: nessuna causale tradotta "
                    + "('proprieta.linguaSecondariaCausale') persistita.");
        }
        String informativaImportoTradotta = proprieta.getLinguaSecondariaInformativaImportoAvviso();
        return new ParametriBilingue(it.govpay.pendenze.api.model.LinguaSecondaria.valueOf(linguaSecondaria.name()),
                causaleTradotta, informativaImporto, informativaImportoTradotta);
    }

    private record ParametriBilingue(it.govpay.pendenze.api.model.LinguaSecondaria linguaSecondaria,
            String causaleTradotta, String informativaImporto, String informativaImportoTradotta) {
    }

    private static void verificaMbtEAnagrafica(List<Pendenza> pendenze) {
        for (Pendenza pendenza : pendenze) {
            if (AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)) {
                throw new AvvisoMbtException(
                        "Stampa Documento non disponibile: sono presenti dei pagamenti di Marca da Bollo");
            }
            if (AvvisoPdfPayloadMapper.isAnagraficaDebitoreAssente(pendenza)) {
                throw new AvvisoAnagraficaAssenteException(
                        "Avviso PDF non disponibile: anagrafica del debitore assente.");
            }
        }
    }

    private it.govpay.pendenze.v2.api.model.Pendenza costruisciDto(Pendenza entity) {
        List<Rpt> rpts = rptRepository.findByIdVersamentoOrderByDataMsgRichiestaAsc(entity.getId());
        List<RppIndex> rpp = rpts.stream().map(rpt -> rppMapper.toRppIndex(rpt, entity)).toList();
        List<AllegatoEntity> allegati = allegatoRepository.findByIdVersamentoOrderByIdAsc(entity.getId());
        return pendenzaV2Mapper.toDto(entity, rpp, allegati);
    }

    /**
     * Upsert: crea la pendenza se {@code idPendenza} non esiste ancora (201), altrimenti la
     * aggiorna (200) — stessa semantica e stessi due status del controller legacy
     * ({@code PendenzeController.pendenzeIdA2AIdPendenzaPUT},
     * {@code createOrUpdate.isCreated() ? 201 : 200}). La decisione fra i due rami usa la
     * STESSA query di risoluzione di {@code PosizioneDebitoriaService#aggiornaPendenza}
     * (una pendenza senza {@code opzionePagamento}, es. un orfano di migrazione, NON conta
     * come "esistente" per questo endpoint: non e' aggiornabile da questo comando, un
     * tentativo con lo stesso {@code idPendenza} tenta la creazione e urta sul vincolo di
     * unicita' {@code unique_versamenti_1}, risultando in 409 — edge case non gestito
     * diversamente, segnalato nel report).
     *
     * <p>Vedi Javadoc di {@link NuovaPendenzaMapper} per le decisioni di progetto (documento
     * sintetico, scelta della tipologia, validazione della rata, cosa viene ignorato in
     * aggiornamento). {@code dataAvvisatura}: nessun equivalente v3 esiste oggi per
     * persistere "quando notificare l'avviso" (gap noto, nessuna colonna/tabella trovata in
     * govpay-common-pendenze) — il valore e' solo validato nel formato ({@code "MAI"} o una
     * data ISO), mai applicato. {@code stampaAvviso}: riusa la stessa generazione PDF di
     * {@code PendenzaController#getStampaPendenza} (v3-nativo), con gli stessi due guard
     * (bollo MBT, anagrafica debitore assente) che qui diventano un 422 sull'intera
     * richiesta se il chiamante ha esplicitamente domandato il PDF.</p>
     */
    @Override
    @Transactional
    public ResponseEntity<PendenzaCreata> addPendenza(String idA2A, String idPendenza, Boolean stampaAvviso,
            String dataAvvisatura, NuovaPendenza nuovaPendenza) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (nuovaPendenza == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        validaDataAvvisatura(dataAvvisatura);

        ApplicazioneEntity applicazione = nuovaPendenzaMapper.risolviApplicazione(idA2A);
        Optional<Pendenza> esistente = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(applicazione.getId(), idPendenza);

        boolean creata = esistente.isEmpty();
        Pendenza pendenzaFinale;
        if (creata) {
            pendenzaFinale = nuovaPendenzaMapper.crea(idA2A, idPendenza, nuovaPendenza, applicazione);
        } else {
            PosizioneDebitoria posizioneEsistente = esistente.get().getOpzionePagamento().getPosizioneDebitoria();
            nuovaPendenzaMapper.validaBeneficiarioInvariato(nuovaPendenza, posizioneEsistente);
            AggiornamentoPendenza comando = nuovaPendenzaMapper.toAggiornamentoPendenza(idPendenza, nuovaPendenza,
                    posizioneEsistente.getIdDominio(), applicazione);
            pendenzaFinale = posizioneDebitoriaService.aggiornaPendenza(idA2A, idPendenza, comando);
        }
        nuovaPendenzaMapper.applicaCampiLegacy(pendenzaFinale, nuovaPendenza);
        salvaAllegati(pendenzaFinale, nuovaPendenza.getAllegati(), !creata);

        PendenzaCreata risposta = new PendenzaCreata(nuovaPendenza.getIdDominio(), pendenzaFinale.getNumeroAvviso());
        // UUID: deliberatamente sempre null (decisione di progetto) -- popolato solo da
        // flussi di pagamento online/sessione (portale/app), mai da una creazione/
        // aggiornamento da gestionale via addPendenza.
        if (Boolean.TRUE.equals(stampaAvviso)) {
            risposta.setPdf(generaPdfAvviso(pendenzaFinale));
        }
        return ResponseEntity.status(creata ? HttpStatus.CREATED : HttpStatus.OK).body(risposta);
    }

    /**
     * JSON-Patch sui 4 path legacy ({@code /stato}, {@code /descrizioneStato}, {@code /ack},
     * {@code /nota} — stesso elenco di {@code PendenzeDAO.OPERAZIONI_CONSENTITE_PENDENZE}).
     * Ogni {@link PatchOp} e' applicato nell'ordine della richiesta alla stessa pendenza
     * gestita, in un'unica transazione (fedele al legacy: nessun commit intermedio fra le
     * singole operazioni).
     *
     * <p>{@code /descrizioneStato} e {@code /nota} scrivono entrambi sulla STESSA colonna
     * {@code descrizioneStato} — replica fedele di un quirk legacy: {@code PendenzeDAO.patch},
     * path {@code PATH_NOTA}, estrae {@code value.oggetto} e lo scrive con
     * {@code setDescrizioneStato}, la stessa chiamata di {@code patchDescrizioneStato}. Il
     * parametro {@code motivazione} che {@code patch()} estrae dal sibling
     * {@code /descrizioneStato} per passarlo a {@code patchStato} non e' invece mai letto
     * dentro quel metodo (dead code legacy): non replicato, non ha alcun effetto da
     * riprodurre. "Ultima scrittura vince" se entrambi i path appaiono nella stessa richiesta
     * — stesso comportamento del legacy.</p>
     *
     * <p>{@code /ack} e {@code /descrizioneStato}/{@code /nota} non hanno un comando dedicato
     * in {@code govpay-common-pendenze} (nessuna precondizione di stato nel legacy per questi
     * tre path): stesso principio gia' applicato per i campi solo-legacy di
     * {@link NuovaPendenzaMapper#applicaCampiLegacy} — setter diretti sull'entity gestita
     * trovata da {@link #pendenzaRepository}, nella transazione corrente.</p>
     *
     * <p><b>{@code /stato}</b>: solo i due target che il legacy ammette ({@code ANNULLATA} →
     * {@code annulla}, {@code NON_ESEGUITA} → {@code ripristina}; ogni altro valore, incluso
     * {@code SCADUTA} — che nel legacy non e' nemmeno un vero {@code StatoVersamento}, solo un
     * filtro calcolato — e' un 400, come nel legacy). A differenza del legacy, pero', la v3
     * nativa non annulla/ripristina piu' una singola pendenza: lo fa solo a livello di
     * {@code OpzionePagamento}, con cascata su tutte le sue pendenze (chiusura deliberata del
     * gap "stati misti nella stessa opzione", vedi {@code PosizioneDebitoriaService
     * #registraEsitoPendenza}, nota 2026-10-22). Questo wrapper non aggira quella scelta
     * scrivendo lo stato direttamente sull'entity (ricreerebbe esattamente l'artefatto che la
     * libreria ha chiuso): se l'opzione della pendenza contiene PIU' di una pendenza (es. una
     * rata di un piano rateale), questa richiesta — che e' per costruzione su una singola
     * pendenza — risponde 409, invece di annullare/ripristinare anche le pendenze sorelle non
     * menzionate dal chiamante. Se l'opzione ha una sola pendenza, il comportamento coincide
     * con quello del legacy. Il wrapper eredita anche le guardie v3 senza equivalente legacy
     * (es. {@code ripristina} rifiuta se un'altra opzione della stessa posizione e'
     * {@code ATTIVATA} — il legacy non ha mai avuto questo controllo, non avendo il concetto
     * di opzione): nessuna di queste viene scavalcata, per lo stesso motivo.</p>
     */
    @Override
    @Transactional
    public ResponseEntity<Void> updatePendenza(String idA2A, String idPendenza, List<PatchOp> patchOp) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (patchOp == null || patchOp.isEmpty()) {
            throw new ValidazioneNonSuperataException("corpo della richiesta mancante o vuoto");
        }

        ApplicazioneEntity applicazione = nuovaPendenzaMapper.risolviApplicazione(idA2A);
        Pendenza pendenza = pendenzaRepository
                .findByIdApplicazioneAndIdPendenzaAndOpzionePagamentoIsNotNull(applicazione.getId(), idPendenza)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna pendenza con idPendenza [" + idPendenza
                        + "] per idA2A [" + idA2A + "]"));
        nuovaPendenzaMapper.verificaAutorizzazioneDominioETipoVersamento(applicazione, pendenza.getIdDominio(),
                pendenza.getIdTipoVersamento());

        for (PatchOp op : patchOp) {
            applicaPatch(idA2A, pendenza, op);
        }
        return ResponseEntity.ok().build();
    }

    private void applicaPatch(String idA2A, Pendenza pendenza, PatchOp op) {
        if (op == null || op.getPath() == null) {
            throw new ValidazioneNonSuperataException("operazione di patch non valida");
        }
        switch (op.getPath()) {
            case "/stato" -> patchStato(idA2A, pendenza, op);
            case "/descrizioneStato" -> patchDescrizioneStato(pendenza, op);
            case "/ack" -> patchAck(pendenza, op);
            case "/nota" -> patchNota(pendenza, op);
            default -> throw new ValidazioneNonSuperataException("path [" + op.getPath() + "] non valido");
        }
    }

    private void patchDescrizioneStato(Pendenza pendenza, PatchOp op) {
        validaOp(op, PatchOp.OpEnum.REPLACE);
        if (!(op.getValue() instanceof String valore) || valore.isEmpty()) {
            throw new ValidazioneNonSuperataException("valore non valido per il path [" + op.getPath() + "]");
        }
        pendenza.setDescrizioneStato(valore);
    }

    private void patchNota(Pendenza pendenza, PatchOp op) {
        validaOp(op, PatchOp.OpEnum.ADD);
        if (!(op.getValue() instanceof Map<?, ?> valore)) {
            throw new ValidazioneNonSuperataException("valore non valido per il path [" + op.getPath() + "]");
        }
        Object oggetto = valore.get("oggetto");
        pendenza.setDescrizioneStato(oggetto == null ? null : oggetto.toString());
    }

    private void patchAck(Pendenza pendenza, PatchOp op) {
        validaOp(op, PatchOp.OpEnum.REPLACE);
        pendenza.setAck(op.getValue() instanceof Boolean valore && valore);
    }

    private void patchStato(String idA2A, Pendenza pendenza, PatchOp op) {
        validaOp(op, PatchOp.OpEnum.REPLACE);
        StatoPendenza nuovoStato = parseStatoPendenza(op.getValue());
        if (nuovoStato != StatoPendenza.ANNULLATA && nuovoStato != StatoPendenza.NON_ESEGUITA) {
            throw new ValidazioneNonSuperataException(
                    "non e' consentito aggiornare lo stato di una pendenza a [" + op.getValue() + "]");
        }

        OpzionePagamento opzione = pendenza.getOpzionePagamento();
        if (opzione.getPendenze().size() > 1) {
            throw new TransizioneStatoNonAmmessaException("la pendenza [" + pendenza.getIdPendenza() + "] "
                    + "appartiene a un'opzione di pagamento con piu' di una pendenza: l'annullamento/ripristino "
                    + "per singola pendenza non e' supportato in questo caso (la v3 nativa annulla/ripristina "
                    + "solo l'intera opzione, con cascata su tutte le sue pendenze)");
        }

        String idPosizioneDebitoria = posizioneDebitoriaService
                .idPosizioneDebitoriaPubblica(opzione.getPosizioneDebitoria());
        if (nuovoStato == StatoPendenza.ANNULLATA) {
            posizioneDebitoriaService.annulla(idA2A, idPosizioneDebitoria, opzione.getIdOpzionePagamento());
        } else {
            posizioneDebitoriaService.ripristina(idA2A, idPosizioneDebitoria, opzione.getIdOpzionePagamento());
        }
    }

    private void validaOp(PatchOp op, PatchOp.OpEnum opAmmessa) {
        if (op.getOp() != opAmmessa) {
            throw new ValidazioneNonSuperataException(
                    "operazione [" + op.getOp() + "] non valida per il path [" + op.getPath() + "]");
        }
    }

    /** Come {@code StatoPendenza.fromValue} legacy: {@code null} per un valore non riconosciuto,
     *  non un'eccezione (a differenza della versione generata da questo progetto, che lancia
     *  {@code IllegalArgumentException} su un valore ignoto). */
    private StatoPendenza parseStatoPendenza(Object valore) {
        if (!(valore instanceof String testo)) {
            return null;
        }
        try {
            return StatoPendenza.fromValue(testo);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code "MAI"} o una data ISO-8601 (stesso oneOf dello YAML originale, qui validato a
     * mano perche' il parametro e' stato semplificato a {@code string} — vedi il commento
     * nello YAML v2 su questo parametro): nessuno dei due valori ha pero' un effetto
     * persistito oggi (gap noto, vedi Javadoc di {@link #addPendenza}), questo controllo
     * serve solo a rifiutare un valore sintatticamente non valido con lo stesso 400 che il
     * contratto originale avrebbe dato.
     */
    private void validaDataAvvisatura(String dataAvvisatura) {
        if (dataAvvisatura == null || "MAI".equals(dataAvvisatura)) {
            return;
        }
        try {
            LocalDate.parse(dataAvvisatura);
        } catch (DateTimeParseException e) {
            throw new ValidazioneNonSuperataException(
                    "'dataAvvisatura' deve essere \"MAI\" oppure una data in formato ISO-8601 (AAAA-MM-GG): ["
                            + dataAvvisatura + "]");
        }
    }

    /**
     * Stessa generazione PDF di {@code PendenzaController#getStampaPendenza} (v3-nativo),
     * catturata su un buffer invece che in streaming diretto sulla risposta HTTP (qui il PDF
     * e' un campo base64 dentro un body JSON, non l'intera risposta) e senza equivalente v2
     * di {@code linguaSecondaria}/causale e informativa tradotte (nessun parametro
     * corrispondente in questo endpoint: avviso sempre in italiano).
     */
    private String generaPdfAvviso(Pendenza pendenza) {
        if (AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)) {
            throw new AvvisoMbtException("Avviso PDF non disponibile per pendenze con Marca da Bollo Telematica.");
        }
        if (AvvisoPdfPayloadMapper.isAnagraficaDebitoreAssente(pendenza)) {
            throw new AvvisoAnagraficaAssenteException("Avviso PDF non disponibile: anagrafica del debitore assente.");
        }
        PaymentNotice payload = avvisoPdfPayloadMapper.toPaymentNotice(pendenza, null, null, null, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        externalCallMetricsRecorder.record("stampe", "payment_notice",
                () -> stampeClient.streamPaymentNotice(payload, output));
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    /**
     * Persiste nome/tipo/descrizione/dataCreazione di ogni allegato ({@code AllegatoEntity},
     * vedi il suo Javadoc per il perche' {@code contenuto} non viene scritto: gap noto, non
     * un'omissione). <b>Su un aggiornamento (non una creazione), REPLACE non APPEND</b>: gli
     * allegati gia' presenti vengono cancellati prima di inserire quelli della richiesta
     * corrente (anche se vuota/assente — svuota gli allegati esistenti), stessa semantica del
     * legacy ({@code VersamentiBD#updateVersamento}, righe 494-516: cancella tutti gli allegati
     * del versamento, poi reinserisce quelli del body). In creazione non c'e' nulla da
     * cancellare, il parametro e' ignorato in pratica.
     */
    private void salvaAllegati(Pendenza pendenza, List<NuovoAllegatoPendenza> allegati, boolean aggiornamento) {
        if (aggiornamento) {
            allegatoRepository.deleteByIdVersamento(pendenza.getId());
        }
        if (allegati == null || allegati.isEmpty()) {
            return;
        }
        OffsetDateTime adesso = OffsetDateTime.now();
        for (NuovoAllegatoPendenza allegato : allegati) {
            if (allegato == null) {
                continue;
            }
            AllegatoEntity entity = new AllegatoEntity();
            entity.setIdVersamento(pendenza.getId());
            entity.setNome(allegato.getNome());
            entity.setTipo(allegato.getTipo());
            entity.setDescrizione(allegato.getDescrizione());
            entity.setDataCreazione(adesso);
            allegatoRepository.save(entity);
        }
    }
}
