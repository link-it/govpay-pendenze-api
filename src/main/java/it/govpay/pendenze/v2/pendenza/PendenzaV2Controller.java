package it.govpay.pendenze.v2.pendenza;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.criteri.FiltriPendenze;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.exception.RicercaNonUnivocaException;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.v2.api.model.Pendenze;
import it.govpay.pendenze.v2.api.model.PendenzaIndex;
import it.govpay.pendenze.v2.api.model.RppIndex;
import it.govpay.pendenze.v2.api.model.StatoPendenza;
import it.govpay.pendenze.v2.api.rest.PendenzeApi;
import it.govpay.pendenze.v2.ricevuta.RppMapper;
import it.govpay.pendenze.web.SelezioneCampi;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link PendenzeApi}: per ora {@link #findPendenze}/{@link #getPendenza}/
 * {@link #getPendenzaByAvviso}, le altre operazioni restano sul default generato (501) fino
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
    private final DominioRepository dominioRepository;
    private final ApplicazioneRepository applicazioneRepository;
    private final RptRepository rptRepository;
    private final AllegatoRepository allegatoRepository;
    private final PendenzaV2Mapper pendenzaV2Mapper;
    private final RppMapper rppMapper;
    private final AclAuthorizer aclAuthorizer;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;

    public PendenzaV2Controller(PosizioneDebitoriaService posizioneDebitoriaService,
            PendenzaRepository pendenzaRepository, DominioRepository dominioRepository,
            ApplicazioneRepository applicazioneRepository, RptRepository rptRepository,
            AllegatoRepository allegatoRepository, PendenzaV2Mapper pendenzaV2Mapper, RppMapper rppMapper,
            AclAuthorizer aclAuthorizer, CurrentApplicazioneService currentApplicazioneService,
            ObjectMapper objectMapper, HttpServletRequest currentRequest) {
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.pendenzaRepository = pendenzaRepository;
        this.dominioRepository = dominioRepository;
        this.applicazioneRepository = applicazioneRepository;
        this.rptRepository = rptRepository;
        this.allegatoRepository = allegatoRepository;
        this.pendenzaV2Mapper = pendenzaV2Mapper;
        this.rppMapper = rppMapper;
        this.aclAuthorizer = aclAuthorizer;
        this.currentApplicazioneService = currentApplicazioneService;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
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
     * <p><b>{@code idPendenza}/{@code idPagamento}/{@code mostraSpontaneiNonPagati}/
     * {@code maxRisultati} sono accettati ma non cablati a nessun filtro</b> — stessa
     * disconnessione (bug) del legacy: {@code idPendenza}/{@code idPagamento} non sono mai
     * passati al filtro SQL reale nel legacy nonostante siano dichiarati;
     * {@code mostraSpontaneiNonPagati} e' un no-op qui perche' {@code Pendenza.tipo} e' sempre
     * {@code DOVUTO} in questa libreria (mai {@code SPONTANEO}); {@code maxRisultati} (conteggio
     * "capped") non ha equivalente v3-nativo, il conteggio quando richiesto e' sempre esatto.</p>
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
                filtroStato.statoPagamento(), filtroStato.scaduta());

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

    private it.govpay.pendenze.v2.api.model.Pendenza costruisciDto(Pendenza entity) {
        List<Rpt> rpts = rptRepository.findByIdVersamentoOrderByDataMsgRichiestaAsc(entity.getId());
        List<RppIndex> rpp = rpts.stream().map(rpt -> rppMapper.toRppIndex(rpt, entity)).toList();
        List<AllegatoEntity> allegati = allegatoRepository.findByIdVersamentoOrderByIdAsc(entity.getId());
        return pendenzaV2Mapper.toDto(entity, rpp, allegati);
    }
}
