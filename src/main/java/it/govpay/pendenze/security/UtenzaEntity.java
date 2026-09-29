package it.govpay.pendenze.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Mappa la tabella condivisa {@code utenze} (credenziali Basic Auth di un'applicazione,
 * risolte da {@link PendenzeGovpayPrincipalLoader}) — copia locale a questo servizio, stesso
 * schema di {@code it.govpay.console.entity.Utenza} in govpay-console-api: ogni consumer
 * mappa la propria vista sulla tabella condivisa, non esiste un'entity comune in
 * govpay-common per questo (e' un concetto di infrastruttura di sicurezza, non di anagrafica
 * di dominio).
 */
@Entity
@Table(name = "utenze")
@SequenceGenerator(name = "seq_utenze", sequenceName = "seq_utenze", allocationSize = 1)
public class UtenzaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_utenze")
    @Column(name = "id")
    private Long id;

    @Column(name = "principal", nullable = false, length = 4000)
    private String principal;

    @Column(name = "principal_originale", nullable = false, length = 4000)
    private String principalOriginale;

    @Column(name = "abilitato", nullable = false)
    private Boolean abilitato;

    @Column(name = "ruoli", length = 512)
    private String ruoli;

    @Column(name = "password", length = 255)
    private String password;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public String getPrincipalOriginale() {
        return principalOriginale;
    }

    public void setPrincipalOriginale(String principalOriginale) {
        this.principalOriginale = principalOriginale;
    }

    public Boolean getAbilitato() {
        return abilitato;
    }

    public void setAbilitato(Boolean abilitato) {
        this.abilitato = abilitato;
    }

    public String getRuoli() {
        return ruoli;
    }

    public void setRuoli(String ruoli) {
        this.ruoli = ruoli;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
