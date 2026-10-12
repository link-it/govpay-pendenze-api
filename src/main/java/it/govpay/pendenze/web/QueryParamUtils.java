package it.govpay.pendenze.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Verifica se un parametro di query e' stato effettivamente specificato dal chiamante,
 * distinguendolo da un valore di default applicato dal generatore OpenAPI (es. {@code page},
 * che nello YAML ha {@code default: 1}: il parametro del metodo generato vale sempre {@code 1}
 * quando assente, indistinguibile da un {@code ?page=1} esplicito se si guarda solo il valore
 * dell'argomento). Stessa tecnica di govpay-console-api ({@code ListQueryValidator}):
 * ispeziona la query string grezza della richiesta corrente.
 */
public final class QueryParamUtils {

    private QueryParamUtils() {
    }

    public static boolean isExplicit(HttpServletRequest request, String name) {
        return request != null && request.getParameterMap().containsKey(name);
    }
}
