package it.govpay.pendenze.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Mappa la tabella condivisa {@code acl} (diritti LETTURA/SCRITTURA per servizio di
 * un'utenza, o di un ruolo se {@code idUtenza} e' null — vedi Javadoc di {@link AclAuthorizer})
 * — stesso schema di {@code it.govpay.console.entity.Acl} in govpay-console-api.
 */
@Entity
@Table(name = "acl")
@SequenceGenerator(name = "seq_acl", sequenceName = "seq_acl", allocationSize = 1)
public class AclEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_acl")
    @Column(name = "id")
    private Long id;

    @Column(name = "ruolo", length = 255)
    private String ruolo;

    @Column(name = "servizio", nullable = false, length = 255)
    private String servizio;

    @Column(name = "diritti", nullable = false, length = 255)
    private String diritti;

    @Column(name = "id_utenza")
    private Long idUtenza;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuolo() {
        return ruolo;
    }

    public void setRuolo(String ruolo) {
        this.ruolo = ruolo;
    }

    public String getServizio() {
        return servizio;
    }

    public void setServizio(String servizio) {
        this.servizio = servizio;
    }

    public String getDiritti() {
        return diritti;
    }

    public void setDiritti(String diritti) {
        this.diritti = diritti;
    }

    public Long getIdUtenza() {
        return idUtenza;
    }

    public void setIdUtenza(Long idUtenza) {
        this.idUtenza = idUtenza;
    }
}
