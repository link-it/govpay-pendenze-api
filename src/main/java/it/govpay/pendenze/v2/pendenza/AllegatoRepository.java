package it.govpay.pendenze.v2.pendenza;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllegatoRepository extends JpaRepository<AllegatoEntity, Long> {

    List<AllegatoEntity> findByIdVersamentoOrderByIdAsc(Long idVersamento);
}
