package it.govpay.pendenze.posizionedebitoria;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import it.govpay.pendenze.api.model.NuovaPosizioneDebitoria;
import it.govpay.pendenze.api.rest.PosizioniDebitorieApi;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.service.PosizioneDebitoriaService;

/**
 * Implementa {@link PosizioniDebitorieApi}: per ora solo {@link #addPosizioneDebitoria}, le
 * altre operazioni restano sul default generato (501, vedi Javadoc dell'interfaccia) fino al
 * loro sviluppo.
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
}
