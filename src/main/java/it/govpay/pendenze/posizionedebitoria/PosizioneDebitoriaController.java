package it.govpay.pendenze.posizionedebitoria;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import it.govpay.pendenze.api.model.NuovaPosizioneDebitoria;
import it.govpay.pendenze.api.model.PosizioneDebitoriaIndex;
import it.govpay.pendenze.api.model.PosizioniDebitorie;
import it.govpay.pendenze.api.rest.PosizioniDebitorieApi;
import it.govpay.pendenze.criteri.CriteriOrdinamento;
import it.govpay.pendenze.criteri.OffsetPageRequest;
import it.govpay.pendenze.criteri.PaginaRisultati;
import it.govpay.pendenze.exception.RisorsaNonTrovataException;
import it.govpay.pendenze.exception.ValidazioneNonSuperataException;
import it.govpay.pendenze.service.PosizioneDebitoriaService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Implementa {@link PosizioniDebitorieApi}: per ora {@link #addPosizioneDebitoria},
 * {@link #getPosizioneDebitoria} e {@link #findPosizioniDebitorie}, le altre operazioni
 * restano sul default generato (501, vedi Javadoc dell'interfaccia) fino al loro sviluppo.
 */
@RestController
public class PosizioneDebitoriaController implements PosizioniDebitorieApi {

    private final PosizioneDebitoriaMapper mapper;
    private final PosizioneDebitoriaService posizioneDebitoriaService;
    private final ObjectMapper objectMapper;

    public PosizioneDebitoriaController(PosizioneDebitoriaMapper mapper,
            PosizioneDebitoriaService posizioneDebitoriaService, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.posizioneDebitoriaService = posizioneDebitoriaService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> addPosizioneDebitoria(String idA2A,
            NuovaPosizioneDebitoria nuovaPosizioneDebitoria) {
        if (nuovaPosizioneDebitoria == null) {
            throw new ValidazioneNonSuperataException("body della richiesta mancante");
        }

        it.govpay.pendenze.entity.PosizioneDebitoria posizione = mapper.toEntity(idA2A, nuovaPosizioneDebitoria);
        it.govpay.pendenze.entity.PosizioneDebitoria creata = posizioneDebitoriaService.crea(posizione);

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(PosizioniDebitorieApi.PATH_GET_POSIZIONE_DEBITORIA)
                .buildAndExpand(idA2A, creata.getIdPosizioneDebitoria())
                .toUri();

        return ResponseEntity.created(location).body(mapper.toDto(creata));
    }

    /**
     * {@code @Transactional} qui, non solo su {@code PosizioneDebitoriaService.trovaPerIdentificativo}
     * (bug del lead, 2026-09-27): quel metodo apre e chiude la propria transazione prima di
     * tornare al controller — con {@code open-in-view=false} la sessione Hibernate e' gia'
     * chiusa quando {@code mapper.toDto(...)} legge le collezioni LAZY dell'aggregato
     * ({@code soggettiDebitori}/{@code opzioniPagamento}/{@code pendenze}), sollevando
     * {@code LazyInitializationException} — stessa classe di bug gia' vista su
     * {@code TipoVersamentoDominio.tipoVersamento}, ma non risolvibile con un semplice
     * {@code join fetch} sul repository: {@code soggettiDebitori} e {@code opzioniPagamento}
     * sono entrambe {@code List} (bag), e Hibernate non ammette il fetch join di piu' di una
     * bag nella stessa query ({@code MultipleBagFetchException}). Estendere qui il confine
     * transazionale, cosi' che copra anche la mappatura, e' la soluzione piu' semplice che non
     * tocca la forma delle collezioni della libreria.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<it.govpay.pendenze.api.model.PosizioneDebitoria> getPosizioneDebitoria(String idA2A,
            String idPosizioneDebitoria) {
        it.govpay.pendenze.entity.PosizioneDebitoria posizione = posizioneDebitoriaService
                .trovaPerIdentificativo(idA2A, idPosizioneDebitoria)
                .orElseThrow(() -> new RisorsaNonTrovataException("nessuna posizione debitoria con "
                        + "idPosizioneDebitoria [" + idPosizioneDebitoria + "] per idA2A [" + idA2A + "]"));

        return ResponseEntity.ok(mapper.toDto(posizione));
    }

    /**
     * Nessun campo ordinabile per default (vedi Javadoc di {@link CriteriOrdinamento}):
     * questo endpoint ne espone solo tre, ragionevoli per un elenco di posizioni e tutti
     * colonne dirette di {@code documenti} — nessuna indicazione esplicita nello YAML su
     * quali offrire.
     */
    private static final Map<String, String> CAMPI_ORDINABILI = Map.of(
            "dataCreazione", "dataCreazione",
            "dataPubblicazione", "dataPubblicazione",
            "idPosizioneDebitoria", "idPosizioneDebitoria");

    /**
     * {@code @Transactional} qui per lo stesso motivo di {@link #getPosizioneDebitoria}:
     * {@code toIndexDto} legge {@code soggettiDebitori}, collezione LAZY.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<PosizioniDebitorie> findPosizioniDebitorie(String idA2A, String idDebitore, Integer offset,
            Integer limit, String sort, String fields) {
        Sort ordinamento = CriteriOrdinamento.parse(sort, CAMPI_ORDINABILI);
        PaginaRisultati<it.govpay.pendenze.entity.PosizioneDebitoria> pagina = posizioneDebitoriaService
                .cercaPerDebitore(idA2A, idDebitore, OffsetPageRequest.of(offset, limit, ordinamento));

        PosizioniDebitorie dto = new PosizioniDebitorie();
        dto.setNumRisultati(BigDecimal.valueOf(pagina.numeroRisultatiTotali()));
        dto.setLimit(BigDecimal.valueOf(limit));
        dto.setOffset(BigDecimal.valueOf(offset));
        if (pagina.haAltriRisultati()) {
            dto.setProssimiRisultati(prossimaPaginaUri(idA2A, idDebitore, sort, fields, offset + limit, limit));
        }
        List<PosizioneDebitoriaIndex> risultati = pagina.risultati().stream()
                .map(mapper::toIndexDto)
                .collect(Collectors.toCollection(ArrayList::new));
        dto.setRisultati(applicaFields(risultati, fields));
        return ResponseEntity.ok(dto);
    }

    /**
     * Bug del lead, 2026-09-27: {@code fields} era dichiarato dallo YAML ma completamente
     * ignorato — la risposta conteneva sempre tutti i campi. Riduce ciascun elemento di
     * {@code risultati} alle sole proprieta' richieste (elenco separato da virgole).
     *
     * <p>{@code PosizioniDebitorie.risultati} e' tipizzato {@code List<PosizioneDebitoriaIndex>}
     * dall'interfaccia generata: non possiamo restituire una lista di {@code Map} con quella
     * firma. Jackson pero' serializza ogni elemento di una collezione in base alla sua classe
     * <b>reale</b> a runtime, non al tipo generico dichiarato — un {@code LinkedHashMap} qui
     * dentro produce comunque, in uscita, esattamente il JSON parziale voluto. Il cast e'
     * quindi sicuro nonostante l'avviso "unchecked": nulla nel codice tenta piu' di leggere
     * questi elementi come veri {@code PosizioneDebitoriaIndex} dopo questo punto.</p>
     *
     * @param fields elenco separato da virgole dei campi da mantenere, o {@code null}/vuoto
     *               per non applicare alcuna riduzione
     */
    @SuppressWarnings("unchecked")
    private List<PosizioneDebitoriaIndex> applicaFields(List<PosizioneDebitoriaIndex> risultati, String fields) {
        if (fields == null || fields.isBlank()) {
            return risultati;
        }
        Set<String> campiRichiesti = java.util.Arrays.stream(fields.split(","))
                .map(String::trim)
                .filter(campo -> !campo.isEmpty())
                .collect(Collectors.toSet());

        List<Object> ridotti = new ArrayList<>();
        for (PosizioneDebitoriaIndex elemento : risultati) {
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
        return (List<PosizioneDebitoriaIndex>) (List<?>) ridotti;
    }

    /**
     * URI relativo, non assoluto (esempio dello YAML: {@code /risorsa?offset=25&limit=25}) —
     * costruito esplicitamente dai parametri della richiesta corrente, non da
     * {@code ServletUriComponentsBuilder.fromCurrentRequest()} (che tornerebbe un URI assoluto
     * con schema/host). Riporta anche {@code fields} (bug del lead, 2026-09-27: la selezione
     * dei campi va mantenuta nelle pagine successive, altrimenti la seconda pagina tornerebbe
     * con tutti i campi).
     */
    private String prossimaPaginaUri(String idA2A, String idDebitore, String sort, String fields, long nuovoOffset,
            int limit) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromPath(PosizioniDebitorieApi.PATH_FIND_POSIZIONI_DEBITORIE)
                .queryParam("idDebitore", idDebitore)
                .queryParam("offset", nuovoOffset)
                .queryParam("limit", limit);
        if (sort != null && !sort.isBlank()) {
            builder.queryParam("sort", sort);
        }
        if (fields != null && !fields.isBlank()) {
            builder.queryParam("fields", fields);
        }
        return builder.buildAndExpand(idA2A).toUriString();
    }
}
