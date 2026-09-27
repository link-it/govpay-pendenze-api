package it.govpay.pendenze.web;

/**
 * Sollevata quando un codice di un'anagrafica esterna riferito da una richiesta (idA2A,
 * idDominio, idUnitaOperativa, idTipoPendenza) non risolve a nessuna riga esistente.
 *
 * <p>Distinta da {@code it.govpay.pendenze.exception.RisorsaNonTrovataException} di
 * {@code govpay-common-pendenze}, che copre solo le risorse dell'aggregato pendenza stesso
 * (posizione debitoria, opzione di pagamento) — la risoluzione di un'anagrafica esterna e'
 * responsabilita' di questo progetto (M4: la libreria tratta questi riferimenti solo come FK
 * piatte Long, non li risolve mai da sola), non della libreria. Vive in questo package (non
 * in un proprio {@code it.govpay.pendenze.exception}) apposta per non condividere il nome di
 * package con quello, gia' usato dalla libreria.</p>
 */
public class AnagraficaNonTrovataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnagraficaNonTrovataException(String messaggio) {
        super(messaggio);
    }
}
