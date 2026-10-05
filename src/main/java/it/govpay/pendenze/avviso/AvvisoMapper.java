package it.govpay.pendenze.avviso;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.common.repository.DominioRepository;
import it.govpay.common.utils.IuvUtils;
import it.govpay.pendenze.api.model.Avviso;
import it.govpay.pendenze.api.model.StatoAvviso;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.model.StatoOpzionePagamento;
import it.govpay.pendenze.model.StatoPendenza;

/**
 * Mapper {@code Pendenza} → {@link Avviso} per la variante {@code application/json} di
 * {@code GET /pendenze/{idA2A}/{idPendenza}/stampa}. qrcode/barcode generati da
 * {@link IuvUtils} (govpay-common), stesso algoritmo pagoPA gia' portato la' da questo
 * progetto.
 */
@Component
public class AvvisoMapper {

    private final Clock clock;
    private final DominioRepository dominioRepository;

    public AvvisoMapper(Clock clock, DominioRepository dominioRepository) {
        this.clock = clock;
        this.dominioRepository = dominioRepository;
    }

    public Avviso toAvviso(Pendenza pendenza) {
        DominioEntity dominio = dominioRepository.findById(pendenza.getIdDominio())
                .orElseThrow(() -> new IllegalStateException(
                        "idDominio [" + pendenza.getIdDominio() + "] della pendenza non risolve a nessun dominio "
                                + "censito: incoerenza del dato, il vincolo di integrita' referenziale dovrebbe "
                                + "impedirlo"));

        Avviso avviso = new Avviso();
        avviso.setStato(mapStato(pendenza));
        avviso.setImporto(BigDecimal.valueOf(pendenza.getImporto()));
        avviso.setIdDominio(dominio.getCodDominio());
        avviso.setNumeroAvviso(pendenza.getNumeroAvviso());
        avviso.setDataValidita(toLocalDate(pendenza.getDataValidita()));
        avviso.setDataScadenza(resolveDataScadenza(pendenza));
        avviso.setDataPagamento(toLocalDate(pendenza.getDataPagamento()));
        avviso.setDescrizione(pendenza.getOpzionePagamento().getPosizioneDebitoria().getDescrizione());
        avviso.setQrcode(buildQrcode(pendenza, dominio));
        avviso.setBarcode(buildBarcode(pendenza, dominio));
        return avviso;
    }

    /**
     * {@code StatoPendenza} ha esattamente gli 8 valori dello storico {@code StatoVersamento}
     * (v2). Per {@code ANOMALO}/{@code ESEGUITO_SENZA_RPT}/{@code INCASSATO} non c'e' una
     * mappatura affidabile su uno stato "di stampa" specifico: ne' il converter legacy v2
     * (che li lascia indeterminati) ne' govpay-portal-api (che per questi stessi tre casi usa
     * {@code default -> SCONOSCIUTA} nella propria {@code PendenzeMapper.getStatoPendenza})
     * tentano di distinguerli ulteriormente — {@code SCONOSCIUTA} e' quindi la mappatura
     * corretta, non un ripiego: stesso valore gia' prodotto da un'altra API GovPay per lo
     * stesso identico problema, non un'invenzione locale di questo mapper.
     *
     * <p>Controllo preliminare sull'opzione di pagamento, non solo sulla pendenza: concetto
     * nuovo della v3, senza equivalente legacy (in v2 la mutua esclusione tra opzioni
     * alternative non esiste in nessuna forma, vedi Javadoc di campo su
     * {@code OpzionePagamento}). {@code PosizioneDebitoriaService#annulla} porta l'opzione a
     * {@code StatoOpzionePagamento.ANNULLATA} senza toccare {@code StatoPendenza}: senza
     * questo controllo l'avviso di una pendenza con opzione annullata resterebbe
     * {@code NON_ESEGUITA} per sempre, dato che nulla fara' mai transitare
     * {@code StatoPendenza}. Nessun valore enum dedicato in {@code StatoAvviso}: si riusa
     * {@code ANNULLATA}, semanticamente corretto anche qui (l'avviso non e' piu' pagabile
     * tramite questa opzione).</p>
     */
    private StatoAvviso mapStato(Pendenza pendenza) {
        if (pendenza.getOpzionePagamento().getStato() == StatoOpzionePagamento.ANNULLATA) {
            return StatoAvviso.ANNULLATA;
        }
        StatoPendenza stato = pendenza.getStato();
        return switch (stato) {
            case ANNULLATO -> StatoAvviso.ANNULLATA;
            case ESEGUITO, ESEGUITO_ALTRO_CANALE, PARZIALMENTE_ESEGUITO -> StatoAvviso.DUPLICATA;
            case NON_ESEGUITO -> isScaduta(pendenza) ? StatoAvviso.SCADUTA : StatoAvviso.NON_ESEGUITA;
            case ANOMALO, ESEGUITO_SENZA_RPT, INCASSATO -> StatoAvviso.SCONOSCIUTA;
        };
    }

    private boolean isScaduta(Pendenza pendenza) {
        LocalDate scadenza = resolveDataScadenza(pendenza);
        return scadenza != null && scadenza.isBefore(LocalDate.now(clock));
    }

    /**
     * Se assente sulla pendenza, si usa la scadenza dell'opzione di pagamento (semantica dello
     * YAML v3). Non-private: riusata da {@link AvvisoPdfPayloadMapper} per la {@code due_date}
     * del PDF, stessa regola di fallback.
     */
    static LocalDate resolveDataScadenza(Pendenza pendenza) {
        if (pendenza.getDataScadenzaAvviso() != null) {
            return pendenza.getDataScadenzaAvviso().toLocalDate();
        }
        return pendenza.getOpzionePagamento().getDataScadenza();
    }

    private static LocalDate toLocalDate(OffsetDateTime dateTime) {
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    /** Non-private: riusata da {@link AvvisoPdfPayloadMapper} per il qrcode del PDF. */
    static String buildQrcode(Pendenza pendenza, DominioEntity dominio) {
        return IuvUtils.buildQrCode002(dominio.getCodDominio(), dominio.getAuxDigit(), applicationCodeDi(dominio),
                pendenza.getIuv(), BigDecimal.valueOf(pendenza.getImporto()), pendenza.getNumeroAvviso());
    }

    private static String buildBarcode(Pendenza pendenza, DominioEntity dominio) {
        return IuvUtils.buildBarCode(dominio.getGln(), dominio.getAuxDigit(), applicationCodeDi(dominio),
                pendenza.getIuv(), BigDecimal.valueOf(pendenza.getImporto()), pendenza.getNumeroAvviso());
    }

    private static Integer applicationCodeDi(DominioEntity dominio) {
        StazioneEntity stazione = dominio.getStazione();
        return stazione != null ? stazione.getApplicationCode() : null;
    }
}
