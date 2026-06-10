package com.example.client.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.annotation.spring.ClientMcpAsyncHandlersRegistry;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * MCP 클라이언트 목록을 관리하고 동적 재연결을 지원하는 홀더.
 *
 * 설계 결정:
 *   - MCP 서버 없이 클라이언트 단독 기동 가능 (일반 채팅만 동작)
 *   - 서버가 나중에 올라오면 재연결 시도 가능 (클라이언트 재시작 불필요)
 *   - 재연결 시 WebClientStreamableHttpTransport를 매번 새로 생성:
 *     싱글톤 트랜스포트는 연결 실패 후 내부 상태가 깨져 재사용 불가 — 신규 인스턴스 필수
 *   - tryReconnect()는 블로킹(최대 10초)이므로 boundedElastic 스케줄러에서 호출할 것
 *   - 쿨다운(5초): 서버 없는 동안 연속 요청이 올 때 재시도 횟수 제한
 */
@Slf4j
public class McpClientHolder {

    private static final long COOLDOWN_MS = 5_000;

    private final McpStreamableHttpClientProperties streamableProperties;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectMapper objectMapper;
    private final ClientMcpAsyncHandlersRegistry registry;
    private final long requestTimeoutMs;

    private final List<McpAsyncClient> clients = new ArrayList<>();
    private final AtomicLong lastAttemptAt = new AtomicLong(0);

    public McpClientHolder(McpStreamableHttpClientProperties streamableProperties,
                           ObjectProvider<WebClient.Builder> webClientBuilderProvider,
                           ObjectMapper objectMapper,
                           ClientMcpAsyncHandlersRegistry registry,
                           long requestTimeoutMs) {
        this.streamableProperties = streamableProperties;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public synchronized boolean isConnected() {
        return !clients.isEmpty();
    }

    /**
     * 재연결 시도 가능 여부:
     *   - 이미 연결됐으면 false (시도 불필요)
     *   - 마지막 실패 후 쿨다운(5초) 이내면 false (연속 재시도 방지)
     */
    public synchronized boolean canAttemptReconnect() {
        return clients.isEmpty()
                && System.currentTimeMillis() - lastAttemptAt.get() >= COOLDOWN_MS;
    }

    /**
     * MCP 서버 연결 시도. 이미 연결된 경우 즉시 true 반환.
     * 실패 시 쿨다운 타이머를 리셋하고 false 반환.
     *
     * 매 호출마다 WebClientStreamableHttpTransport를 새로 생성한다.
     * 기존 인스턴스는 연결 실패 후 재사용 불가 (내부 상태 파괴).
     *
     * 주의: 최대 10초 블로킹 — 반드시 boundedElastic 스레드에서 호출할 것.
     * 호출 전 canAttemptReconnect()로 쿨다운 여부를 확인하는 것을 권장.
     */
    public synchronized boolean tryReconnect() {
        if (!clients.isEmpty()) return true;

        lastAttemptAt.set(System.currentTimeMillis());

        WebClient.Builder builderTemplate = webClientBuilderProvider.getIfAvailable(WebClient::builder);

        for (Map.Entry<String, ConnectionParameters> entry : streamableProperties.getConnections().entrySet()) {
            String name = entry.getKey();
            ConnectionParameters params = entry.getValue();
            String url = params.url();
            String endpoint = params.endpoint() != null ? params.endpoint() : "/mcp";

            try {
                // 매 재연결 시도마다 신규 트랜스포트 생성 (재사용 불가)
                var freshTransport = WebClientStreamableHttpTransport
                        .builder(builderTemplate.clone().baseUrl(url))
                        .endpoint(endpoint)
                        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                        .build();

                McpClient.AsyncSpec spec = McpClient.async(freshTransport)
                        .clientInfo(new McpSchema.Implementation("webflux-mcp-client", "1.0.0"))
                        .requestTimeout(Duration.ofMillis(requestTimeoutMs));

                if (registry != null) {
                    McpSchema.ClientCapabilities caps = registry.getCapabilities(name);
                    if (caps != null) spec = spec.capabilities(caps);
                    spec = spec
                            .sampling(req -> registry.handleSampling(name, req))
                            .elicitation(req -> registry.handleElicitation(name, req))
                            .loggingConsumer(notif -> registry.handleLogging(name, notif))
                            .progressConsumer(progress -> registry.handleProgress(name, progress))
                            .toolsChangeConsumer(tools -> registry.handleToolListChanged(name, tools))
                            .resourcesChangeConsumer(res -> registry.handleResourceListChanged(name, res))
                            .promptsChangeConsumer(prompts -> registry.handlePromptListChanged(name, prompts));
                }

                McpAsyncClient client = spec.build();
                client.initialize().block(Duration.ofSeconds(10));
                log.info("[MCP] 서버 '{}' 연결 성공 (url={})", name, url);
                clients.add(client);

            } catch (Exception e) {
                log.warn("[MCP] 서버 '{}' 연결 실패 (url={}): {}", name, url, e.getMessage());
            }
        }

        if (clients.isEmpty()) {
            log.warn("[MCP] 재연결 실패 — {}ms 후 재시도 가능", COOLDOWN_MS);
        }

        return !clients.isEmpty();
    }

    /** 현재 연결된 클라이언트 목록으로 ToolCallback 배열 생성. 미연결 시 빈 배열 반환. */
    public synchronized ToolCallback[] getToolCallbacks() {
        if (clients.isEmpty()) return new ToolCallback[0];
        return AsyncMcpToolCallbackProvider.builder()
                .mcpClients(new ArrayList<>(clients))
                .build()
                .getToolCallbacks();
    }

    /** 업로드용 단일 클라이언트. 미연결 시 null 반환. */
    public synchronized McpAsyncClient getFirstClient() {
        return clients.isEmpty() ? null : clients.get(0);
    }
}
