package it.govpay.pendenze.security;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UtenzaTipoVersamentoRepository extends JpaRepository<UtenzaTipoVersamentoEntity, Long> {

    boolean existsByIdUtenzaAndIdTipoVersamento(Long idUtenza, Long idTipoVersamento);
}
