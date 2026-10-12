package it.govpay.pendenze.gde;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import it.govpay.common.logging.MdcTaskDecorator;

/**
 * Bean {@code gdeExecutor} usato da {@link PendenzeGdeService} per l'invio
 * asincrono degli eventi GDE, alias anche {@code asyncHttpExecutor} perche'
 * lo stesso executor serve al costruttore di
 * {@code it.govpay.common.client.service.ConnettoreService} (vedi
 * {@link it.govpay.pendenze.config.CommonBeansConfig}).
 * <p>
 * Se {@code app.gde.async=true} (default): {@link ThreadPoolTaskExecutor}
 * con pool dedicato {@code gde-*}. Se {@code app.gde.async=false} (impostato
 * nel profilo test): {@link SyncTaskExecutor}, cosi' i test che verificano
 * l'invio dell'evento lo trovano senza dover attendere il thread async.
 * <p>
 * {@link MdcTaskDecorator} e' indispensabile sul ramo asincrono: senza,
 * transaction id e correlation id (in {@code TransactionContext}, un
 * ThreadLocal) non attraversano il cambio di thread verso il pool —
 * {@code AbstractGdeService#inviaEvento} eseguito sul thread {@code gde-*}
 * troverebbe un contesto vuoto, perdendo sia la correlazione nei log di
 * quell'invio sia la propagazione del correlation id alla chiamata HTTP
 * verso il GDE (fatta da {@code CorrelationIdClientInterceptor}, che legge
 * lo stesso ThreadLocal). Il ramo sincrono non ne ha bisogno: gira sul
 * thread chiamante, dove il contesto e' gia' presente.
 */
@Configuration
public class AsyncGdeConfig {

    @Bean(name = { "gdeExecutor", "asyncHttpExecutor" })
    public TaskExecutor gdeExecutor(@Value("${app.gde.async:true}") boolean async,
                                    @Value("${app.gde.pool.core-size:2}") int coreSize,
                                    @Value("${app.gde.pool.max-size:4}") int maxSize,
                                    @Value("${app.gde.pool.queue-capacity:100}") int queueCapacity) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(coreSize);
        exec.setMaxPoolSize(maxSize);
        exec.setQueueCapacity(queueCapacity);
        exec.setThreadNamePrefix("gde-");
        // l'invio GDE non deve mai bloccare la response: log e drop
        exec.setRejectedExecutionHandler((r, e) -> org.slf4j.LoggerFactory.getLogger(AsyncGdeConfig.class)
                .warn("Evento GDE rifiutato dall'executor (pool saturo): evento droppato."));
        exec.setTaskDecorator(new MdcTaskDecorator());
        exec.initialize();
        return exec;
    }
}
