package it.govpay.pendenze.security;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UtenzaDominioRepository extends JpaRepository<UtenzaDominioEntity, Long> {

    List<UtenzaDominioEntity> findByIdUtenza(Long idUtenza);
}
