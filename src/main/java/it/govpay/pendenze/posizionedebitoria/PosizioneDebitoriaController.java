package it.govpay.pendenze.posizionedebitoria;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import it.govpay.common.metrics.ExternalCallMetricsRecorder;
import it.govpay.pendenze.api.model.NuovaOpzionePagamento;
import it.govpay.pendenze.api.model.NuovaPendenza;
import it.govpay.pendenze.api.model.NuovaPosizioneDebitoria;
import it.govpay.pendenze.api.model.OpzionePagamento;
import it.govpay.pendenze.api.model.PatchOp;
import it.govpay.pendenze.api.model.Pagination;
import it.govpay.pendenze.api.model.PosizioneDebitoriaIndex;
import it.govpay.pendenze.api.model.PosizioniDebitorie;
import it.govpay.pendenze.api.rest.PosizioniDebitorieApi;
import it.govpay.pendenze.avviso.AvvisoAnagraficaAssenteException;
import it.govpay.pendenze.avviso.AvvisoDocumentoNonDisponibileException;
import it.govpay.pendenze.avviso.AvvisoMbtException;
import it.govpay.pendenze.avviso.AvvisoPdfPayloadMapper;
import it.govpay.pendenze.avviso.StampeClient;
import it.govpay.pendenze.criteri.CriteriOrdinamento;
import it.govpay.pendenze.criteri.CursorCodec;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.criteri.PaginaSenzaConteggio;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.model.StatoPendenza;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.web.NotAcceptableMediaTypeException;
import it.govpay.pendenze.web.QueryParamUtils;
import it.govpay.pendenze.web.SelezioneCampi;
import it.govpay.stampe.client.model.PaymentNotice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link PosizioniDebitorieApi}: per ora {@link #addPosizioneDebitoria},
 * {@link #getPosizioneDebitoria}, {@link #getStampaPosizioneDebitoria},
 * {@link #updatePosizioneDebitoria}, {@link #findPosizioniDebitorie},
 * {@link #addOpzionePagamento}, {@link #addPendenza} e {@link #updateOpzionePagamento}, le
 * altre operazioni restano sul default generato (501, vedi Javadoc dell'interfaccia) fino al
 * loro sviluppo.
 */
@RestController
@RequestMapping("${govpay.pendenze.api.v3.base-path:}")
public class PosizioneDebitoriaController implements PosizioniDebitorieApi {

    /** Ordinamento fisso in modalita' cursore, coerente con govpay-console-api. */
    static final String ORDINAMENTO_FISSO_CURSORE = "dataCreazione desc, id desc";

    private final PosizioneDebitoriaMapper mapper;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final AvvisoPdfPayloadMapper avvisoPdfPayloadMapper;
    private final StampeClient stampeClient;
    private final ExternalCallMetricsRecorder externalCallMetricsRecorder;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest currentRequest;
    private final HttpServletResponse currentResponse;
    private final CurrentApplicazioneService currentApplicazioneService;
    private final AclAuthorizer aclAuthorizer;

    public PosizioneDebitoriaController(PosizioneDebitoriaMapper mapper,
            PosizioneDebitoriaService posizioneDebitoriaService, AvvisoPdfPayloadMapper avvisoPdfPayloadMapper,
            StampeClient stampeClient, ExternalCallMetricsRecorder externalCallMetricsRecorder,
            ObjectMapper objectMapper, HttpServletRequest currentRequest, HttpServletResponse currentResponse,
            CurrentApplicazioneService currentApplicazioneService, AclAuthorizer aclAuthorizer) {
        this.mapper = mapper;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.avvisoPdfPayloadMapper = avvisoPdfPayloadMapper;
        this.stampeClient = stampeClient;
        this.externalCallMetricsRecorder = externalCallMetricsRecorder;
        this.objectMapper = objectMapper;
        this.currentRequest = currentRequest;
        this.currentResponse = currentResponse;
        this.currentApplicazioneService = currentApplicazioneService;
        this.aclAuthorizer = aclAuthorizer;
    }

    @Override
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> addPosizioneDebitoria(String idA2A,
            NuovaPosizioneDebitoria nuovaPosizioneDebitoria) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (nuovaPosizioneDebitoria == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }

        var comando = mapper.toComando(idA2A, nuovaPosizioneDebitoria);
        it.govpay.pendenze.entity.PosizioneDebitoria creata = posizioneDebitoriaService.crea(comando);
        it.govpay.pendenze.api.model.PosizioneDebitoria dto = mapper.toDto(creata);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(PosizioniDebitorieApi.PATH_GET_POSIZIONE_DEBITORIA)
                .buildAndExpand(idA2A, dto.getIdPosizioneDebitoria())
                .toUri();

        return ResponseEntity.created(location).body(dto);
    }

    /**
     * {@code @Transactional} qui, non solo su {@code PosizioneDebitoriaService.trovaPerIdentificativo}:
     * quel metodo apre e chiude la propria transazione prima di
     * tornare al controller — con {@code open-in-view=false} la sessione Hibernate e' gia'
     * chiusa quando {@code mapper.toDto(...)} legge le collezioni LAZY dell'aggregato
     * ({@code soggettiDebitori}/{@code opzioniPagamento}/{@code pendenze}), sollevando
     * {@code LazyInitializationException} — stessa classe di bug gia' vista su
     * {@code TipoVersamentoDominio.tipoVersamento}, ma non risolvibile con un semplice
     * {@code join fetch} sul repository: {@code soggettiDebitori} e {@code opzioniPagamento}
     * sono entrambe {@code List} (bag), e Hibernate non ammette il fetch join di piu' di una
     * bag nella stessa query ({@code MultipleBagFetchException}). Estendere qui il confine
     * transazionale, cosi' che copra anche la mappatura, e' la soluzione piu' semplice che non
     * tocca la forma delle collezioni della libreria.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> getPosizioneDebitoria(String idA2A,
            String idPosizioneDebitoria) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        it.govpay.pendenze.entity.PosizioneDebitoria posizione = posizioneDebitoriaService
                .trovaPerIdentificativo(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        return ResponseEntity.ok(mapper.toDto(posizione));
    }

    /**
     * Avviso PDF della posizione debitoria. Copre l'intera {@code PosizioneDebitoria}, incluse
     * le eventuali {@code OpzionePagamento} alternative.
     *
     * <p><b>Opzioni considerate</b>: si stampano solo le pendenze ancora {@code NON_ESEGUITO}
     * (vedi {@link #opzioniStampabili}). Nessuna opzione stampabile: 422
     * ({@link AvvisoDocumentoNonDisponibileException}).</p>
     *
     * <p><b>Layout</b>: esattamente un'opzione stampabile produce lo stesso avviso di
     * {@code getStampaPendenza}/{@code getAvvisiDocumento} (rata unica se l'opzione ha una sola
     * pendenza pagabile, cumulativo se ne ha piu' di una). Piu' di un'opzione stampabile
     * produce un solo PDF con {@code PaymentNotice.options}, una pagina (o piu', se l'opzione
     * ha piu' rate) per opzione, mai condivisa — vedi
     * {@link AvvisoPdfPayloadMapper#toPaymentNoticeOpzioni}. Marca da Bollo Telematica o
     * anagrafica debitore assente su QUALUNQUE pendenza stampabile di QUALUNQUE opzione fa
     * fallire l'intera stampa con 422.</p>
     *
     * <p>Un'opzione {@code SOLUZIONE_UNICA_ENTRO}/{@code _OLTRE} produce {@code reduced_payments}
     * invece di {@code full}. {@code postal} e' {@code true} solo se TUTTE le pendenze di TUTTE
     * le opzioni stampabili hanno un IBAN postale.</p>
     *
     * <p>Nessun parametro {@code linguaSecondaria}/{@code causaleTradotta}: l'avviso e' sempre
     * in italiano. Nome del file: {@code <idPosizioneDebitoria>.pdf}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> getStampaPosizioneDebitoria(String idA2A, String idPosizioneDebitoria) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        verificaAcceptCompatibileConPdf();

        it.govpay.pendenze.entity.PosizioneDebitoria posizione = posizioneDebitoriaService
                .trovaPerIdentificativo(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        List<List<Pendenza>> opzioni = opzioniStampabili(posizione);
        if (opzioni.isEmpty()) {
            throw new AvvisoDocumentoNonDisponibileException("Non sono state trovate opzioni di pagamento con rate "
                    + "pagabili da includere nella stampa della posizione debitoria [idA2A: " + idA2A
                    + " | idPosizioneDebitoria: " + idPosizioneDebitoria + "].");
        }
        for (List<Pendenza> pendenzeOpzione : opzioni) {
            for (Pendenza pendenza : pendenzeOpzione) {
                if (AvvisoPdfPayloadMapper.isPendenzaMbt(pendenza)) {
                    throw new AvvisoMbtException(
                            "Stampa posizione debitoria non disponibile: sono presenti dei pagamenti di Marca da Bollo");
                }
                if (AvvisoPdfPayloadMapper.isAnagraficaDebitoreAssente(pendenza)) {
                    throw new AvvisoAnagraficaAssenteException(
                            "Avviso PDF non disponibile: anagrafica del debitore assente.");
                }
            }
        }

        PaymentNotice payload;
        if (opzioni.size() == 1) {
            List<Pendenza> pendenzeOpzione = opzioni.get(0);
            payload = pendenzeOpzione.size() == 1
                    ? avvisoPdfPayloadMapper.toPaymentNotice(pendenzeOpzione.get(0), null, null, null, null)
                    : avvisoPdfPayloadMapper.toPaymentNoticeRate(pendenzeOpzione);
        } else {
            payload = avvisoPdfPayloadMapper.toPaymentNoticeOpzioni(opzioni);
        }

        currentResponse.setContentType(MediaType.APPLICATION_PDF_VALUE);
        currentResponse.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + idPosizioneDebitoria + ".pdf\"");
        try {
            var output = currentResponse.getOutputStream();
            externalCallMetricsRecorder.record("stampe", "payment_notice",
                    () -> stampeClient.streamPaymentNotice(payload, output));
            currentResponse.flushBuffer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Le pendenze pagabili ({@link StatoPendenza#NON_ESEGUITO}, ordinate per
     * {@code numeroRata}) di ciascuna opzione di pagamento della posizione — un elemento di
     * lista per opzione, nello stesso ordine di {@code PosizioneDebitoria.getOpzioniPagamento()},
     * con le opzioni senza alcuna pendenza pagabile escluse.
     */
    private static List<List<Pendenza>> opzioniStampabili(it.govpay.pendenze.entity.PosizioneDebitoria posizione) {
        List<List<Pendenza>> risultato = new ArrayList<>();
        for (it.govpay.pendenze.entity.OpzionePagamento opzione : posizione.getOpzioniPagamento()) {
            List<Pendenza> pagabili = opzione.getPendenze().stream()
                    .filter(p -> p.getStato() == StatoPendenza.NON_ESEGUITO)
                    .sorted(Comparator.comparingInt(Pendenza::getNumeroRata))
                    .toList();
            if (!pagabili.isEmpty()) {
                risultato.add(pagabili);
            }
        }
        return risultato;
    }

    /**
     * Verifica che l'Accept richiesto non escluda {@code application/pdf}, l'unico
     * content-type prodotto da questo endpoint.
     */
    private void verificaAcceptCompatibileConPdf() {
        String accept = currentRequest.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            return;
        }
        List<MediaType> richiesti = MediaType.parseMediaTypes(accept);
        if (qualitaPiuSpecifica(MediaType.APPLICATION_PDF, richiesti) <= 0) {
            throw new NotAcceptableMediaTypeException(
                    "Accept header non compatibile: questo endpoint produce solo application/pdf.");
        }
    }

    private static double qualitaPiuSpecifica(MediaType tipo, List<MediaType> richiesti) {
        return richiesti.stream()
                .filter(mt -> mt.isCompatibleWith(tipo))
                .max(Comparator.comparingInt(PosizioneDebitoriaController::specificita))
                .map(MediaType::getQualityValue)
                .orElse(0.0);
    }

    /** Match esatto (2) prevale su sottotipo wildcard, es. {@code application/*} (1), su {@code *}/{@code *} (0). */
    private static int specificita(MediaType mt) {
        if (!mt.isWildcardType() && !mt.isWildcardSubtype()) {
            return 2;
        }
        return mt.isWildcardType() ? 0 : 1;
    }

    /**
     * Applica un JSON Patch (RFC 6902, sottoinsieme add/remove/replace) alla posizione
     * debitoria — vedi Javadoc di {@link PosizioneDebitoriaMapper#toAggiornamentoPosizione} per i path
     * supportati e di {@link PosizioneDebitoriaService#aggiorna} per la rivalidazione e la
     * marcatura ACA applicate dopo il patch. Le opzioni di pagamento non si toccano qui
     * (vedi descrizione dell'operazione nello YAML).
     */
    @Override
    @Transactional
    public ResponseEntity<Void> updatePosizioneDebitoria(String idA2A, String idPosizioneDebitoria,
            List<PatchOp> patchOp) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (patchOp == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        posizioneDebitoriaService.aggiorna(idA2A, idPosizioneDebitoria,
                mapper.toAggiornamentoPosizione(patchOp));
        return ResponseEntity.ok().build();
    }

    /**
     * Aggiunge una nuova opzione di pagamento a una posizione debitoria esistente — vedi
     * Javadoc di {@link PosizioneDebitoriaService#aggiungiOpzionePagamento} per l'ordine non
     * banale con cui l'opzione viene costruita/validata prima di essere collegata
     * all'aggregato gestito. {@code @Transactional} per lo stesso motivo di
     * {@link #getPosizioneDebitoria}: {@link PosizioneDebitoriaMapper#toOpzionePagamentoDto}
     * legge {@code entity.getPendenze()} sull'opzione appena creata — non un problema di per
     * se' (e' un oggetto costruito in memoria da questo stesso mapper, non un proxy LAZY
     * caricato dal DB), ma l'aggiunta e' avvenuta dentro la transazione del servizio: estendere
     * il confine qui evita comunque ogni rischio se la forma dell'aggregato cambiasse in
     * futuro. Nessun header {@code Location}: lo YAML non espone un GET per la singola
     * opzione di pagamento.
     */
    @Override
    @Transactional
    public ResponseEntity<OpzionePagamento> addOpzionePagamento(String idA2A, String idPosizioneDebitoria,
            NuovaOpzionePagamento nuovaOpzionePagamento) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (nuovaOpzionePagamento == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        it.govpay.pendenze.entity.OpzionePagamento creata = posizioneDebitoriaService.aggiungiOpzionePagamento(
                idA2A, idPosizioneDebitoria,
                mapper.toOpzionePagamento(idA2A, dominioPosizione(idA2A, idPosizioneDebitoria), nuovaOpzionePagamento));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(mapper.toOpzionePagamentoDto(creata));
    }

    /**
     * Aggiunge una pendenza a un'opzione di pagamento ESISTENTE — vedi Javadoc di
     * {@code PosizioneDebitoriaService#aggiungiPendenza} per i vincoli (opzione
     * {@code DISPONIBILE}; la cardinalita' massima della tipologia e' verificata dal
     * validatore di libreria, non qui, perche' e' la stessa fonte unica usata in creazione).
     * L'appartenenza di {@code idOpzionePagamento} a QUESTA posizione/applicazione e'
     * verificata dal servizio, non qui — stesso principio di {@link #updateOpzionePagamento}.
     */
    @Override
    @Transactional
    public ResponseEntity<it.govpay.pendenze.api.model.Pendenza> addPendenza(String idA2A,
            String idPosizioneDebitoria, java.util.UUID idOpzionePagamento, NuovaPendenza nuovaPendenza) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        if (nuovaPendenza == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }
        it.govpay.pendenze.entity.Pendenza creata = posizioneDebitoriaService.aggiungiPendenza(idA2A,
                idPosizioneDebitoria, idOpzionePagamento,
                mapper.toPendenza(idA2A, dominioPosizione(idA2A, idPosizioneDebitoria), nuovaPendenza));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(mapper.toPendenzaDto(creata));
    }

    /**
     * Annulla o ripristina un'opzione di pagamento — le uniche due transizioni raggiungibili
     * da questo endpoint (vedi Javadoc di
     * {@link PosizioneDebitoriaMapper#validaPatchStatoOpzione}: l'attivazione e' innescata da
     * un pagamento reale, mai da un PATCH del chiamante). Entrambe cascata anche su tutte le
     * pendenze dell'opzione (vedi Javadoc di {@code PosizioneDebitoriaService#annulla(UUID)}/
     * {@code #ripristina(UUID)}) — nessuna regola di transizione qui, solo smistamento verso
     * il metodo giusto in base al valore validato. L'appartenenza di {@code idOpzionePagamento}
     * a QUESTA posizione/applicazione e' verificata dal servizio (vedi Javadoc di
     * {@code PosizioneDebitoriaService#annulla(String, String, UUID)}), non qui:
     * {@code idOpzionePagamento} da solo non e' altrimenti legato a nessun controllo di
     * appartenenza.
     */
    @Override
    @Transactional
    public ResponseEntity<Void> updateOpzionePagamento(String idA2A, String idPosizioneDebitoria,
            java.util.UUID idOpzionePagamento, List<PatchOp> patchOp) {
        aclAuthorizer.richiedeScrittura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        it.govpay.pendenze.model.StatoOpzionePagamento nuovoStato = mapper.validaPatchStatoOpzione(patchOp);
        if (nuovoStato == it.govpay.pendenze.model.StatoOpzionePagamento.ANNULLATA) {
            posizioneDebitoriaService.annulla(idA2A, idPosizioneDebitoria, idOpzionePagamento);
        } else {
            posizioneDebitoriaService.ripristina(idA2A, idPosizioneDebitoria, idOpzionePagamento);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Nessun campo ordinabile per default (vedi Javadoc di {@link CriteriOrdinamento}):
     * questo endpoint ne espone solo tre, ragionevoli per un elenco di posizioni e tutti
     * colonne dirette di {@code documenti} — nessuna indicazione esplicita nello YAML su
     * quali offrire. Ignorati in modalita' cursore, dove l'ordinamento e' fisso (vedi
     * {@link #ORDINAMENTO_FISSO_CURSORE}).
     */
    private static final Map<String, String> CAMPI_ORDINABILI = Map.of(
            "dataCreazione", "dataCreazione",
            "dataPubblicazione", "dataPubblicazione",
            // idPosizioneDebitoria (nome esterno, l'IUPD) non e' una colonna: si ordina sulla
            // sua parte persistita, codDocumento (vedi Javadoc di PosizioneDebitoria).
            "idPosizioneDebitoria", "codDocumento");

    /**
     * {@code @Transactional} qui per lo stesso motivo di {@link #getPosizioneDebitoria}:
     * {@code toIndexDto} legge {@code soggettiDebitori}, collezione LAZY.
     *
     * <p>Due modalita' di paginazione mutuamente esclusive (standard condiviso con
     * govpay-console-api): a offset ({@code page}/{@code limit}, ordinamento libero via
     * {@code sort}, conteggio opzionale via {@code total}) o a cursore ({@code cursor},
     * ordinamento fisso {@link #ORDINAMENTO_FISSO_CURSORE}, nessun conteggio). La modalita'
     * cursore e' attiva se {@code cursor} e' presente nella richiesta, anche vuoto (prima
     * pagina cursore); in tal caso {@code page}/{@code sort} espliciti e {@code total=true}
     * sono rifiutati con 400 ({@link #rifiutaSeIncompatibiliConCursore}).</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<PosizioniDebitorie> findPosizioniDebitorie(String idA2A, String idDebitore, Integer page,
            String cursor, Integer limit, String sort, String fields, Boolean total) {
        aclAuthorizer.richiedeLettura();
        currentApplicazioneService.verificaIdA2A(idA2A);
        boolean modalitaCursore = cursor != null;
        if (modalitaCursore) {
            rifiutaSeIncompatibiliConCursore(sort, total);
        }

        PosizioniDebitorie dto = new PosizioniDebitorie();
        List<PosizioneDebitoriaIndex> risultati;

        if (modalitaCursore) {
            CursorCodec.Cursore decodificato = cursor.isBlank() ? null : CursorCodec.decode(cursor);
            PaginaSenzaConteggio<PosizioneDebitoria> pagina = posizioneDebitoriaService.cercaPerDebitoreDaCursore(
                    idA2A, idDebitore, decodificato == null ? null : decodificato.dataCreazione(),
                    decodificato == null ? null : decodificato.id(), limit);
            risultati = pagina.risultati().stream().map(mapper::toIndexDto).toList();
            if (pagina.haAltriRisultati() && !pagina.risultati().isEmpty()) {
                PosizioneDebitoria ultima = pagina.risultati().get(pagina.risultati().size() - 1);
                dto.setNextCursor(CursorCodec.encode(ultima.getDataCreazione(), ultima.getId()));
            }
        } else {
            int paginaEffettiva = QueryParamUtils.isExplicit(currentRequest, "page") ? page : 1;
            Sort ordinamento = CriteriOrdinamento.parse(sort, CAMPI_ORDINABILI);
            Pageable pageable = OffsetPageRequest.of((long) (paginaEffettiva - 1) * limit, limit, ordinamento);

            Pagination pagination = new Pagination(paginaEffettiva, limit, false);
            if (Boolean.TRUE.equals(total)) {
                PaginaRisultati<PosizioneDebitoria> pagina = posizioneDebitoriaService.cercaPerDebitore(idA2A,
                        idDebitore, pageable);
                risultati = pagina.risultati().stream().map(mapper::toIndexDto).toList();
                pagination.setHasNextPage(pagina.haAltriRisultati());
                pagination.setTotalResults(Math.toIntExact(pagina.numeroRisultatiTotali()));
                pagination.setTotalPages(
                        (int) Math.ceil(pagina.numeroRisultatiTotali() / (double) limit));
            } else {
                PaginaSenzaConteggio<PosizioneDebitoria> pagina = posizioneDebitoriaService
                        .cercaPerDebitoreSenzaConteggio(idA2A, idDebitore, pageable);
                risultati = pagina.risultati().stream().map(mapper::toIndexDto).toList();
                pagination.setHasNextPage(pagina.haAltriRisultati());
            }
            dto.setPagination(pagination);
        }

        dto.setResults(SelezioneCampi.applica(risultati, fields, objectMapper));
        return ResponseEntity.ok(dto);
    }

    /**
     * In modalita' cursore sono incompatibili {@code page}/{@code sort} espliciti e
     * {@code total=true} (stessa regola di govpay-console-api,
     * {@code ListQueryValidator#rejectCursorIncompatible}). {@code page} richiede
     * l'ispezione della query string grezza ({@link QueryParamUtils#isExplicit}) perche' lo
     * YAML gli assegna un default (1): l'argomento del metodo generato vale sempre 1 se
     * assente, indistinguibile da un {@code ?page=1} esplicito. {@code sort}/{@code total}
     * non hanno questo problema ({@code sort} non ha default, {@code total} ha default
     * {@code false}: solo un valore esplicito {@code true} e' possibile).
     */
    private void rifiutaSeIncompatibiliConCursore(String sort, Boolean total) {
        if (QueryParamUtils.isExplicit(currentRequest, "page")) {
            throw new ValidazioneNonSuperataException(
                    "parametri 'page' e 'cursor' mutuamente esclusivi: usare solo uno dei due "
                            + "(cursor per paginazione keyset, page per paginazione a offset)");
        }
        if (sort != null && !sort.isBlank()) {
            throw new ValidazioneNonSuperataException(
                    "in modalita' cursore (?cursor=...) l'ordinamento e' fisso (" + ORDINAMENTO_FISSO_CURSORE
                            + "): non specificare 'sort'");
        }
        if (Boolean.TRUE.equals(total)) {
            throw new ValidazioneNonSuperataException(
                    "in modalita' cursore (?cursor=...) il conteggio totale non e' disponibile: "
                            + "'total=true' non e' compatibile. Usa la presenza di 'nextCursor' in "
                            + "risposta per sapere se ci sono altre pagine");
        }
    }
    private Long dominioPosizione(String idA2A, String idPosizioneDebitoria) {
        return posizioneDebitoriaService.trovaPerIdentificativo(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con idPosizioneDebitoria ["
                        + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]")).getIdDominio();
    }
}
