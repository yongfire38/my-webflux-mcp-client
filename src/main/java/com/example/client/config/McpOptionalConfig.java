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
 * MCP 서버 레지스트리 구성.
 *
 * McpClientAutoConfiguration은 컨텍스트 초기화 시 MCP 서버에 blocking 연결을 시도하여
 * 서버가 없으면 기동 자체가 실패한다. McpServerRegistry는 이를 대체한다:
 *   - 기동 시 모든 서버 연결 시도 (실패해도 기동 계속)
 *   - 서버별 상태 추적 (DISCONNECTED / CONNECTED / FAILED)
 *   - UI에서 connect/disconnect 버튼으로 명시적 제어
 *   - HTTP + stdio 서버 통합 관리
 *
 * application.yml에서 McpClientAutoConfiguration, McpToolCallbackAutoConfiguration을
 * spring.autoconfigure.exclude로 제외해야 이 구성이 충돌 없이 동작한다.
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
    public McpServerRegistry mcpServerRegistry(
            McpStreamableHttpClientProperties httpProperties,
            AppStdioMcpProperties stdioProperties,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<ClientMcpAsyncHandlersRegistry> registryProvider) {

        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        ClientMcpAsyncHandlersRegistry handlerRegistry = registryProvider.getIfAvailable();

        McpServerRegistry registry = new McpServerRegistry(
                httpProperties,
                stdioProperties,
                webClientBuilderProvider,
                objectMapper,
                handlerRegistry,
                requestTimeoutMs,
                mcpClientId,
                mcpApiKey);

        // 기동 시 전체 연결 시도 (실패해도 기동 계속, UI에서 재연결 가능)
        registry.tryConnectAll();

        // JVM 종료 시 stdio 프로세스 등 리소스 정리
        Runtime.getRuntime().addShutdownHook(new Thread(registry::shutdown));

        return registry;
    }
}
