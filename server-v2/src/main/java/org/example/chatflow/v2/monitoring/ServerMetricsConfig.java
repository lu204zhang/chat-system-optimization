package org.example.chatflow.v2.monitoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServerMetricsConfig {

    @Bean
    public ServerMetrics serverMetrics() {
        return new ServerMetrics();
    }
}

