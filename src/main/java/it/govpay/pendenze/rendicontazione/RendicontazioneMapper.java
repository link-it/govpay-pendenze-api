package it.govpay.pendenze.rendicontazione;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import it.govpay.pendenze.api.model.FlussoRendicontazione;
import it.govpay.pendenze.api.model.Rendicontazione;
import it.govpay.pendenze.api.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.api.model.StatoRendicontazione;

/**
 * Mapping {@code it.govpay.pendenze.entity.Rendicontazione} (entity, govpay-common-pendenze)
 * → {@link Rendicontazione} (DTO OpenAPI) per
 * {@code GET /pendenze/{idA2A}/{idPendenza}/rendicontazioni}. Entity e DTO condividono gli
 * stessi nomi di classe ({@code Rendicontazione}/{@code FlussoRendicontazione}): i parametri
 * di tipo entity sono quindi referenziati con nome completamente qualificato, gli import non
 * qualificati sono riservati ai DTO (il tipo di ritorno di questa classe).
 *
 * <p>Verificato contro il legacy (non un'ipotesi):</p>
 * <ul>
 * <li>{@code entity.data} e' un {@code OffsetDateTime} (colonna DB), ma il contenuto
 * originale e' sempre una data pura — {@code dataEsitoSingoloPagamento} nell'XSD del flusso
 * di rendicontazione e' {@code xs:date}, non {@code xs:dateTime}, e il legacy la materializza
 * a mezzanotte ({@code DateUtils.toJavaDate(LocalDate)}). Troncare con {@code toLocalDate()}
 * verso il DTO ({@code type: string, format: date}) e' quindi fedele al dato originale, non
 * una perdita di informazione.</li>
 * <li>{@code entity.esito} (0/3/4/8/9) replica 1:1 {@code EsitoRendicontazione} del legacy
 * ({@code ESEGUITO/REVOCATO/ESEGUITO_STANDIN/ESEGUITO_STANDIN_SENZA_RPT/ESEGUITO_SENZA_RPT}) —
 * lo schema v3 ne copriva inizialmente solo 3 (0/3/9), corretto per includere anche 4/8.
 * Puo' essere nullo in pratica (non solo sulla carta): il legacy
 * ({@code it.govpay.core.business.Rendicontazioni#salva}) persiste comunque la riga con
 * {@code esito} nullo se pagoPA invia un codice non previsto dall'enum, invece di
 * interrompere l'elaborazione del flusso — gestito qui con un controllo esplicito, a
 * differenza di {@code importoPagato}/{@code data}/{@code flusso.numeroPagamenti} (sempre
 * assegnati incondizionatamente nel legacy, mai nulli in pratica nonostante il Javadoc
 * "nullable" dell'entity).</li>
 * <li>{@code flusso.iur} (entity) → {@code trn} (DTO): stesso rename gia' fatto dalle API v3
 * ragioneria legacy (confermato, non un'invenzione di questa sessione) — a livello di
 * singola rendicontazione invece {@code iur} resta {@code iur} (semantica diversa: CRO/IUR di
 * riscossione del singolo pagamento, non il TRN di regolamento del flusso).</li>
 * <li>{@code StatoRendicontazione}/{@code StatoFlussoRendicontazione} coincidono nome per
 * nome con gli enum legacy (DB/business layer) — nessuna conversione, solo
 * {@code valueOf(entity.name())}.</li>
 * </ul>
 */
@Component
public class RendicontazioneMapper {

    public Rendicontazione toRendicontazioneDto(it.govpay.pendenze.entity.Rendicontazione entity) {
        Rendicontazione dto = new Rendicontazione();
        dto.setIuv(entity.getIuv());
        dto.setIur(entity.getIur());
        if (entity.getIndiceDati() != null) {
            dto.setIndice(BigDecimal.valueOf(entity.getIndiceDati()));
        }
        dto.setImporto(BigDecimal.valueOf(entity.getImportoPagato()));
        if (entity.getEsito() == null) {
            throw new RendicontazioneNonLeggibileException(
                    "La rendicontazione [iuv:" + entity.getIuv() + ", iur:" + entity.getIur()
                            + "] ha esito nullo: pagoPA ha inviato un codice esito non riconosciuto.");
        }
        dto.setEsito(Rendicontazione.EsitoEnum.fromValue(BigDecimal.valueOf(entity.getEsito())));
        dto.setData(entity.getData().toLocalDate());
        dto.setStato(StatoRendicontazione.fromValue(entity.getStato().name()));
        dto.setFlusso(toFlussoRendicontazioneDto(entity.getFlusso()));
        return dto;
    }

    private static FlussoRendicontazione toFlussoRendicontazioneDto(
            it.govpay.pendenze.entity.FlussoRendicontazione entity) {
        FlussoRendicontazione dto = new FlussoRendicontazione();
        dto.setIdFlusso(entity.getCodFlusso());
        dto.setDataFlusso(entity.getDataOraFlusso());
        dto.setTrn(entity.getIur());
        dto.setDataRegolamento(entity.getDataRegolamento());
        dto.setIdPsp(entity.getCodPsp());
        dto.setBicRiversamento(entity.getBicRiversamento());
        if (entity.getNumeroPagamenti() != null) {
            dto.setNumeroPagamenti(BigDecimal.valueOf(entity.getNumeroPagamenti()));
        }
        dto.setImportoTotale(entity.getImportoTotale());
        dto.setStato(StatoFlussoRendicontazione.fromValue(entity.getStato().name()));
        return dto;
    }
}
