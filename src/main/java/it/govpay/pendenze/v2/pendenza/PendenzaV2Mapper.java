package it.govpay.pendenze.v2.pendenza;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.TipoVersamentoDominioEntity;
import it.govpay.common.entity.IbanAccreditoEntity;
import it.govpay.common.entity.UnitaOperativaEntity;
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
import it.govpay.pendenze.avviso.AvvisoMapper;
import it.govpay.pendenze.model.TipologiaOpzionePagamento;
import it.govpay.pendenze.service.CausaleVersamentoCodec;
import it.govpay.pendenze.v2.api.model.Avviso;
import it.govpay.pendenze.v2.api.model.Documento;
import it.govpay.pendenze.v2.api.model.Dominio;
import it.govpay.pendenze.v2.api.model.ProprietaPendenza;
import it.govpay.pendenze.v2.api.model.Segnalazione;
import it.govpay.pendenze.v2.api.model.Soggetto;
import it.govpay.pendenze.v2.api.model.StatoAvviso;
import it.govpay.pendenze.v2.api.model.StatoPendenza;
import it.govpay.pendenze.v2.api.model.TassonomiaAvviso;
import it.govpay.pendenze.v2.api.model.TipoPendenzaTipologia;
import it.govpay.pendenze.v2.api.model.TipoSoggetto;
import it.govpay.pendenze.v2.api.model.TipoSogliaVincoloPagamento;
import it.govpay.pendenze.v2.api.model.VincoloPagamento;

/**
 * Mapping dei campi "core" di {@code Pendenza}/{@code PendenzaIndex} (dello YAML v2) — tutto
 * tranne il contenuto di {@code rpp[]} (vedi {@code it.govpay.pendenze.v2.ricevuta}, che
 * riusa {@link #toIndexDto} per popolare {@code Rpp(Index).pendenza}).
 * Stessa divisione del legacy: logica di dominio (stato, risoluzione anagrafica) mai
 * duplicata, {@code it.govpay.pendenze.entity.Pendenza}/{@code PosizioneDebitoria}/
 * {@code VocePendenza} restano l'unica fonte.
 *
 * <p>Risoluzione dei codici testuali (idA2A/idDominio/idTipoPendenza/idUnita) dagli id tecnici
 * — stesso pattern e stesse query di {@code PosizioneDebitoriaMapper} (v3-nativo, package
 * diverso): non riusabile direttamente (quei metodi sono privati e restituiscono/accettano
 * DTO v3), ma la risoluzione stessa non e' logica specifica di v2, solo ripetuta qui.</p>
 */
@Component
public class PendenzaV2Mapper {

    /** Prefisso riservato ai documenti sintetizzati dalla migrazione per i versamenti orfani
     *  (vedi {@code 05_documenti_orfani.sql} in govpay-common-pendenze) — un v2 orfano non ha
     *  mai avuto un documento reale, quindi non va mostrato qui (coerente con il legacy, che
     *  omette {@code documento} per una pendenza senza documento). Lo stesso vale per un
     *  eventuale orfano creato in futuro direttamente dal wrapper in scrittura (non ancora
     *  disegnata): quella scrittura dovra' riusare questo stesso prefisso, o questo controllo
     *  andra' esteso. */
    private static final String PREFISSO_DOCUMENTO_SINTETIZZATO = "MIGR-";

    private final ApplicazioneRepository applicazioneRepository;
    private final DominioRepository dominioRepository;
    private final UnitaOperativaRepository unitaOperativaRepository;
    private final TipoVersamentoDominioRepository tipoVersamentoDominioRepository;
    private final TributoRepository tributoRepository;
    private final IbanAccreditoRepository ibanAccreditoRepository;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public PendenzaV2Mapper(ApplicazioneRepository applicazioneRepository, DominioRepository dominioRepository,
            UnitaOperativaRepository unitaOperativaRepository,
            TipoVersamentoDominioRepository tipoVersamentoDominioRepository, TributoRepository tributoRepository,
            IbanAccreditoRepository ibanAccreditoRepository) {
        this.applicazioneRepository = applicazioneRepository;
        this.dominioRepository = dominioRepository;
        this.unitaOperativaRepository = unitaOperativaRepository;
        this.tipoVersamentoDominioRepository = tipoVersamentoDominioRepository;
        this.tributoRepository = tributoRepository;
        this.ibanAccreditoRepository = ibanAccreditoRepository;
    }

