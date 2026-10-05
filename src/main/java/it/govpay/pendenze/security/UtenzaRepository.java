package it.govpay.pendenze.security;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UtenzaRepository extends JpaRepository<UtenzaEntity, Long> {

    Optional<UtenzaEntity> findByPrincipal(String principal);
}
