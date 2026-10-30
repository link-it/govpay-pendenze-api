package it.govpay.pendenze.v2.pendenza;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Allegato libero di una pendenza (tabella legacy {@code allegati}, FK su {@code versamenti}) —
 * per scelta progettuale la v3 nativa non porta avanti questo concetto (nessuna traccia in
 * govpay-common-pendenze, nessuno schema/endpoint in govpay-api-pendenze-v3.yaml): esiste solo
 * qui, scoped al wrapper v2, che deve continuare a esporlo ai client esistenti.
 *
 * <p>Nome/tipo/descrizione/dataCreazione sono scrivibili (per {@code addPendenza}, che crea
 * nuove righe) oltre che leggibili (per {@code Pendenza.allegati[]}/
 * {@code getAllegatoPendenza}). {@code id} e' generato dalla sequenza legacy
 * {@code seq_allegati} (stessa convenzione gia' presente nello schema, {@code DEFAULT
 * nextval('seq_allegati')}: qui esplicitata come {@code @GeneratedValue} perche' questa
 * classe adesso inserisce righe, non solo le legge).</p>
 *
 * <p><b>{@code raw_contenuto} resta deliberatamente non mappato</b> (gap noto, non
 * un'omissione): in produzione (PostgreSQL) e' un {@code OID} — un Large Object, non un
 * array di byte ordinario — che richiede l'API dedicata
 * {@code org.postgresql.largeobject.LargeObjectManager} sulla connessione JDBC sottostante,
 * non un semplice {@code @Lob byte[]} di Hibernate. Lo schema di test H2
 * ({@code schema-pendenze-test.sql}) approssima la colonna come {@code BYTEA} solo per
 * compatibilita' DDL, non perche' sia la stessa semantica: un {@code byte[]} che validasse
 * contro H2 non funzionerebbe necessariamente nello stesso modo contro il vero {@code OID} di
 * PostgreSQL. {@code NuovaPendenza.allegati[].contenuto} (il base64 in ingresso) viene quindi
 * attualmente scartato: {@code addPendenza} persiste solo i metadati dell'allegato (nome,
 * tipo, descrizione), non il contenuto binario — vedi {@code NuovaPendenzaMapper} e il punto
 * aperto corrispondente nella documentazione di progetto.</p>
 *
 * <p>{@code idVersamento} referenzia {@link it.govpay.pendenze.entity.Pendenza#getId()}: la
 * migrazione modifica la tabella {@code versamenti} sul posto (stesso {@code id}), non la
 * ricrea, quindi nessuna traduzione serve tra il vecchio FK e l'id della pendenza v3.</p>
 */
@Entity
@Table(name = "allegati")
public class AllegatoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "allegati_seq_gen")
    @SequenceGenerator(name = "allegati_seq_gen", sequenceName = "seq_allegati", allocationSize = 1)
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

    @Column(name = "data_creazione", nullable = false)
    private OffsetDateTime dataCreazione;

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

    public OffsetDateTime getDataCreazione() {
        return dataCreazione;
    }

    public void setDataCreazione(OffsetDateTime dataCreazione) {
        this.dataCreazione = dataCreazione;
    }
}