    /**
     * @param entity   la pendenza, con {@code voci} gia' caricate (join fetch o transazione
     *                 aperta: {@link Pendenza#getVoci()} e' {@code LAZY})
     * @param rpp      contenuto gia' calcolato di {@code rpp[]} (vedi
     *                 {@code it.govpay.pendenze.v2.ricevuta.RppContenutoMapper}), passato da
     *                 fuori: questa classe non accede a {@code Rpt}, stessa separazione dei
     *                 due blocchi del wrapper.
     * @param allegati elenco gia' caricato ({@link AllegatoRepository}), stessa separazione
     *                 di {@code rpp}: {@code AllegatoEntity} non e' una relazione JPA di
     *                 {@link Pendenza} (e' scoped al wrapper, vedi Javadoc di
     *                 {@link AllegatoEntity}), va risolto da fuori.
     */
    public it.govpay.pendenze.v2.api.model.Pendenza toDto(Pendenza entity,
            List<it.govpay.pendenze.v2.api.model.RppIndex> rpp, List<AllegatoEntity> allegati) {
        it.govpay.pendenze.v2.api.model.Pendenza dto = new it.govpay.pendenze.v2.api.model.Pendenza();

        dto.setIdA2A(risolviCodApplicazione(entity.getIdApplicazione()));
        dto.setIdPendenza(entity.getIdPendenza());
        dto.setIdTipoPendenza(risolviCodTipoVersamento(entity.getIdTipoPendenza()));
        dto.setDominio(risolviDominioDto(entity.getIdDominio()));
        dto.setUnitaOperativa(risolviUnitaOperativaDto(entity));
        dto.setStato(risolviStato(entity));
        dto.setDescrizioneStato(entity.getDescrizioneStato());
        dto.setSegnalazioni(toSegnalazioni(entity.getAnomalie()));
        dto.setIuvAvviso(entity.getIuv());
        dto.setIuvPagamento(entity.getIuvPagamento());
        dto.setDataPagamento(entity.getDataPagamento() == null ? null : entity.getDataPagamento().toLocalDate());
        dto.setCausale(CausaleVersamentoCodec.decode(entity.getCausaleVersamento()));
        dto.setSoggettoPagatore(toSoggettoDto(primoSoggetto(entity)));
        dto.setImporto(entity.getImporto());
        dto.setNumeroAvviso(entity.getNumeroAvviso());
        dto.setDataCaricamento(entity.getDataCreazione().toLocalDate());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenza(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        dto.setAnnoRiferimento(entity.getCodAnnoTributario() == null ? null
                : Integer.valueOf(entity.getCodAnnoTributario()));
        dto.setCartellaPagamento(entity.getCodLotto());
        dto.setDatiAllegati(toJsonObject(entity.getDatiAllegati()));
        dto.setTassonomia(entity.getTassonomia());
        dto.setTassonomiaAvviso(entity.getTassonomiaAvviso() == null ? null
                : TassonomiaAvviso.fromValue(entity.getTassonomiaAvviso()));
        dto.setDirezione(entity.getDirezione());
        dto.setDivisione(entity.getDivisione());
        dto.setDocumento(toDocumentoDto(entity));
        // Pendenza.tipo e' sempre "DOVUTO" in questa libreria (v3 carica solo pendenze
        // DOVUTO, vedi Javadoc di classe) — mai SPONTANEO, a differenza del legacy che
        // distingue i due casi.
        dto.setTipo(TipoPendenzaTipologia.DOVUTO);
        dto.setUUID(entity.getIdSessione());
        dto.setProprieta(toProprietaPendenzaDto(entity.getProprieta()));
        dto.setSendAbilitato(entity.isSendAbilitato());
        dto.setVoci(entity.getVoci().stream().map(this::toVocePendenzaDto).toList());
        dto.setRpp(rpp);
        dto.setAllegati(allegati.stream().map(this::toAllegatoDto).toList());
        return dto;
    }

    /**
     * {@code tipo} e' impostato cosi' com'e' in colonna, anche se {@code null} (sovrascrive
     * il default {@code "application/octet-stream"} del DTO generato): stesso comportamento
     * del legacy ({@code PendenzeConverter.toAllegatiRsModel}).
     */
    private it.govpay.pendenze.v2.api.model.AllegatoPendenza toAllegatoDto(AllegatoEntity entity) {
        it.govpay.pendenze.v2.api.model.AllegatoPendenza dto = new it.govpay.pendenze.v2.api.model.AllegatoPendenza();
        dto.setNome(entity.getNome());
        dto.setTipo(entity.getTipo());
        dto.setDescrizione(entity.getDescrizione());
        dto.setContenuto("/allegati/" + entity.getId());
        return dto;
    }

    /**
     * Versione "indice" di {@link #toDto}, usata per {@code Rpp(Index).pendenza} (il
     * riferimento alla pendenza annidato in ogni richiesta di pagamento, vedi
     * {@code it.govpay.pendenze.v2.ricevuta}) — stessi campi "core" tranne {@code voci}/
     * {@code allegati} (qui assenti) e {@code rpp}/{@code pagamenti}, qui URL verso le liste
     * invece dei contenuti annidati. Non accede a {@link Pendenza#getVoci()} (LAZY): puo'
     * essere chiamato anche fuori dalla transazione che ha caricato le voci, a differenza di
     * {@link #toDto}. Stessa divisione in due metodi indipendenti del legacy
     * ({@code PendenzeConverter.toRsModel}/{@code toRsIndexModel}).
     */
    public it.govpay.pendenze.v2.api.model.PendenzaIndex toIndexDto(Pendenza entity) {
        it.govpay.pendenze.v2.api.model.PendenzaIndex dto = new it.govpay.pendenze.v2.api.model.PendenzaIndex();

        String idA2A = risolviCodApplicazione(entity.getIdApplicazione());
        dto.setIdA2A(idA2A);
        dto.setIdPendenza(entity.getIdPendenza());
        dto.setIdTipoPendenza(risolviCodTipoVersamento(entity.getIdTipoPendenza()));
        dto.setDominio(risolviDominioDto(entity.getIdDominio()));
        dto.setUnitaOperativa(risolviUnitaOperativaDto(entity));
        dto.setStato(risolviStato(entity));
        dto.setDescrizioneStato(entity.getDescrizioneStato());
        dto.setSegnalazioni(toSegnalazioni(entity.getAnomalie()));
        dto.setIuvAvviso(entity.getIuv());
        dto.setIuvPagamento(entity.getIuvPagamento());
        dto.setDataPagamento(entity.getDataPagamento() == null ? null : entity.getDataPagamento().toLocalDate());
        dto.setCausale(CausaleVersamentoCodec.decode(entity.getCausaleVersamento()));
        dto.setSoggettoPagatore(toSoggettoDto(primoSoggetto(entity)));
        dto.setImporto(entity.getImporto());
        dto.setNumeroAvviso(entity.getNumeroAvviso());
        dto.setDataCaricamento(entity.getDataCreazione().toLocalDate());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenza(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        dto.setAnnoRiferimento(entity.getCodAnnoTributario() == null ? null
                : Integer.valueOf(entity.getCodAnnoTributario()));
        dto.setCartellaPagamento(entity.getCodLotto());
        dto.setDatiAllegati(toJsonObject(entity.getDatiAllegati()));
        dto.setTassonomia(entity.getTassonomia());
        dto.setTassonomiaAvviso(entity.getTassonomiaAvviso() == null ? null
                : TassonomiaAvviso.fromValue(entity.getTassonomiaAvviso()));
        dto.setDirezione(entity.getDirezione());
        dto.setDivisione(entity.getDivisione());
        dto.setDocumento(toDocumentoDto(entity));
        dto.setTipo(TipoPendenzaTipologia.DOVUTO);
        dto.setUUID(entity.getIdSessione());
        dto.setProprieta(toProprietaPendenzaDto(entity.getProprieta()));
        dto.setSendAbilitato(entity.isSendAbilitato());
        dto.setRpp(urlListaFiltrataPerPendenza("/rpp", idA2A, entity.getIdPendenza()));
        // Nessun endpoint "/pagamenti" ancora esposto da questo wrapper (assente dallo YAML
        // v2 attuale; anche il legacy, verificato, non popola mai questo campo nonostante sia
        // obbligatorio nello schema) — stessa forma relativa di {@code rpp}, per ora un
        // placeholder che rispetta comunque il contratto v2.
        dto.setPagamenti(urlListaFiltrataPerPendenza("/pagamenti", idA2A, entity.getIdPendenza()));
        return dto;
    }

    private String urlListaFiltrataPerPendenza(String path, String idA2A, String idPendenza) {
        return UriComponentsBuilder.fromPath(path).queryParam("idA2A", idA2A).queryParam("idPendenza", idPendenza)
                .build().toUriString();
    }

    /**
     * Replica lo switch del legacy ({@code PendenzeConverter.toRsModel}): deriva lo stato v2
     * da {@code stato}+{@code statoPagamento}+{@code anomalo} — tre informazioni legacy
     * distinte che {@code Pendenza} mappa gia' tutte (stessi 8 valori di
     * {@code StatoVersamento}, vedi Javadoc di {@link it.govpay.pendenze.model.StatoPendenza}).
     */
    private StatoPendenza risolviStato(Pendenza entity) {
        StatoPendenza stato;
        switch (entity.getStato()) {
            case ANNULLATO -> stato = StatoPendenza.ANNULLATA;
            case ESEGUITO, ESEGUITO_SENZA_RPT -> {
                stato = StatoPendenza.ESEGUITA;
                if (entity.getStatoPagamento() == it.govpay.pendenze.model.StatoPagamento.INCASSATO) {
                    stato = StatoPendenza.INCASSATA;
                }
            }
            case ESEGUITO_ALTRO_CANALE -> stato = StatoPendenza.ESEGUITA;
            case NON_ESEGUITO -> stato = isScaduta(entity) ? StatoPendenza.SCADUTA : StatoPendenza.NON_ESEGUITA;
            case PARZIALMENTE_ESEGUITO -> stato = StatoPendenza.ESEGUITA_PARZIALE;
            default -> stato = StatoPendenza.NON_ESEGUITA;
        }
        return entity.isAnomalo() ? StatoPendenza.ANOMALA : stato;
    }

    /**
     * Replica {@code PendenzeConverter.toAvvisoRsModel} ({@code GET /avvisi/{idDominio}/
     * {numeroAvviso}}) — switch DIVERSO da {@link #risolviStato}: qui solo {@code stato}, mai
     * {@code statoPagamento}/{@code anomalo}. {@code ANOMALO}/{@code ESEGUITO_SENZA_RPT}/
     * {@code INCASSATO} non hanno un caso (nessuno nello switch originale): {@code stato}
     * resta {@code null}, anche se lo schema lo dichiara {@code required}.
     */
    public Avviso toAvvisoDto(Pendenza entity) {
        DominioEntity dominio = dominioRepository.findById(entity.getIdDominio())
                .orElseThrow(() -> new IllegalStateException("idDominio [" + entity.getIdDominio()
                        + "] della pendenza non risolve a nessun dominio censito"));
        Avviso dto = new Avviso();
        dto.setStato(risolviStatoAvviso(entity));
        dto.setImporto(entity.getImporto());
        dto.setIdDominio(dominio.getCodDominio());
        dto.setNumeroAvviso(entity.getNumeroAvviso());
        dto.setDataValidita(entity.getDataValidita() == null ? null : entity.getDataValidita().toLocalDate());
        dto.setDataScadenza(
                entity.getDataScadenzaAvviso() == null ? null : entity.getDataScadenzaAvviso().toLocalDate());
        dto.setDataPagamento(entity.getDataPagamento() == null ? null : entity.getDataPagamento().toLocalDate());
        dto.setDescrizione(risolviDescrizioneAvviso(entity));
        dto.setTassonomiaAvviso(entity.getTassonomiaAvviso() == null ? null
                : TassonomiaAvviso.fromValue(entity.getTassonomiaAvviso()));
        dto.setQrcode(AvvisoMapper.buildQrcode(entity, dominio));
        dto.setBarcode(AvvisoMapper.buildBarcode(entity, dominio));
        return dto;
    }

    /**
     * Causale della pendenza se presente, altrimenti la descrizione della posizione debitoria
     * — stesso fallback di {@code AvvisoPdfPayloadMapper#oggettoPagamentoRataUnica}, usato
     * dalla stampa PDF di questo stesso avviso.
     */
    private String risolviDescrizioneAvviso(Pendenza entity) {
        String causale = CausaleVersamentoCodec.decode(entity.getCausaleVersamento());
        return causale != null ? causale : entity.getOpzionePagamento().getPosizioneDebitoria().getDescrizione();
    }

    private StatoAvviso risolviStatoAvviso(Pendenza entity) {
        return switch (entity.getStato()) {
            case ANNULLATO -> StatoAvviso.ANNULLATA;
            case ESEGUITO, ESEGUITO_ALTRO_CANALE, PARZIALMENTE_ESEGUITO -> StatoAvviso.DUPLICATA;
            case NON_ESEGUITO -> isScaduta(entity) ? StatoAvviso.SCADUTA : StatoAvviso.NON_ESEGUITA;
            default -> null;
        };
    }

    /** Stessa soglia del legacy ({@code DateUtils.CONTROLLO_SCADENZA}, mezzanotte del giorno
     *  indicato: una pendenza e' scaduta solo dal giorno DOPO {@code dataScadenzaAvviso}). */
    private boolean isScaduta(Pendenza entity) {
        if (entity.getDataScadenzaAvviso() == null) {
            return false;
        }
        return entity.getDataScadenzaAvviso().toLocalDate().isBefore(java.time.LocalDate.now());
    }

    private SoggettoDebitore primoSoggetto(Pendenza entity) {
        PosizioneDebitoria posizione = posizioneDi(entity);
        if (posizione == null || posizione.getSoggettiDebitori().isEmpty()) {
            return null;
        }
        // Soggetto di ordine 0 per convenzione — stesso principio gia' adottato per il
        // soggetto pagatore RPT (vedi proposta-modello-nativo-v3.md, Punto 1).
        return posizione.getSoggettiDebitori().get(0);
    }

    private PosizioneDebitoria posizioneDi(Pendenza entity) {
        OpzionePagamento opzione = entity.getOpzionePagamento();
        return opzione == null ? null : opzione.getPosizioneDebitoria();
    }

    private Soggetto toSoggettoDto(SoggettoDebitore entity) {
        if (entity == null) {
            return null;
        }
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
        dto.setCellulare(entity.getCellulare());
        return dto;
    }

    /**
     * {@code null} per una pendenza orfana (mai avuto un documento reale, vedi
     * {@link #PREFISSO_DOCUMENTO_SINTETIZZATO}) o priva di opzione di pagamento (creata da
     * v2/migrazione senza che questo passo sia ancora stato completato).
     */
    private Documento toDocumentoDto(Pendenza entity) {
        OpzionePagamento opzione = entity.getOpzionePagamento();
        PosizioneDebitoria posizione = posizioneDi(entity);
        if (opzione == null || posizione == null
                || posizione.getCodDocumento().startsWith(PREFISSO_DOCUMENTO_SINTETIZZATO)) {
            return null;
        }
        Documento dto = new Documento();
        dto.setIdentificativo(posizione.getCodDocumento());
        dto.setDescrizione(posizione.getDescrizione());
        switch (opzione.getTipologia()) {
            case PIANO_RATEALE -> dto.setRata(entity.getNumeroRata());
            case SOLUZIONE_UNICA_ENTRO -> dto.setSoglia(toVincoloPagamento(TipoSogliaVincoloPagamento.ENTRO, opzione));
            case SOLUZIONE_UNICA_OLTRE -> dto.setSoglia(toVincoloPagamento(TipoSogliaVincoloPagamento.OLTRE, opzione));
            case SOLUZIONE_UNICA_RIDOTTO ->
                dto.setSoglia(toVincoloPagamento(TipoSogliaVincoloPagamento.RIDOTTO, opzione));
            case SOLUZIONE_UNICA_SCONTATO ->
                dto.setSoglia(toVincoloPagamento(TipoSogliaVincoloPagamento.SCONTATO, opzione));
            case SOLUZIONE_UNICA -> {
                // Nessun rata/soglia: un vero documento con un'unica soluzione, nessuna
                // informazione aggiuntiva da riportare.
            }
        }
        return dto;
    }

    private VincoloPagamento toVincoloPagamento(TipoSogliaVincoloPagamento tipo, OpzionePagamento opzione) {
        VincoloPagamento soglia = new VincoloPagamento();
        soglia.setTipo(tipo);
        soglia.setGiorni(opzione.getGiorni());
        return soglia;
    }

    /**
     * Le tre varianti dell'unico {@code VocePendenza} di risposta (nessun discriminator a
     * differenza della richiesta) — stesso smistamento per {@code tipoRiferimento} di
     * {@code PosizioneDebitoriaMapper#toVocePendenzaDto} (v3-nativo), senza dettaglio
     * contabile (concetto assente dallo YAML v2).
     */
    private it.govpay.pendenze.v2.api.model.VocePendenza toVocePendenzaDto(VocePendenza entity) {
        it.govpay.pendenze.v2.api.model.VocePendenza dto = new it.govpay.pendenze.v2.api.model.VocePendenza();
        dto.setIdVocePendenza(entity.getIdVocePendenza());
        dto.setImporto(entity.getImporto());
        dto.setDescrizione(entity.getDescrizione());
        dto.setDescrizioneCausaleRPT(entity.getDescrizioneCausaleRpt());
        dto.setIndice(entity.getIndice());
        dto.setStato(entity.getStato() == null ? null
                : it.govpay.pendenze.v2.api.model.StatoVocePendenza.valueOf(entity.getStato().name()));
        dto.setDominio(entity.getIdDominio() == null ? null : risolviDominioDto(entity.getIdDominio()));

        switch (entity.getTipoRiferimento()) {
            case RIFERIMENTO_ENTRATA -> dto.setCodEntrata(risolviCodEntrata(entity.getIdTributo()));
            case ENTRATA -> {
                dto.setIbanAccredito(risolviCodIban(entity.getIdIbanAccredito()));
                dto.setIbanAppoggio(risolviCodIban(entity.getIdIbanAppoggio()));
                if (entity.getTipoContabilita() != null) {
                    dto.setTipoContabilita(it.govpay.pendenze.v2.api.model.TipoContabilita
                            .valueOf(entity.getTipoContabilita().name()));
                }
                dto.setCodiceContabilita(entity.getCodiceContabilita());
            }
            case BOLLO -> {
                dto.setTipoBollo(entity.getTipoBollo() == null ? null
                        : it.govpay.pendenze.v2.api.model.VocePendenza.TipoBolloEnum.fromValue(entity.getTipoBollo()));
                dto.setHashDocumento(entity.getHashDocumento());
                dto.setProvinciaResidenza(entity.getProvinciaResidenza());
            }
        }
        return dto;
    }

    private List<Segnalazione> toSegnalazioni(String anomalie) {
        List<Segnalazione> segnalazioni = new ArrayList<>();
        if (anomalie == null || anomalie.isBlank()) {
            return segnalazioni;
        }
        // Stesso formato del legacy ("codice#descrizione|codice#descrizione|..."), solo
        // codice/descrizione disponibili: data/dettaglio non fanno parte di questo formato,
        // anche il legacy stesso (PendenzeConverter.unmarshall) le lascia assenti.
        for (String voce : anomalie.split("\\|")) {
            String[] parti = voce.split("#", 2);
            Segnalazione segnalazione = new Segnalazione();
            segnalazione.setCodice(parti[0]);
            segnalazione.setDescrizione(parti.length > 1 ? parti[1] : "");
            segnalazioni.add(segnalazione);
        }
        return segnalazioni;
    }

    /**
     * {@code datiAllegati}/vedi {@link #toJsonObject}: JSON libero del chiamante, mai scritto
     * da questa libreria — si assume che sia stato scritto a suo tempo da questa stessa API
     * v2 (nessuna verifica contro dati reali diversi).
     */
    private Object toJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("datiAllegati non e' un JSON valido", e);
        }
    }

