package org.example.chatflow.consumer.monitoring;

import org.example.chatflow.consumer.cache.CacheService;
import org.example.chatflow.consumer.queue.ConsumerScaler;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class ConsumerMetricsController {

    private final RoomManager    roomManager;
    private final ObjectProvider<ConsumerScaler> scalerProvider;
    private final FanoutMetrics fanoutMetrics;
    private final CacheService  cacheService;
    private final long startTimeMillis = System.currentTimeMillis();

    public ConsumerMetricsController(RoomManager roomManager,
                                    ObjectProvider<ConsumerScaler> scalerProvider,
                                    FanoutMetrics fanoutMetrics,
                                    CacheService cacheService) {
        this.roomManager     = roomManager;
        this.scalerProvider  = scalerProvider;
        this.fanoutMetrics   = fanoutMetrics;
        this.cacheService    = cacheService;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        long   now              = System.currentTimeMillis();
        long   uptimeMillis     = now - startTimeMillis;
        long   processed        = roomManager.getMessagesProcessed();
        double uptimeSeconds    = uptimeMillis / 1000.0;
        double messagesPerSecond = uptimeSeconds > 0 ? processed / uptimeSeconds : 0.0;

        Map<String, Object> out = new HashMap<>();
        out.put("startTimeMillis",        startTimeMillis);
        out.put("uptimeMillis",           uptimeMillis);
        out.put("messagesProcessed",      processed);
        out.put("messagesPerSecondApprox", messagesPerSecond);
        ConsumerScaler scaler = scalerProvider.getIfAvailable();
        out.put("activeWorkerThreads", scaler != null ? scaler.getWorkerCount() : null);
        out.putAll(fanoutMetrics.snapshot());
        out.put("cache", cacheService.getCacheStats());
        return out;
    }
}

