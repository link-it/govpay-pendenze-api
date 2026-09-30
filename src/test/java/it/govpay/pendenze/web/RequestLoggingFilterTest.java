package it.govpay.pendenze.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import it.govpay.common.auth.PrincipalCaptureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void invocaLaCatenaDeiFiltriAncheConPrincipalAssente() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/pendenze/A2A-TEST");
        when(request.getAttribute(PrincipalCaptureFilter.REQUEST_ATTRIBUTE)).thenReturn(null);
        when(response.getStatus()).thenReturn(200);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void invocaLaCatenaDeiFiltriAncheSeSollevaEccezione() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/posizioni-debitorie/A2A-TEST");
        when(response.getStatus()).thenReturn(500);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(chain).doFilter(request, response);
            filter.doFilterInternal(request, response, chain);
        }).isInstanceOf(RuntimeException.class);
    }
}
