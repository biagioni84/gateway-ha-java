package uy.plomo.gateway.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Offloads MQTT command processing off the AWS IoT event loop thread.
 *
 * MqttService (on AwsEventLoop) calls dispatch() and returns immediately.
 * Spring submits the work to the "gatewayExecutor" thread pool where blocking
 * Z-Wave / Zigbee / Matter calls are safe to execute.
 */
@Component
@Slf4j
public class AsyncCommandDispatcher {

    private final MqttDispatcher mqttDispatcher;
    private final MqttService    mqttService;

    public AsyncCommandDispatcher(MqttDispatcher mqttDispatcher,
                                  @Lazy MqttService mqttService) {
        this.mqttDispatcher = mqttDispatcher;
        this.mqttService    = mqttService;
    }

    @Async("gatewayExecutor")
    public void dispatch(String requestId, String method, String path, String body) {
        try {
            String response = mqttDispatcher.dispatch(method, path, body);
            mqttService.publishResponse(requestId, response != null ? response : "{}");
        } catch (Exception e) {
            log.error("Async dispatch failed for {} {}: {}", method, path, e.getMessage());
            mqttService.publishResponse(requestId, "{\"error\":\"internal error\"}");
        }
    }
}
