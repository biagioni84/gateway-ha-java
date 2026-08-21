package uy.plomo.gateway.mqtt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class GatewayExecutorConfig {

    @Bean("gatewayExecutor")
    public ThreadPoolTaskExecutor gatewayExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(10);
        ex.setMaxPoolSize(20);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("gw-cmd-");
        // When the queue is full, reject and the caller will publish an error response
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.initialize();
        return ex;
    }
}
