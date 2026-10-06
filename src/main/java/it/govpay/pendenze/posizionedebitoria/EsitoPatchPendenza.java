package it.govpay.pendenze.posizionedebitoria;

import it.govpay.pendenze.model.StatoPendenza;

/**
 * Esito della validazione di {@code PATCH .../pendenze/{idA2A}/{idPendenza}}
 * ({@link PosizioneDebitoriaMapper#validaPatchPendenza}): i due path supportati,
 * {@code /stato} e {@code /descrizioneStato}, sono indipendenti — ciascuno campo e'
 * {@code null} se la relativa operazione non era presente nel body, cosi' il chiamante
 * applica solo le mutazioni effettivamente richieste.
 */
public record EsitoPatchPendenza(StatoPendenza nuovoStato, String descrizioneStato) {
}
