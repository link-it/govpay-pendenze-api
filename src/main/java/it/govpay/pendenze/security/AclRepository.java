package it.govpay.pendenze.security;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AclRepository extends JpaRepository<AclEntity, Long> {

    List<AclEntity> findByIdUtenza(Long idUtenza);

    /** Righe ACL di definizione di un ruolo (id_utenza IS NULL) — vedi Javadoc di {@link AclAuthorizer}. */
    List<AclEntity> findByRuoloAndIdUtenzaIsNull(String ruolo);
}
