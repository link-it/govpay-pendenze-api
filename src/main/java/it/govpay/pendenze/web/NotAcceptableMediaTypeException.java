package it.govpay.pendenze.web;

/**
 * L'header {@code Accept} della richiesta non corrisponde a nessuno dei content-type che
 * l'endpoint puo' restituire. Sollevata da un controller che sceglie il content-type a mano
 * (es. {@code GET /pendenze/{idA2A}/{idPendenza}/stampa}, che alterna JSON/PDF sullo stesso
 * path), non dal meccanismo di content negotiation automatico di Spring MVC — per quello resta
 * {@link org.springframework.web.HttpMediaTypeNotAcceptableException}.
 */
public class NotAcceptableMediaTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotAcceptableMediaTypeException(String messaggio) {
        super(messaggio);
    }
}
