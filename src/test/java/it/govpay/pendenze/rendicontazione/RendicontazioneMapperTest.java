package it.govpay.pendenze.rendicontazione;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import it.govpay.pendenze.api.model.Rendicontazione;
import it.govpay.pendenze.api.model.StatoFlussoRendicontazione;
import it.govpay.pendenze.api.model.StatoRendicontazione;

class RendicontazioneMapperTest {

    private final RendicontazioneMapper mapper = new RendicontazioneMapper();

    private static it.govpay.pendenze.entity.FlussoRendicontazione flusso() {
        it.govpay.pendenze.entity.FlussoRendicontazione flusso = new it.govpay.pendenze.entity.FlussoRendicontazione();
        flusso.setCodFlusso("2026-10-03Psp1-10:27:27.903");
        flusso.setDataOraFlusso(OffsetDateTime.parse("2026-10-03T10:27:27.903+02:00"));
        flusso.setIur("TRN-123456");
        flusso.setDataRegolamento(OffsetDateTime.parse("2026-10-03T10:27:27.903+02:00"));
        flusso.setCodPsp("ABI-12345");
        flusso.setBicRiversamento("BIC-12345");
        flusso.setNumeroPagamenti(3L);
        flusso.setImportoTotale(100.01);
        flusso.setStato(it.govpay.pendenze.model.StatoFlussoRendicontazione.ACCETTATA);
        return flusso;
    }

    private static it.govpay.pendenze.entity.Rendicontazione rendicontazione() {
        it.govpay.pendenze.entity.Rendicontazione entity = new it.govpay.pendenze.entity.Rendicontazione();
        entity.setId(42L);
        entity.setIuv("01000000202012345");
        entity.setIur("1234acdc");
        entity.setIndiceDati(1);
        entity.setImportoPagato(10.01);
        entity.setEsito(0);
        entity.setData(OffsetDateTime.parse("2026-10-03T00:00:00Z"));
        entity.setStato(it.govpay.pendenze.model.StatoRendicontazione.OK);
        entity.setFlusso(flusso());
        return entity;
    }

    @Test
    void mappaCampiDellaRendicontazione() {
        Rendicontazione dto = mapper.toRendicontazioneDto(rendicontazione());

        assertThat(dto.getIuv()).isEqualTo("01000000202012345");
        assertThat(dto.getIur()).isEqualTo("1234acdc");
        assertThat(dto.getIndice()).isEqualTo(BigDecimal.valueOf(1));
        assertThat(dto.getImporto()).isEqualTo(BigDecimal.valueOf(10.01));
        assertThat(dto.getEsito()).isEqualTo(Rendicontazione.EsitoEnum.NUMBER_0);
        assertThat(dto.getData()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(dto.getStato()).isEqualTo(StatoRendicontazione.OK);
    }

    /** {@code esito} copre anche i due codici "standin" (4/8), non solo 0/3/9 — vedi Javadoc del mapper. */
    @Test
    void esitoStandinMappaCorrettamente() {
        it.govpay.pendenze.entity.Rendicontazione entity = rendicontazione();
        entity.setEsito(4);

        assertThat(mapper.toRendicontazioneDto(entity).getEsito()).isEqualTo(Rendicontazione.EsitoEnum.NUMBER_4);
    }

    @Test
    void esitoStandinSenzaRptMappaCorrettamente() {
        it.govpay.pendenze.entity.Rendicontazione entity = rendicontazione();
        entity.setEsito(8);

        assertThat(mapper.toRendicontazioneDto(entity).getEsito()).isEqualTo(Rendicontazione.EsitoEnum.NUMBER_8);
    }

    @Test
    void indiceAssenteSeNonValorizzatoSullEntity() {
        it.govpay.pendenze.entity.Rendicontazione entity = rendicontazione();
        entity.setIndiceDati(null);

        assertThat(mapper.toRendicontazioneDto(entity).getIndice()).isNull();
    }

    /** {@code data}: troncamento a LocalDate fedele all'XSD originale (xs:date) — vedi Javadoc del mapper. */
    @Test
    void dataTroncataALocalDateIndipendentementeDallOraMemorizzata() {
        it.govpay.pendenze.entity.Rendicontazione entity = rendicontazione();
        entity.setData(OffsetDateTime.parse("2026-10-03T23:59:59+02:00"));

        assertThat(mapper.toRendicontazioneDto(entity).getData()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void mappaIlFlussoDiRendicontazioneConRenameIurSuTrn() {
        Rendicontazione dto = mapper.toRendicontazioneDto(rendicontazione());

        assertThat(dto.getFlusso().getIdFlusso()).isEqualTo("2026-10-03Psp1-10:27:27.903");
        assertThat(dto.getFlusso().getDataFlusso()).isEqualTo(OffsetDateTime.parse("2026-10-03T10:27:27.903+02:00"));
        // flusso.iur (entity) -> trn (DTO): rename verificato contro il legacy, non un'invenzione.
        assertThat(dto.getFlusso().getTrn()).isEqualTo("TRN-123456");
        assertThat(dto.getFlusso().getDataRegolamento())
                .isEqualTo(OffsetDateTime.parse("2026-10-03T10:27:27.903+02:00"));
        assertThat(dto.getFlusso().getIdPsp()).isEqualTo("ABI-12345");
        assertThat(dto.getFlusso().getBicRiversamento()).isEqualTo("BIC-12345");
        assertThat(dto.getFlusso().getNumeroPagamenti()).isEqualTo(BigDecimal.valueOf(3));
        assertThat(dto.getFlusso().getImportoTotale()).isEqualTo(100.01);
        assertThat(dto.getFlusso().getStato()).isEqualTo(StatoFlussoRendicontazione.ACCETTATA);
    }

    /** {@code esito} nullo e' un caso reale (codice pagoPA non riconosciuto), non un'ipotesi — vedi Javadoc del mapper. */
    @Test
    void esitoNulloSollevaEccezioneLeggibile() {
        it.govpay.pendenze.entity.Rendicontazione entity = rendicontazione();
        entity.setEsito(null);

        assertThatThrownBy(() -> mapper.toRendicontazioneDto(entity))
                .isInstanceOf(RendicontazioneNonLeggibileException.class)
                .hasMessageContaining(entity.getIuv())
                .hasMessageContaining(entity.getIur());
    }

    @Test
    void numeroPagamentiAssenteSeNonValorizzatoSullEntity() {
        it.govpay.pendenze.entity.Rendicontazione entity = rendicontazione();
        entity.getFlusso().setNumeroPagamenti(null);

        assertThat(mapper.toRendicontazioneDto(entity).getFlusso().getNumeroPagamenti()).isNull();
    }
}
