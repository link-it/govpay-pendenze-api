package it.govpay.pendenze.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import it.govpay.common.auth.PrincipalCaptureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Logga ogni richiesta HTTP a livello INFO con metodo, path, status,
 * principal (se autenticato) e durata. Il {@code transactionId} e il
 * {@code correlationId} in MDC sono settati dal {@code TransactionIdFilter} di
 * govpay-common, quindi appaiono automaticamente nel formato di log.
 *
 * <p>L'ordine deve essere numericamente MAGGIORE dell'ordine di
 * {@code TransactionIdFilter} (default {@code HIGHEST_PRECEDENCE + 100}), non
 * minore: un filtro con ordine piu' basso avvolge quelli con ordine piu' alto
 * dall'esterno, quindi il suo blocco {@code finally} viene eseguito DOPO che
 * il filtro interno ha gia' ripulito il proprio contesto — qui l'MDC
 * risulterebbe gia' vuoto al momento del log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 110)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String ANONYMOUS = "<anonymous>";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            if (log.isInfoEnabled()) {
                log.info("{} {} status={} duration={}ms principal={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        duration,
                        resolvePrincipal(request));
            }
        }
    }

    private static String resolvePrincipal(HttpServletRequest request) {
        Object captured = request.getAttribute(PrincipalCaptureFilter.REQUEST_ATTRIBUTE);
        if (captured instanceof String s && !s.isBlank()) {
            return s;
        }
        return ANONYMOUS;
    }
}
