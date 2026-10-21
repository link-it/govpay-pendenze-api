package it.govpay.pendenze.v2.ricevuta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.jakarta.xmlbind.JakartaXmlBindAnnotationModule;

import org.springframework.stereotype.Component;

/**
 * Serializza un bean JAXB (RPT/RT) leggendo le sue annotazioni {@code @XmlElement} per i nomi
 * dei campi — stesso meccanismo del legacy ({@code ConverterUtils}, monolite): i bean JAXB non
 * hanno getter/setter pensati per Jackson "nudo" (es. {@code isLastPayment()} per un campo
 * {@code Boolean}), quindi senza questo modulo la serializzazione userebbe i nomi/la presenza
 * sbagliati.
 *
 * <p><b>Restituisce una struttura generica di {@code Map}/{@code List}/valori semplici, non un
 * {@code JsonNode}</b>: questo modulo e {@code jackson-databind} classico (Jackson 2) arrivano
 * dalla BOM di Spring Boot solo per compatibilita', ma il mapper applicativo (quello che
 * serializza davvero la risposta HTTP) e' Jackson 3 ({@code tools.jackson}) — verificato che
 * un {@code com.fasterxml.jackson.databind.JsonNode} passato a quel mapper non viene
 * riconosciuto come albero e finisce serializzato come un bean Java qualunque (le sue stesse
 * proprieta' interne, {@code nodeType}/{@code containerNode}/{@code array}, invece del
 * contenuto). Una struttura di {@code LinkedHashMap}/{@code ArrayList}/{@code String}/
 * {@code Number}/{@code Boolean}/{@code null} invece la scrive correttamente qualunque sia la
 * versione di Jackson che la riceve — nessun modulo speciale richiesto per quel passo.</p>
 */
@Component
public class RicevutaJsonConverter {

    private final ObjectMapper mapper;

    public RicevutaJsonConverter() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JakartaXmlBindAnnotationModule());
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * @param bean bean JAXB da convertire, oppure {@code null}
     * @return l'equivalente come {@code Map}/{@code List}/valori semplici (round-trip
     *         serializza-poi-deserializza su {@code Object.class}, lo stesso idioma usato per
     *         "appiattire" un bean in una struttura generica), {@code null} se {@code bean}
     *         e' {@code null}
     */
    public Object toAlberoGenerico(Object bean) {
        return bean == null ? null : mapper.convertValue(bean, Object.class);
    }
}
