package it.govpay.pendenze.v2.pendenza;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.repository.RptRepository;
import it.govpay.pendenze.security.AclAuthorizer;
import it.govpay.pendenze.security.CurrentApplicazioneService;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import it.govpay.pendenze.v2.api.model.RppIndex;
import it.govpay.pendenze.v2.api.rest.PendenzeApi;
import it.govpay.pendenze.v2.ricevuta.RppMapper;

/**
 * Implementa {@link PendenzeApi}: per ora solo {@link #getPendenza}, le altre operazioni
 * restano sul default generato (501) fino al loro sviluppo — stesso approccio incrementale
 * di {@code it.govpay.pendenze.pendenza.PendenzaController} (v3).
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
    private final RptRepository rptRepository;
    private final AllegatoRepository allegatoRepository;
    private final PendenzaV2Mapper pendenzaV2Mapper;
    private final RppMapper rppMapper;
    private final AclAuthorizer aclAuthorizer;
    private final CurrentApplicazioneService currentApplicazioneService;

    public PendenzaV2Controller(PosizioneDebitoriaService posizioneDebitoriaService, RptRepository rptRepository,
            AllegatoRepository allegatoRepository, PendenzaV2Mapper pendenzaV2Mapper, RppMapper rppMapper,
            AclAuthorizer aclAuthorizer, CurrentApplicazioneService currentApplicazioneService) {
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.rptRepository = rptRepository;
        this.allegatoRepository = allegatoRepository;
        this.pendenzaV2Mapper = pendenzaV2Mapper;
        this.rppMapper = rppMapper;
        this.aclAuthorizer = aclAuthorizer;
        this.currentApplicazioneService = currentApplicazioneService;
    }

    /**
     * {@code @Transactional} qui, non solo sul servizio (stesso motivo di
     * {@code PendenzaController#getPendenza}, v3): {@code pendenzaV2Mapper.toDto} e
     * {@code rppMapper.toRppIndex} attraversano {@code Pendenza.voci}/{@code opzionePagamento}/
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

        List<Rpt> rpts = rptRepository.findByIdVersamentoOrderByDataMsgRichiestaAsc(entity.getId());
        List<RppIndex> rpp = rpts.stream().map(rpt -> rppMapper.toRppIndex(rpt, entity)).toList();
        List<AllegatoEntity> allegati = allegatoRepository.findByIdVersamentoOrderByIdAsc(entity.getId());

        return ResponseEntity.ok(pendenzaV2Mapper.toDto(entity, rpp, allegati));
    }
}
