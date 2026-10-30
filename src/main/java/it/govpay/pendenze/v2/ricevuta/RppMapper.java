package it.govpay.pendenze.v2.ricevuta;

import java.util.ArrayList;

import org.springframework.stereotype.Component;

import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.ricevuta.RicevutaNonLeggibileException;
import it.govpay.pendenze.v2.api.model.Rpp;
import it.govpay.pendenze.v2.api.model.RppIndex;
import it.govpay.pendenze.v2.pendenza.PendenzaV2Mapper;

/**
 * Assembla {@code Rpp}/{@code RppIndex} ({@code stato}+{@code dettaglioStato}+{@code rpt}+
 * {@code rt}+{@code pendenza}, dallo YAML v2) — porting di
 * {@code RptConverter.toRsModel}/{@code toRsModelIndex} del wrapper legacy, adattato a
 * {@link Rpt}/{@link Pendenza}. {@code segnalazioni} resta sempre vuoto: anche il legacy non
 * lo popola mai a questo livello (solo {@code Pendenza.segnalazioni}, da {@code anomalie}).
 */
@Component
public class RppMapper {

    private final RppContenutoMapper contenutoMapper;
    private final PendenzaV2Mapper pendenzaV2Mapper;

    public RppMapper(RppContenutoMapper contenutoMapper, PendenzaV2Mapper pendenzaV2Mapper) {
        this.contenutoMapper = contenutoMapper;
        this.pendenzaV2Mapper = pendenzaV2Mapper;
    }

    /** @throws RicevutaNonLeggibileException se rpt/rt (reali o ricostruiti) non sono interpretabili */
    public Rpp toRpp(Rpt rpt, Pendenza pendenza) {
        Rpp dto = new Rpp();
        dto.setStato(rpt.getStato());
        dto.setDettaglioStato(rpt.getDescrizioneStato());
        dto.setSegnalazioni(new ArrayList<>());
        dto.setRpt(contenutoMapper.toRpt(rpt, pendenza));
        dto.setRt(leggiRt(rpt, pendenza));
        dto.setPendenza(pendenzaV2Mapper.toIndexDto(pendenza));
        return dto;
    }

    /** @throws RicevutaNonLeggibileException se rpt/rt (reali o ricostruiti) non sono interpretabili */
    public RppIndex toRppIndex(Rpt rpt, Pendenza pendenza) {
        RppIndex dto = new RppIndex();
        dto.setStato(rpt.getStato());
        dto.setDettaglioStato(rpt.getDescrizioneStato());
        dto.setSegnalazioni(new ArrayList<>());
        dto.setRpt(contenutoMapper.toRpt(rpt, pendenza));
        dto.setRt(leggiRt(rpt, pendenza));
        dto.setPendenza(pendenzaV2Mapper.toIndexDto(pendenza));
        return dto;
    }

    /**
     * {@code rt} e' {@code NOT_REQUIRED} nello schema (a differenza di {@code rpt}, sempre
     * presente): {@code null} se la RT non e' ancora stata acquisita, invece di propagare
     * l'eccezione di {@link RppContenutoMapper#toRt} (che distingue "RT assente" da "RT
     * presente ma illeggibile" — solo il secondo caso e' un errore reale anche qui).
     */
    private Object leggiRt(Rpt rpt, Pendenza pendenza) {
        if (rpt.getXmlRt() == null) {
            return null;
        }
        return contenutoMapper.toRt(rpt, pendenza);
    }
}
