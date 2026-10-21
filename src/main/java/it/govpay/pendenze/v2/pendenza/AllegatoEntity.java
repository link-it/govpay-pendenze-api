package it.govpay.pendenze.v2.pendenza;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Allegato libero di una pendenza (tabella legacy {@code allegati}, FK su {@code versamenti}) —
 * per scelta progettuale la v3 nativa non porta avanti questo concetto (nessuna traccia in
 * govpay-common-pendenze, nessuno schema/endpoint in govpay-api-pendenze-v3.yaml): esiste solo
 * qui, scoped al wrapper v2, che deve continuare a esporlo ai client esistenti.
 *
 * <p>Minimo indispensabile per {@code Pendenza.allegati[]} (nome/tipo/descrizione + URL verso
 * {@code getAllegatoPendenza}): {@code raw_contenuto} (un OID Postgres, il contenuto binario
 * vero e proprio) e {@code data_creazione} non sono mappati qui, non servono a questo
 * elenco — vanno aggiunti se/quando si implementera' il download del singolo allegato.</p>
 *
 * <p>{@code idVersamento} referenzia {@link it.govpay.pendenze.entity.Pendenza#getId()}: la
 * migrazione modifica la tabella {@code versamenti} sul posto (stesso {@code id}), non la
 * ricrea, quindi nessuna traduzione serve tra il vecchio FK e l'id della pendenza v3.</p>
 */
@Entity
@Table(name = "allegati")
public class AllegatoEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "id_versamento", nullable = false)
    private Long idVersamento;

    @Column(name = "nome", length = 255, nullable = false)
    private String nome;

    @Column(name = "tipo", length = 255)
    private String tipo;

    @Column(name = "descrizione", length = 255)
    private String descrizione;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getIdVersamento() {
        return idVersamento;
    }

    public void setIdVersamento(Long idVersamento) {
        this.idVersamento = idVersamento;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }
}
