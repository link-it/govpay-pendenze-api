package it.govpay.pendenze.v2.pendenza;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllegatoRepository extends JpaRepository<AllegatoEntity, Long> {

    List<AllegatoEntity> findByIdVersamentoOrderByIdAsc(Long idVersamento);

    /** Usato da {@code PendenzaV2Controller#salvaAllegati} su un aggiornamento (REPLACE, non
     *  APPEND) — stessa semantica del legacy, vedi Javadoc del chiamante. */
    void deleteByIdVersamento(Long idVersamento);
}
