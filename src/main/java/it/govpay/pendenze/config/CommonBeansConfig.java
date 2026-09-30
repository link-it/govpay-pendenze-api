package it.govpay.pendenze.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

import it.govpay.common.client.factory.RestTemplateFactory;
import it.govpay.common.client.oauth2.Oauth2ClientCredentialsManager;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.common.configurazione.service.ConfigurazioneService;
import it.govpay.common.repository.ConfigurazioneRepository;
import it.govpay.common.repository.ConnettoreEntityRepository;

/**
 * Wiring esplicito dei bean di sola lettura di govpay-common che servono ai log dinamici
 * ({@code ConfigurazioneService}, prerequisito di {@code DynamicLogLevelAutoConfiguration}) e
 * alle chiamate al GDE ({@code ConnettoreService}, per risolvere URL/credenziali del
 * connettore). Deliberatamente NON via {@code @ComponentScan} su
 * {@code it.govpay.common.client}/{@code it.govpay.common.configurazione}: quei pacchetti non
 * sono sotto {@code it.govpay.pendenze}, quindi restano fuori dallo scan di
 * default di {@code @SpringBootApplication} a prescindere — qui, a differenza di
 * govpay-console-api, non c'e' rischio di collisione di nomi con classi CRUD proprie (questo
 * servizio non ne ha), quindi nessun {@code @Qualifier} di disambiguazione e' necessario.
 *
 * <p>{@code asyncHttpExecutor} e' fornito da {@code it.govpay.pendenze.gde.AsyncGdeConfig}
 * (stesso bean usato per l'invio degli eventi GDE, con nome alias): un solo executor nel
 * contesto, non uno duplicato qui.
 */
@Configuration
public class CommonBeansConfig {

    @Bean
    public Oauth2ClientCredentialsManager oauth2ClientCredentialsManager() {
        return new Oauth2ClientCredentialsManager();
    }

    @Bean
    public RestTemplateFactory restTemplateFactory(Oauth2ClientCredentialsManager oauth2ClientCredentialsManager,
            ObjectMapper objectMapper) {
        return new RestTemplateFactory(oauth2ClientCredentialsManager, objectMapper);
    }

    @Bean
    public ConnettoreService connettoreService(ConnettoreEntityRepository connettoreEntityRepository,
            RestTemplateFactory restTemplateFactory, Executor asyncHttpExecutor) {
        return new ConnettoreService(connettoreEntityRepository, restTemplateFactory, asyncHttpExecutor);
    }

    @Bean
    public ConfigurazioneService configurazioneService(ConfigurazioneRepository configurazioneRepository,
            ObjectMapper objectMapper, ConnettoreService connettoreService) {
        return new ConfigurazioneService(configurazioneRepository, objectMapper, connettoreService);
    }
}
