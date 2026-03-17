package org.example.chatflow.v2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfigV2 {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}

