package org.example.chatflow.consumer.monitoring;

import org.example.chatflow.consumer.queue.ConsumerScaler;
import org.example.chatflow.consumer.websocket.RoomManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class ConsumerMetricsController {

    private final RoomManager    roomManager;
    private final ConsumerScaler scaler;
    private final long startTimeMillis = System.currentTimeMillis();

    public ConsumerMetricsController(RoomManager roomManager, ConsumerScaler scaler) {
        this.roomManager = roomManager;
        this.scaler      = scaler;
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
        out.put("activeWorkerThreads",    scaler.getWorkerCount());   // Fix 4: expose dynamic thread count
        return out;
    }
}