    ProprietaPendenza toProprietaPendenzaDto(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, ProprietaPendenza.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("proprieta non e' nel formato ProprietaPendenza atteso", e);
        }
    }

    private String risolviCodApplicazione(Long idApplicazione) {
        return applicazioneRepository.findById(idApplicazione).map(ApplicazioneEntity::getCodApplicazione)
                .orElse(null);
    }

    private Dominio risolviDominioDto(Long idDominio) {
        DominioEntity entity = dominioRepository.findById(idDominio).orElse(null);
        if (entity == null) {
            return null;
        }
        Dominio dto = new Dominio();
        dto.setIdDominio(entity.getCodDominio());
        dto.setRagioneSociale(entity.getRagioneSociale());
        return dto;
    }

    /**
     * {@code null} se l'unita' operativa e' assente o e' "EC" (l'ente creditore stesso, non
     * una vera unita' operativa — stesso filtro del legacy, {@code uo.getCodUo().equals(Dominio.EC)}).
     */
    private it.govpay.pendenze.v2.api.model.UnitaOperativa risolviUnitaOperativaDto(Pendenza entity) {
        PosizioneDebitoria posizione = posizioneDi(entity);
        Long idUnitaOperativa = posizione == null ? null : posizione.getIdUnitaOperativa();
        if (idUnitaOperativa == null) {
            return null;
        }
        UnitaOperativaEntity uo = unitaOperativaRepository.findById(idUnitaOperativa).orElse(null);
        if (uo == null || "EC".equals(uo.getCodUo())) {
            return null;
        }
        it.govpay.pendenze.v2.api.model.UnitaOperativa dto = new it.govpay.pendenze.v2.api.model.UnitaOperativa();
        dto.setIdUnita(uo.getCodUo());
        dto.setRagioneSociale(uo.getUoDenominazione());
        dto.setIndirizzo(uo.getUoIndirizzo());
        dto.setCivico(uo.getUoCivico());
        dto.setCap(uo.getUoCap());
        dto.setLocalita(uo.getUoLocalita());
        dto.setProvincia(uo.getUoProvincia());
        dto.setNazione(uo.getUoNazione());
        dto.setEmail(uo.getUoEmail());
        dto.setPec(uo.getUoPec());
        dto.setTel(uo.getUoTel());
        dto.setFax(uo.getUoFax());
        dto.setWeb(uo.getUoUrlSitoWeb());
        dto.setArea(uo.getUoArea());
        return dto;
    }

    private String risolviCodTipoVersamento(Long idTipoVersamentoDominio) {
        if (idTipoVersamentoDominio == null) {
            return null;
        }
        return tipoVersamentoDominioRepository.findByIdFetchTipoVersamento(idTipoVersamentoDominio)
                .map(TipoVersamentoDominioEntity::getTipoVersamento)
                .map(it.govpay.common.entity.TipoVersamentoEntity::getCodTipoVersamento)
                .orElse(null);
    }

    /** Stessa risoluzione di {@code PosizioneDebitoriaMapper#risolviCodEntrata} (v3-nativo). */
    private String risolviCodEntrata(Long idTributo) {
        return idTributo == null ? null
                : tributoRepository.findById(idTributo).map(t -> t.getTipoTributo().getCodTributo()).orElse(null);
    }

    /** Stessa risoluzione di {@code PosizioneDebitoriaMapper#risolviCodIban} (v3-nativo). */
    private String risolviCodIban(Long idIban) {
        return idIban == null ? null
                : ibanAccreditoRepository.findById(idIban).map(IbanAccreditoEntity::getCodIban).orElse(null);
    }
}
