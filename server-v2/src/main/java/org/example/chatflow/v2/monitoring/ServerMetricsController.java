package org.example.chatflow.v2.monitoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class ServerMetricsController {

    private final ServerMetrics metrics;

    public ServerMetricsController(ServerMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        long now = System.currentTimeMillis();
        long uptimeMillis = now - metrics.getStartTimeMillis();
        long enqueued = metrics.getMessagesEnqueued();
        double uptimeSeconds = uptimeMillis / 1000.0;
        double messagesPerSecond = uptimeSeconds > 0 ? enqueued / uptimeSeconds : 0.0;

        Map<String, Object> out = new HashMap<>();
        out.put("startTimeMillis", metrics.getStartTimeMillis());
        out.put("uptimeMillis", uptimeMillis);
        out.put("messagesEnqueued", enqueued);
        out.put("queuePublishFailures", metrics.getQueuePublishFailures());
        out.put("messagesPerSecondApprox", messagesPerSecond);
        out.put("activeConnections", metrics.getActiveConnections());
        return out;
    }
}

