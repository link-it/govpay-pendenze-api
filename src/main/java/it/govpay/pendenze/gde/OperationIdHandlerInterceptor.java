package it.govpay.pendenze.gde;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rende disponibili, come request attribute, due informazioni che
 * {@link GdeEventFilter} legge solo dopo l'esecuzione della catena (nel
 * proprio {@code finally}):
 * <ul>
 *   <li>il nome dell'operazione (metodo Java del controller generato,
 *       coincidente con l'operationId OpenAPI) — un {@link jakarta.servlet.Filter}
 *       non riceve mai l'{@link HandlerMethod} risolto, solo un
 *       {@link HandlerInterceptor} lo espone, in {@link #preHandle};</li>
 *   <li>il principal autenticato — deve essere letto QUI, non nel
 *       {@code finally} del filtro: la catena di Spring Security ripulisce
 *       il {@code SecurityContext} nel proprio {@code finally}, che si
 *       chiude prima di risalire fino a un filtro esterno come
 *       {@link GdeEventFilter} (ordine {@code HIGHEST_PRECEDENCE + 120},
 *       ampiamente esterno alla security chain, che parte da
 *       {@code SecurityProperties.DEFAULT_FILTER_ORDER}). Un
 *       {@link HandlerInterceptor#preHandle} gira alla stessa
 *       profondita' del controller, quando il contesto e' ancora garantito
 *       popolato.</li>
 * </ul>
 */
@Component
public class OperationIdHandlerInterceptor implements HandlerInterceptor {

    public static final String OPERATION_ID_ATTRIBUTE = "it.govpay.pendenze.gde.operationId";
    public static final String PRINCIPAL_ATTRIBUTE = "it.govpay.pendenze.gde.principal";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            request.setAttribute(OPERATION_ID_ATTRIBUTE, handlerMethod.getMethod().getName());
        }
        String principal = currentPrincipal();
        if (principal != null) {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        }
        return true;
    }

    private static String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }
}
