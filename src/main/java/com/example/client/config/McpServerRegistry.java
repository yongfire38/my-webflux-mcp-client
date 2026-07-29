package com.example.client.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.example.client.config.AppStdioMcpProperties.StdioServerDef;
import com.example.client.dto.ServerStatusDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
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
 * HTTP(Streamable) 및 stdio MCP 서버를 통합 관리하는 레지스트리.
 *
 * McpClientHolder를 대체하며, 서버별 상태(DISCONNECTED/CONNECTED/FAILED)를 독립적으로 추적한다.
 * connect/disconnect/writeAllowed는 REST API(/api/mcp/servers/**)를 통해 UI에서 제어한다.
 *
 * 참고: Spring AI McpClientAutoConfiguration을 사용하면 stdio 포함 모든 전송 방식이
 * application.yml 설정만으로 자동 구성된다. 이 클래스는 동적 재연결·per-server 상태 관리를
 * 위해 자동구성을 배제한 결과로 수동 구현된 것이다.
 */
@Slf4j
public class McpServerRegistry {

    public enum Status { DISCONNECTED, CONNECTED, FAILED }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 모델
    // ─────────────────────────────────────────────────────────────────────────

    private static class ServerConfig {
        final String type;
        final ConnectionParameters httpParams;
        final StdioServerDef stdioParams;

        ServerConfig(String name, ConnectionParameters httpParams) {
            this.type = "http";
            this.httpParams = httpParams;
            this.stdioParams = null;
        }

        ServerConfig(String name, StdioServerDef stdioParams) {
            this.type = "stdio";
            this.httpParams = null;
            this.stdioParams = stdioParams;
        }
    }

    private static class ServerRuntime {
        volatile Status status = Status.DISCONNECTED;
        McpAsyncClient client;
        Process process;
        List<McpSchema.Tool> tools = Collections.emptyList();
        boolean writeAllowed = false;
        String lastError;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 필드
    // ─────────────────────────────────────────────────────────────────────────

    private final Map<String, ServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, ServerRuntime> runtimes = new LinkedHashMap<>();

    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectMapper objectMapper;
    private final ClientMcpAsyncHandlersRegistry handlerRegistry;
    private final long requestTimeoutMs;
    private final String clientId;
    private final String mcpApiKey;

    public McpServerRegistry(
            McpStreamableHttpClientProperties httpProperties,
            AppStdioMcpProperties stdioProperties,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectMapper objectMapper,
            ClientMcpAsyncHandlersRegistry handlerRegistry,
            long requestTimeoutMs,
            String clientId,
            String mcpApiKey) {

        this.webClientBuilderProvider = webClientBuilderProvider;
        this.objectMapper = objectMapper;
        this.handlerRegistry = handlerRegistry;
        this.requestTimeoutMs = requestTimeoutMs;
        this.clientId = clientId;
        this.mcpApiKey = mcpApiKey;

        if (httpProperties != null && httpProperties.getConnections() != null) {
            httpProperties.getConnections().forEach((name, params) -> {
                configs.put(name, new ServerConfig(name, params));
                runtimes.put(name, new ServerRuntime());
            });
        }

        if (stdioProperties != null && stdioProperties.getServers() != null) {
            stdioProperties.getServers().forEach((name, def) -> {
                configs.put(name, new ServerConfig(name, def));
                runtimes.put(name, new ServerRuntime());
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 퍼블릭 API
    // ─────────────────────────────────────────────────────────────────────────

    /** 기동 시 모든 서버 연결 시도. 실패해도 예외를 전파하지 않는다. */
    public void tryConnectAll() {
        configs.keySet().forEach(name -> {
            try {
                connect(name);
            } catch (Exception e) {
                log.warn("[MCP] '{}' 초기 연결 실패: {}", name, e.getMessage());
            }
        });
    }

    /**
     * 서버 연결. 이미 CONNECTED 상태이면 현재 상태를 그대로 반환한다.
     * 블로킹 호출이므로 boundedElastic 스케줄러에서 호출할 것.
     */
    public synchronized ServerStatusDto connect(String name) {
        ServerConfig config = configs.get(name);
        if (config == null) throw new IllegalArgumentException("알 수 없는 서버: " + name);

        ServerRuntime runtime = runtimes.get(name);
        if (runtime.status == Status.CONNECTED) {
            log.info("[MCP] '{}' 이미 연결됨", name);
            return toDto(name, config, runtime);
        }

        runtime.lastError = null;

        try {
            McpAsyncClient client = "http".equals(config.type)
                    ? createHttpClient(name, config)
                    : createStdioClient(name, config, runtime);

            client.initialize().block(Duration.ofSeconds(15));

            runtime.client = client;
            runtime.tools = fetchTools(client);
            runtime.status = Status.CONNECTED;
            List<String> names = runtime.tools.stream().map(McpSchema.Tool::name).toList();
            log.info("[MCP] '{}' 연결 성공 — 도구 {}개: {}", name, names.size(), names);

        } catch (Exception e) {
            runtime.status = Status.FAILED;
            runtime.lastError = e.getMessage();
            cleanupRuntime(runtime);
            log.warn("[MCP] '{}' 연결 실패: {}", name, e.getMessage());
        }

        return toDto(name, config, runtime);
    }

    /** 서버 연결 해제. */
    public synchronized void disconnect(String name) {
        ServerConfig config = configs.get(name);
        if (config == null) throw new IllegalArgumentException("알 수 없는 서버: " + name);

        ServerRuntime runtime = runtimes.get(name);
        cleanupRuntime(runtime);
        runtime.status = Status.DISCONNECTED;
        runtime.lastError = null;
        runtime.tools = Collections.emptyList();
        runtime.writeAllowed = false;
        log.info("[MCP] '{}' 연결 해제", name);
    }

    /** 전체 서버 상태 목록. */
    public synchronized List<ServerStatusDto> getServerStatuses() {
        List<ServerStatusDto> result = new ArrayList<>();
        configs.forEach((name, config) -> result.add(toDto(name, config, runtimes.get(name))));
        return result;
    }

    /**
     * 연결된 모든 서버의 도구 콜백 집합.
     *
     * 각 클라이언트에 listTools()로 직접 핑을 먼저 찔러 살아있는지 확인한다.
     * 핑 실패 시 FAILED 처리 후 제외, 성공한 클라이언트만 콜백 제공.
     * 모두 실패해도 예외를 던지지 않는다.
     */
    public synchronized ToolCallback[] getToolCallbacks() {
        List<McpAsyncClient> live = new ArrayList<>();
        for (Entry<String, ServerRuntime> entry : runtimes.entrySet()) {
            String name = entry.getKey();
            ServerRuntime runtime = entry.getValue();
            if (runtime.status != Status.CONNECTED || runtime.client == null) continue;
            try {
                runtime.client.listTools(null).block(Duration.ofSeconds(5));
                live.add(runtime.client);
            } catch (Exception e) {
                log.warn("[MCP] '{}' 핑 실패 — FAILED 처리: {}", name, e.getMessage());
                runtime.status = Status.FAILED;
                runtime.lastError = "서버 응답 없음: " + e.getMessage();
                cleanupRuntime(runtime);
            }
        }
        if (live.isEmpty()) return new ToolCallback[0];

        // 살아있는 클라이언트별로 콜백을 얻어 쓰기 가드로 래핑
        List<ToolCallback> all = new ArrayList<>();
        for (Entry<String, ServerRuntime> entry : runtimes.entrySet()) {
            ServerRuntime rt = entry.getValue();
            if (!live.contains(rt.client)) continue;
            ToolCallback[] cbs = AsyncMcpToolCallbackProvider.builder()
                    .mcpClients(List.of(rt.client))
                    .build()
                    .getToolCallbacks();
            for (ToolCallback cb : cbs) {
                all.add(wrapWithWriteGuard(cb, rt));
            }
        }
        return all.toArray(new ToolCallback[0]);
    }

    /** 쓰기 허용 여부 토글. */
    public synchronized ServerStatusDto setWriteAllowed(String name, boolean allowed) {
        ServerConfig config = configs.get(name);
        if (config == null) throw new IllegalArgumentException("알 수 없는 서버: " + name);
        ServerRuntime runtime = runtimes.get(name);
        runtime.writeAllowed = allowed;
        log.info("[MCP] '{}' 쓰기 권한 {}", name, allowed ? "허용" : "차단");
        return toDto(name, config, runtime);
    }

    /**
     * 특정 이름의 서버 클라이언트 반환 (MCP 도구 직접 호출용, e.g. 문서 적재).
     * 미연결이면 null 반환.
     */
    public synchronized McpAsyncClient getClient(String name) {
        ServerRuntime runtime = runtimes.get(name);
        if (runtime == null || runtime.status != Status.CONNECTED) return null;
        return runtime.client;
    }

    /** 특정 서버의 현재 상태 반환. 존재하지 않는 이름이면 null. */
    public synchronized ServerStatusDto getServerStatus(String name) {
        ServerConfig config = configs.get(name);
        if (config == null) return null;
        return toDto(name, config, runtimes.get(name));
    }

    /** 연결된 서버가 하나 이상인지 확인. */
    public synchronized boolean hasAnyConnected() {
        return runtimes.values().stream().anyMatch(r -> r.status == Status.CONNECTED);
    }

    /** yml에 등록된 서버가 하나 이상인지 확인 (연결 여부 무관). */
    public synchronized boolean hasAnyServer() {
        return !configs.isEmpty();
    }

    /** JVM 종료 시 모든 리소스 정리. ShutdownHook에서 호출. */
    public synchronized void shutdown() {
        runtimes.forEach((name, runtime) -> cleanupRuntime(runtime));
        log.info("[MCP] 모든 서버 연결 종료 완료");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private McpAsyncClient createHttpClient(String name, ServerConfig config) {
        ConnectionParameters params = config.httpParams;
        String url = params.url();
        String endpoint = params.endpoint() != null ? params.endpoint() : "/mcp";

        WebClient.Builder builder = webClientBuilderProvider
                .getIfAvailable(WebClient::builder)
                .clone()
                .baseUrl(url);

        if (mcpApiKey != null && !mcpApiKey.isBlank()) {
            builder = builder.defaultHeader("X-MCP-API-Key", mcpApiKey);
        }

        var transport = WebClientStreamableHttpTransport.builder(builder)
                .endpoint(endpoint)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .build();

        return wireAndBuild(name, McpClient.async(transport));
    }

    /**
     * stdio 서버 클라이언트 생성.
     *
     * StdioClientTransport가 프로세스 생명주기를 내부에서 관리하므로
     * ServerRuntime.process는 사용하지 않는다.
     * 프로세스 종료는 McpAsyncClient.close() → transport.closeGracefully()로 처리된다.
     *
     * Windows에서 npx 같은 .cmd 파일 실행 시 command를 "cmd", args 첫 번째를 "/c"로 지정할 것.
     */
    private McpAsyncClient createStdioClient(String name, ServerConfig config,
                                              ServerRuntime runtime) {
        StdioServerDef def = config.stdioParams;

        ServerParameters.Builder paramsBuilder = ServerParameters.builder(def.getCommand());
        if (def.getArgs() != null && !def.getArgs().isEmpty()) {
            paramsBuilder = paramsBuilder.args(def.getArgs());
        }
        if (def.getEnv() != null && !def.getEnv().isEmpty()) {
            paramsBuilder = paramsBuilder.env(def.getEnv());
        }
        ServerParameters params = paramsBuilder.build();

        var transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(objectMapper));
        transport.setStdErrorHandler(
                line -> log.warn("[MCP-stdio][{}] stderr: {}", name, line));

        return wireAndBuild(name, McpClient.async(transport));
    }

    private McpAsyncClient wireAndBuild(String name, McpClient.AsyncSpec spec) {
        spec = spec
                .clientInfo(new McpSchema.Implementation(clientId, "1.0.0"))
                .requestTimeout(Duration.ofMillis(requestTimeoutMs));

        if (handlerRegistry != null) {
            McpSchema.ClientCapabilities caps = handlerRegistry.getCapabilities(name);
            if (caps != null) spec = spec.capabilities(caps);
            spec = spec
                    .sampling(req -> handlerRegistry.handleSampling(name, req))
                    .elicitation(req -> handlerRegistry.handleElicitation(name, req))
                    .loggingConsumer(n -> handlerRegistry.handleLogging(name, n))
                    .progressConsumer(p -> handlerRegistry.handleProgress(name, p))
                    .toolsChangeConsumer(t -> handlerRegistry.handleToolListChanged(name, t))
                    .resourcesChangeConsumer(r -> handlerRegistry.handleResourceListChanged(name, r))
                    .promptsChangeConsumer(p -> handlerRegistry.handlePromptListChanged(name, p));
        }

        return spec.build();
    }

    private List<McpSchema.Tool> fetchTools(McpAsyncClient client) {
        try {
            McpSchema.ListToolsResult result = client.listTools(null).block(Duration.ofSeconds(5));
            if (result == null || result.tools() == null) return Collections.emptyList();
            return result.tools();
        } catch (Exception e) {
            log.warn("[MCP] 도구 목록 조회 실패: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 쓰기/파괴적 도구인지 판단.
     * 1순위: ToolAnnotations (readOnlyHint, destructiveHint)
     * 2순위: 도구 이름 휴리스틱 (annotations 없는 서버 대비 fallback)
     */
    private boolean isWriteOperation(McpSchema.Tool tool) {
        if (tool == null) return false;
        McpSchema.ToolAnnotations ann = tool.annotations();
        if (ann != null) {
            if (Boolean.TRUE.equals(ann.readOnlyHint())) return false;
            if (Boolean.TRUE.equals(ann.destructiveHint())) return true;
        }
        String n = tool.name().toLowerCase();
        return n.contains("write") || n.contains("create") || n.contains("delete")
            || n.contains("update") || n.contains("edit")  || n.contains("move")
            || n.contains("rename") || n.contains("append");
    }

    /** 쓰기 도구이면 writeAllowed 플래그를 확인하는 가드로 래핑. */
    private ToolCallback wrapWithWriteGuard(ToolCallback cb, ServerRuntime runtime) {
        McpSchema.Tool mcpTool = runtime.tools.stream()
                .filter(t -> t.name().equals(cb.getToolDefinition().name()))
                .findFirst().orElse(null);
        if (!isWriteOperation(mcpTool)) return cb;

        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return cb.getToolDefinition();
            }
            @Override
            public String call(String toolInput) {
                if (!runtime.writeAllowed) {
                    return "쓰기 작업이 차단되었습니다. 사이드바의 [쓰기 허용] 토글을 활성화하세요.";
                }
                return cb.call(toolInput);
            }
        };
    }

    private void cleanupRuntime(ServerRuntime runtime) {
        if (runtime.client != null) {
            try { runtime.client.close(); } catch (Exception ignored) {}
            runtime.client = null;
        }
        if (runtime.process != null && runtime.process.isAlive()) {
            runtime.process.destroyForcibly();
            runtime.process = null;
        }
    }

    private ServerStatusDto toDto(String name, ServerConfig config, ServerRuntime runtime) {
        List<String> toolNames = runtime.tools.stream().map(McpSchema.Tool::name).toList();
        return new ServerStatusDto(
                name, config.type, runtime.status.name(),
                runtime.lastError, toolNames, runtime.writeAllowed);
    }
}
