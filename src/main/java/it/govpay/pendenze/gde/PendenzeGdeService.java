package it.govpay.pendenze.gde;

import java.math.BigDecimal;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import it.govpay.common.configurazione.model.GdeInterfaccia;
import it.govpay.common.configurazione.model.Giornale;
import it.govpay.common.configurazione.service.ConfigurazioneService;
import it.govpay.common.gde.AbstractGdeService;
import it.govpay.common.gde.GdeEventInfo;
import it.govpay.gde.client.beans.ComponenteEvento;
import it.govpay.gde.client.beans.DettaglioRichiesta;
import it.govpay.gde.client.beans.DettaglioRisposta;
import it.govpay.gde.client.beans.NuovoEvento;

/**
 * Invio al GDE degli eventi generati dalle richieste API di questo servizio
 * (componente {@link ComponenteEvento#API_PENDENZE}). Costruito da
 * {@link GdeEventFilter} per ogni richiesta.
 */
@Service
public class PendenzeGdeService extends AbstractGdeService {

    private final ConfigurazioneService configurazioneService;

    public PendenzeGdeService(ObjectMapper objectMapper,
                              @Qualifier("gdeExecutor") Executor gdeExecutor,
                              ConfigurazioneService configurazioneService) {
        super(objectMapper, gdeExecutor, configurazioneService);
        this.configurazioneService = configurazioneService;
    }

    @Override
    protected String getGdeEndpoint() {
        return configurazioneService.getServizioGDE().getUrl() + "/eventi";
    }

    @Override
    protected GdeInterfaccia getConfigurazioneComponente(ComponenteEvento componente, Giornale giornale) {
        if (componente == null || giornale == null) {
            return null;
        }
        return switch (componente) {
            case API_PENDENZE -> giornale.getApiPendenze();
            default -> null;
        };
    }

    @Override
    protected NuovoEvento convertToGdeEvent(GdeEventInfo eventInfo) {
        NuovoEvento evento = new NuovoEvento();
        evento.setComponente(eventInfo.getComponente());
        evento.setCategoriaEvento(eventInfo.getCategoriaEvento());
        evento.setRuolo(eventInfo.getRuolo());
        evento.setTipoEvento(eventInfo.getTipoEvento());
        evento.setSottotipoEvento(eventInfo.getSottotipoEvento());
        evento.setDataEvento(eventInfo.getDataEvento());
        evento.setDurataEvento(eventInfo.getDurataEvento());
        evento.setEsito(eventInfo.getEsito());
        evento.setDettaglioEsito(eventInfo.getDescrizioneEsito());
        evento.setIdDominio(eventInfo.getIdDominio());
        evento.setClusterId(eventInfo.getClusterId());
        evento.setTransactionId(eventInfo.getTransactionId());
        if (eventInfo.getStatusCodeRisposta() != null) {
            evento.setSottotipoEsito(String.valueOf(eventInfo.getStatusCodeRisposta()));
        }

        DettaglioRichiesta dettaglioRichiesta = new DettaglioRichiesta();
        dettaglioRichiesta.setPrincipal(eventInfo.getPrincipal());
        dettaglioRichiesta.setUtente(eventInfo.getUtente());
        dettaglioRichiesta.setDataOraRichiesta(eventInfo.getDataEvento());
        dettaglioRichiesta.setUrl(eventInfo.getUrlRichiesta());
        dettaglioRichiesta.setMethod(eventInfo.getMetodoHttp());
        dettaglioRichiesta.setHeaders(eventInfo.getHeadersRichiesta());
        dettaglioRichiesta.setPayload(eventInfo.getPayloadRichiesta());
        evento.setParametriRichiesta(dettaglioRichiesta);

        DettaglioRisposta dettaglioRisposta = new DettaglioRisposta();
        if (eventInfo.getDataEvento() != null && eventInfo.getDurataEvento() != null) {
            dettaglioRisposta.setDataOraRisposta(
                    eventInfo.getDataEvento().plusNanos(eventInfo.getDurataEvento() * 1_000_000));
        }
        if (eventInfo.getStatusCodeRisposta() != null) {
            dettaglioRisposta.setStatus(BigDecimal.valueOf(eventInfo.getStatusCodeRisposta()));
        }
        dettaglioRisposta.setHeaders(eventInfo.getHeadersRisposta());
        dettaglioRisposta.setPayload(eventInfo.getPayloadRisposta());
        evento.setParametriRisposta(dettaglioRisposta);

        return evento;
    }
}
