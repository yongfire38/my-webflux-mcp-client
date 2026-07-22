package com.example.client.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.spring.ClientMcpAsyncHandlersRegistry;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * MCP 클라이언트 선택적 초기화 구성.
 *
 * McpClientAutoConfiguration은 컨텍스트 초기화 시 MCP 서버에 blocking 연결을 시도하여,
 * 서버가 없으면 기동 자체가 실패한다. 이 클래스는 그 대신 McpClientHolder를 제공한다:
 *   - 기동 시 연결 시도 → 성공하면 즉시 MCP 기능 사용 가능
 *   - 기동 시 연결 실패 → 빈 목록으로 시작, 이후 RAG 요청 시 동적 재연결 시도
 *   - 재연결 시 트랜스포트를 매번 새로 생성 (기존 인스턴스 재사용 불가)
 *
 * application.yml에서 McpClientAutoConfiguration, McpToolCallbackAutoConfiguration을
 * spring.autoconfigure.exclude로 제외해야 이 구성이 충돌 없이 동작한다.
 * StreamableHttpWebFluxTransportAutoConfiguration은 제외하지 않는다
 * — McpStreamableHttpClientProperties 빈(URL, endpoint 등)을 제공하기 때문.
 */
@Slf4j
@Configuration
public class McpOptionalConfig {

    @Value("${spring.ai.mcp.client.request-timeout:60000}")
    private long requestTimeoutMs;

    @Value("${app.mcp-client-id:webflux-mcp-client}")
    private String mcpClientId;

    @Value("${app.mcp-api-key:}")
    private String mcpApiKey;

    @Bean
    public McpClientHolder mcpClientHolder(
            McpStreamableHttpClientProperties streamableProperties,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<ClientMcpAsyncHandlersRegistry> registryProvider) {

        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        ClientMcpAsyncHandlersRegistry registry = registryProvider.getIfAvailable();

        McpClientHolder holder = new McpClientHolder(
                streamableProperties,
                webClientBuilderProvider,
                objectMapper,
                registry,
                requestTimeoutMs,
                mcpClientId,
                mcpApiKey);

        // 기동 시 초기 연결 시도 (실패해도 기동은 계속)
        boolean connected = holder.tryReconnect();
        if (!connected) {
            log.warn("[MCP] 초기 연결 실패 — 일반 채팅만 가능. RAG 요청 시 자동 재연결 시도.");
        }

        return holder;
    }
}
