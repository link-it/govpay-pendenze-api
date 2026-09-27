package it.govpay.pendenze.posizionedebitoria;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import it.govpay.pendenze.api.model.NuovaPosizioneDebitoria;
import it.govpay.pendenze.api.rest.PosizioniDebitorieApi;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Implementa {@link PosizioniDebitorieApi}: per ora {@link #addPosizioneDebitoria} e
 * {@link #getPosizioneDebitoria}, le altre operazioni restano sul default generato (501, vedi
 * Javadoc dell'interfaccia) fino al loro sviluppo.
 */
@RestController
public class PosizioneDebitoriaController implements PosizioniDebitorieApi {

    private final PosizioneDebitoriaMapper mapper;
    private final PosizioneDebitoriaService posizioneDebitoriaService;

    public PosizioneDebitoriaController(PosizioneDebitoriaMapper mapper,
            PosizioneDebitoriaService posizioneDebitoriaService) {
        this.mapper = mapper;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
    }

    @Override
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> addPosizioneDebitoria(String idA2A,
            NuovaPosizioneDebitoria nuovaPosizioneDebitoria) {
        if (nuovaPosizioneDebitoria == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }

        it.govpay.pendenze.entity.PosizioneDebitoria posizione = mapper.toEntity(idA2A, nuovaPosizioneDebitoria);
        it.govpay.pendenze.entity.PosizioneDebitoria creata = posizioneDebitoriaService.crea(posizione);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(PosizioniDebitorieApi.PATH_GET_POSIZIONE_DEBITORIA)
                .buildAndExpand(idA2A, creata.getIdPosizioneDebitoria())
                .toUri();

        return ResponseEntity.created(location).body(mapper.toDto(creata));
    }

    /**
     * {@code @Transactional} qui, non solo su {@code PosizioneDebitoriaService.trovaPerIdentificativo}
     * (bug del lead, 2026-09-27): quel metodo apre e chiude la propria transazione prima di
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
        it.govpay.pendenze.entity.PosizioneDebitoria posizione = posizioneDebitoriaService
                .trovaPerIdentificativo(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        return ResponseEntity.ok(mapper.toDto(posizione));
    }
}
