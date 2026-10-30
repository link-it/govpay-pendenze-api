package it.govpay.pendenze.v2.ricevuta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.v2.api.model.PendenzaIndex;
import it.govpay.pendenze.v2.pendenza.PendenzaV2Mapper;

class RppMapperTest {

    private final RppContenutoMapper contenutoMapper = mock(RppContenutoMapper.class);
    private final PendenzaV2Mapper pendenzaV2Mapper = mock(PendenzaV2Mapper.class);
    private final RppMapper mapper = new RppMapper(contenutoMapper, pendenzaV2Mapper);

    @Test
    @DisplayName("toRppIndex: stato/dettaglioStato da Rpt, rpt/rt/pendenza dai rispettivi mapper")
    void toRppIndexAssemblaTuttiICampi() {
        Rpt rpt = new Rpt();
        rpt.setStato("RT_ACCETTATA_PA");
        rpt.setDescrizioneStato("Pagamento eseguito con successo");
        rpt.setXmlRt(new byte[] { 1 });
        Pendenza pendenza = new Pendenza();

        var rptJson = new ObjectMapper().valueToTree("rpt-contenuto");
        var rtJson = new ObjectMapper().valueToTree("rt-contenuto");
        PendenzaIndex pendenzaIndexDto = new PendenzaIndex();
        when(contenutoMapper.toRpt(rpt, pendenza)).thenReturn(rptJson);
        when(contenutoMapper.toRt(rpt, pendenza)).thenReturn(rtJson);
        when(pendenzaV2Mapper.toIndexDto(pendenza)).thenReturn(pendenzaIndexDto);

        var dto = mapper.toRppIndex(rpt, pendenza);

        assertThat(dto.getStato()).isEqualTo("RT_ACCETTATA_PA");
        assertThat(dto.getDettaglioStato()).isEqualTo("Pagamento eseguito con successo");
        assertThat(dto.getSegnalazioni()).isEmpty();
        assertThat(dto.getRpt()).isEqualTo(rptJson);
        assertThat(dto.getRt()).isEqualTo(rtJson);
        assertThat(dto.getPendenza()).isSameAs(pendenzaIndexDto);
    }

    @Test
    @DisplayName("toRpp: rt assente (xml_rt non ancora acquisita) -> null, nessuna chiamata al mapper di dettaglio")
    void toRppSenzaRtAcquisitaRestituisceNull() {
        Rpt rpt = new Rpt();
        rpt.setStato("RPT_ACCETTATA_PA");
        Pendenza pendenza = new Pendenza();
        when(contenutoMapper.toRpt(rpt, pendenza)).thenReturn(TextNode.valueOf("rpt"));
        when(pendenzaV2Mapper.toIndexDto(pendenza)).thenReturn(new PendenzaIndex());

        var dto = mapper.toRpp(rpt, pendenza);

        assertThat(dto.getRt()).isNull();
        verify(contenutoMapper).toRpt(rpt, pendenza);
        verify(contenutoMapper, never()).toRt(rpt, pendenza);
    }
}
