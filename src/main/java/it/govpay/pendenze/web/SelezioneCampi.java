package it.govpay.pendenze.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Applica il parametro di query {@code fields} (elenco separato da virgole dei campi da
 * restituire per ciascun risultato — RAC_REST_NAME dello YAML v3) agli elementi di una lista
 * di risultati. Estratta come utility condivisa dopo essere stata scritta una prima volta
 * solo per {@code PosizioneDebitoriaController} (bug del lead, 2026-09-27: il parametro era
 * dichiarato dallo YAML ma completamente ignorato) e poi riservita identica da
 * {@code PendenzaController}.
 */
public final class SelezioneCampi {

    private SelezioneCampi() {
    }

    /**
     * L'interfaccia generata tipizza {@code risultati} col DTO concreto (es.
     * {@code List<PosizioneDebitoriaIndex>}): non possiamo restituire una lista di {@code Map}
     * con quella firma. Jackson pero' serializza ogni elemento di una collezione in base alla
     * sua classe <b>reale</b> a runtime, non al tipo generico dichiarato — un
     * {@code LinkedHashMap} qui dentro produce comunque, in uscita, esattamente il JSON
     * parziale voluto. Il cast e' quindi sicuro nonostante l'avviso "unchecked": nulla nel
     * codice tenta piu' di leggere questi elementi come veri {@code T} dopo questo punto.
     *
     * @param risultati    elementi da ridurre, tipizzati T (un DTO generato dallo YAML)
     * @param fields       elenco separato da virgole dei campi da mantenere, o
     *                     {@code null}/vuoto per non applicare alcuna riduzione
     * @param objectMapper usato solo per la conversione DTO-&gt;Map, non per la
     *                     (de)serializzazione HTTP della risposta
     * @return {@code risultati} invariata se {@code fields} e' assente, altrimenti una lista
     *         di mappe (una per elemento) con le sole chiavi richieste presenti nel DTO
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> applica(List<T> risultati, String fields, ObjectMapper objectMapper) {
        if (fields == null || fields.isBlank()) {
            return risultati;
        }
        Set<String> campiRichiesti = Arrays.stream(fields.split(","))
                .map(String::trim)
                .filter(campo -> !campo.isEmpty())
                .collect(Collectors.toSet());

        List<Object> ridotti = new ArrayList<>();
        for (T elemento : risultati) {
            Map<String, Object> completo = objectMapper.convertValue(elemento,
                    new TypeReference<Map<String, Object>>() {
                    });
            Map<String, Object> ridotto = new LinkedHashMap<>();
            for (String campo : campiRichiesti) {
                if (completo.containsKey(campo)) {
                    ridotto.put(campo, completo.get(campo));
                }
            }
            ridotti.add(ridotto);
        }
        return (List<T>) (List<?>) ridotti;
    }
}
