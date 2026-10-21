package it.govpay.pendenze.v2.pendenza;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.common.entity.ApplicazioneEntity;
import it.govpay.common.repository.ApplicazioneRepository;
import it.govpay.common.repository.DominioRepository;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.v2.api.model.RppIndex;
import it.govpay.pendenze.v2.api.rest.PendenzeApi;
import it.govpay.pendenze.v2.ricevuta.RppMapper;

/**
 * Implementa {@link PendenzeApi}: per ora {@link #getPendenza}/{@link #getPendenzaByAvviso},
 * le altre operazioni restano sul default generato (501) fino al loro sviluppo — stesso
 * approccio incrementale di {@code it.govpay.pendenze.pendenza.PendenzaController} (v3).
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

    public PendenzaV2Controller(PosizioneDebitoriaService posizioneDebitoriaService,
            PendenzaRepository pendenzaRepository, DominioRepository dominioRepository,
            ApplicazioneRepository applicazioneRepository, RptRepository rptRepository,
            AllegatoRepository allegatoRepository, PendenzaV2Mapper pendenzaV2Mapper, RppMapper rppMapper,
            AclAuthorizer aclAuthorizer, CurrentApplicazioneService currentApplicazioneService) {
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
     * {@code idDominio}+{@code numeroAvviso} identificano la pendenza univocamente da soli
     * (univocita' per dominio, vedi {@code PosizioneDebitoriaService#verificaNumeroAvvisoNonDuplicato}),
     * ma il chiamante deve comunque essere l'applicazione proprietaria — stesso controllo del
     * legacy ({@code PendenzeController.getPendenzaByAvviso}, "filtro sull'applicazione"), qui
     * fatto dopo aver trovato la pendenza (non si puo' verificare prima, l'idA2A proprietario
     * non e' ancora noto). Un {@code idDominio} inesistente e' trattato come "pendenza non
     * trovata" (404), non un errore di anagrafica separato: dal punto di vista del chiamante
     * nessuna pendenza puo' comunque esistere su un dominio che non esiste.
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
        Pendenza entity = dominioRepository.findByCodDominio(idDominio)
                .flatMap(dominio -> pendenzaRepository.findByIdDominioAndNumeroAvviso(dominio.getId(), numeroAvviso))
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna pendenza con idDominio [" + idDominio
                        + "] e numeroAvviso [" + numeroAvviso + "]"));

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
