package com.example.client.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${app.server.url}")
    private String serverUrl;

    @Bean
    public WebClient serverWebClient() {
        return WebClient.builder()
                .baseUrl(serverUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(52 * 1024 * 1024))
                .build();
    }
}
