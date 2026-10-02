package it.govpay.pendenze.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import it.govpay.pendenze.api.model.RicevutaIndex;
import it.govpay.pendenze.api.model.TipoRicevuta;
import it.govpay.pendenze.repository.RicevutaElenco;

class RicevutaMapperTest {

    private final RicevutaMapper mapper = new RicevutaMapper();

    private static RicevutaElenco ricevuta(String iur, String versione, OffsetDateTime data) {
        return new RicevutaElenco() {
            @Override
            public Long getId() {
                return 1L;
            }

            @Override
            public String getIur() {
                return iur;
            }

            @Override
            public String getVersione() {
                return versione;
            }

            @Override
            public OffsetDateTime getDataMsgRicevuta() {
                return data;
            }
        };
    }

    @Test
    void toRicevutaIndexDtoMappaIurEData() {
        OffsetDateTime data = OffsetDateTime.now();
        RicevutaIndex dto = mapper.toRicevutaIndexDto(ricevuta("iur-1", "SANP_240", data));

        assertThat(dto.getIur()).isEqualTo("iur-1");
        assertThat(dto.getData()).isEqualTo(data);
    }

    // --- mapVersioneATipo: replica il dispatch legacy di MessaggiPagoPARtUtils.getMessaggioRT ---

    @Test
    void sanp230MappaSuCtRicevutaTelematica() {
        assertThat(RicevutaMapper.mapVersioneATipo("SANP_230")).isEqualTo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
    }

    @Test
    void sanp240MappaSuCtReceipt() {
        assertThat(RicevutaMapper.mapVersioneATipo("SANP_240")).isEqualTo(TipoRicevuta.CT_RECEIPT);
    }

    @Test
    void rptv2Rtv1MappaSuCtReceipt() {
        assertThat(RicevutaMapper.mapVersioneATipo("RPTV2_RTV1")).isEqualTo(TipoRicevuta.CT_RECEIPT);
    }

    @Test
    void sanp321V2MappaSuCtReceiptV2() {
        assertThat(RicevutaMapper.mapVersioneATipo("SANP_321_V2")).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);
    }

    @Test
    void rptv1Rtv2MappaSuCtReceiptV2() {
        assertThat(RicevutaMapper.mapVersioneATipo("RPTV1_RTV2")).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);
    }

    @Test
    void rptsanp230Rtv2MappaSuCtReceiptV2() {
        assertThat(RicevutaMapper.mapVersioneATipo("RPTSANP230_RTV2")).isEqualTo(TipoRicevuta.CT_RECEIPT_V2);
    }

    /** Stesso fallback di {@code VersioneRPT.toEnum} nel legacy: valore ignoto -> SANP_230. */
    @Test
    void versioneIgnotaMappaSuCtRicevutaTelematica() {
        assertThat(RicevutaMapper.mapVersioneATipo("VALORE_INESISTENTE"))
                .isEqualTo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
    }

    @Test
    void versioneNullaMappaSuCtRicevutaTelematica() {
        assertThat(RicevutaMapper.mapVersioneATipo(null)).isEqualTo(TipoRicevuta.CT_RICEVUTA_TELEMATICA);
    }
}
