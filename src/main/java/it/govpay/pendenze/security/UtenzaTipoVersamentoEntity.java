package it.govpay.pendenze.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Mappa la tabella condivisa {@code utenze_tipo_vers} (autorizzazione esplicita di
 * un'utenza a un tipo versamento, quando {@code utenze.autorizzazione_tipi_vers_star} e'
 * false — vedi Javadoc di {@link AutorizzazioneTipoVersamentoVerifier}). FK piatte (M4): sia
 * {@code idUtenza} (locale, {@link UtenzaEntity}) sia {@code idTipoVersamento} (govpay-common,
 * {@code TipoVersamentoEntity}) sono {@code Long}, nessuna relazione JPA.
 */
@Entity
@Table(name = "utenze_tipo_vers")
@SequenceGenerator(name = "seq_utenze_tipo_vers", sequenceName = "seq_utenze_tipo_vers", allocationSize = 1)
public class UtenzaTipoVersamentoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_utenze_tipo_vers")
    @Column(name = "id")
    private Long id;

    @Column(name = "id_utenza", nullable = false)
    private Long idUtenza;

    @Column(name = "id_tipo_versamento", nullable = false)
    private Long idTipoVersamento;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdUtenza() {
        return idUtenza;
    }

    public void setIdUtenza(Long idUtenza) {
        this.idUtenza = idUtenza;
    }

    public Long getIdTipoVersamento() {
        return idTipoVersamento;
    }

    public void setIdTipoVersamento(Long idTipoVersamento) {
        this.idTipoVersamento = idTipoVersamento;
    }
}
