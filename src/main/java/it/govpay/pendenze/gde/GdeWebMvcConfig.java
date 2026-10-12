package it.govpay.pendenze.gde;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GdeWebMvcConfig implements WebMvcConfigurer {

    private final OperationIdHandlerInterceptor operationIdHandlerInterceptor;

    public GdeWebMvcConfig(OperationIdHandlerInterceptor operationIdHandlerInterceptor) {
        this.operationIdHandlerInterceptor = operationIdHandlerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operationIdHandlerInterceptor);
    }
}
