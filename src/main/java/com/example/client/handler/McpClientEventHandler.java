package com.example.client.handler;

import java.util.ArrayList;
import java.util.List;

import org.springaicommunity.mcp.annotation.McpLogging;
import org.springaicommunity.mcp.annotation.McpPromptListChanged;
import org.springaicommunity.mcp.annotation.McpResourceListChanged;
import org.springaicommunity.mcp.annotation.McpSampling;
import org.springaicommunity.mcp.annotation.McpToolListChanged;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP 클라이언트 이벤트 핸들러
 *
 * MCP 서버에서 전송하는 알림(Notification)과 요청(Request)을 처리합니다.
 * 모든 어노테이션의 clients 값은 application.yml의 연결 이름과 일치해야 합니다.
 *
 * 연결 이름: "mcp-server"
 *   spring.ai.mcp.client.streamable-http.connections.mcp-server.url
 *
 * ─── 구현된 핸들러 ───────────────────────────────────────────────────────────
 *
 *  @McpLogging          MCP 서버 로그 알림 → SLF4J 레벨별 라우팅
 *  @McpToolListChanged  서버 도구 목록 변경 알림 → 로그
 *  @McpResourceListChanged  서버 리소스 목록 변경 알림 → 로그 (문서 인덱싱 감지)
 *  @McpPromptListChanged    서버 프롬프트 목록 변경 알림 → 로그
 *  @McpSampling         서버의 LLM 샘플링 요청 → Ollama(ChatModel)로 위임 처리
 *
 * ─── 미구현 핸들러 ────────────────────────────────────────────────────────────
 *
 *  @McpElicitation  서버의 사용자 입력 요청 (미구현)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientEventHandler {

    private final ChatModel chatModel;

    // ─────────────────────────────────────────────────────────────────────────
    // @McpLogging — MCP 서버 로그 알림 수신
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MCP 서버에서 전송한 로그 메시지를 SLF4J로 라우팅합니다.
     *
     * MCP LoggingLevel → SLF4J 매핑:
     *   DEBUG              → log.debug
     *   INFO / NOTICE      → log.info
     *   WARNING            → log.warn
     *   ERROR / CRITICAL / ALERT / EMERGENCY → log.error
     *
     * 서버 측에서 McpAsyncServerExchange.loggingNotification() 또는
     * @McpTool 메서드 내 context.info() / context.warning() 등으로 전송됩니다.
     */
    @McpLogging(clients = "mcp-server")
    public void handleMcpLogging(McpSchema.LoggingMessageNotification notification) {
        String loggerName = notification.logger() != null ? notification.logger() : "mcp-server";
        String data = notification.data() != null ? notification.data() : "";

        switch (notification.level()) {
            case DEBUG ->
                log.debug("[MCP:{}] {}", loggerName, data);
            case INFO, NOTICE ->
                log.info("[MCP:{}] {}", loggerName, data);
            case WARNING ->
                log.warn("[MCP:{}] {}", loggerName, data);
            case ERROR, CRITICAL, ALERT, EMERGENCY ->
                log.error("[MCP:{}] {}", loggerName, data);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @McpToolListChanged — 서버 도구 목록 변경 감지
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MCP 서버의 도구 목록이 변경되었을 때 호출됩니다.
     *
     * 발생 시점: 서버 재기동, 도구 동적 추가/제거
     * AsyncMcpToolCallbackProvider는 자동으로 목록을 갱신하며,
     * 이 핸들러는 변경 내역을 로그로 기록합니다.
     */
    @McpToolListChanged(clients = "mcp-server")
    public void handleToolListChanged(List<McpSchema.Tool> updatedTools) {
        log.info("[MCP] 도구 목록 변경됨 — 총 {}개", updatedTools.size());
        updatedTools.forEach(tool ->
            log.debug("[MCP] 도구: {} — {}", tool.name(), tool.description())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @McpResourceListChanged — 서버 리소스 목록 변경 감지
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MCP 서버의 리소스 목록이 변경되었을 때 호출됩니다.
     *
     * 발생 시점: 새 문서 인덱싱 완료 (resource://documents/index 내용 변경)
     * 활용: 문서가 새로 추가됐음을 클라이언트 측에서 감지하여 UI 갱신 등에 활용 가능
     */
    @McpResourceListChanged(clients = "mcp-server")
    public void handleResourceListChanged(List<McpSchema.Resource> updatedResources) {
        log.info("[MCP] 리소스 목록 변경됨 — 총 {}개", updatedResources.size());
        updatedResources.forEach(resource ->
            log.debug("[MCP] 리소스: {} ({})", resource.name(), resource.uri())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @McpPromptListChanged — 서버 프롬프트 목록 변경 감지
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MCP 서버의 프롬프트 목록이 변경되었을 때 호출됩니다.
     *
     * 발생 시점: 서버의 @McpPrompt 등록 변경
     * 활용: 새로운 시스템 프롬프트가 등록됐음을 감지하여 클라이언트 UI 갱신 등에 활용 가능
     */
    @McpPromptListChanged(clients = "mcp-server")
    public void handlePromptListChanged(List<McpSchema.Prompt> updatedPrompts) {
        log.info("[MCP] 프롬프트 목록 변경됨 — 총 {}개", updatedPrompts.size());
        updatedPrompts.forEach(prompt ->
            log.debug("[MCP] 프롬프트: {} — {}", prompt.name(), prompt.description())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // @McpSampling — 서버의 LLM 샘플링 요청을 Ollama로 위임
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MCP 서버가 LLM 추론을 클라이언트에게 위임할 때 호출됩니다.
     *
     * MCP Sampling 개념:
     *   서버가 직접 LLM을 갖지 않아도 클라이언트의 LLM을 활용할 수 있습니다.
     *   서버에서 LLM 응답이 필요한 시점에 CreateMessageRequest를 전송하면,
     *   이 핸들러가 Ollama를 호출하고 결과를 서버에 반환합니다.
     *
     * 처리 흐름:
     *   CreateMessageRequest (MCP) → Spring AI Prompt 변환
     *     → ChatModel(Ollama) 호출 (boundedElastic 스케줄러)
     *     → 응답 텍스트 → CreateMessageResult (MCP) 반환
     *
     * `uploadAndIndexDocument` 도구 처리 중 서버가 문서 요약을 위해 샘플링을 요청합니다.
     * 서버가 ctx.sampleEnabled() / ctx.sample() 을 호출하면 이 핸들러가 실행됩니다.
     */
    @McpSampling(clients = "mcp-server")
    public Mono<McpSchema.CreateMessageResult> handleSampling(McpSchema.CreateMessageRequest request) {
        return Mono.fromCallable(() -> {
            log.info("[MCP] 샘플링 요청 수신 — 메시지 수: {}, maxTokens: {}",
                    request.messages().size(), request.maxTokens());

            List<Message> messages = buildSpringAiMessages(request);

            String responseText = chatModel.call(new Prompt(messages))
                    .getResult()
                    .getOutput()
                    .getText();

            log.debug("[MCP] 샘플링 응답 생성 완료 — 길이: {}자", responseText.length());

            return McpSchema.CreateMessageResult.builder()
                    .role(McpSchema.Role.ASSISTANT)
                    .content(new McpSchema.TextContent(responseText))
                    .model("ollama")
                    .stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * MCP CreateMessageRequest를 Spring AI Prompt용 메시지 목록으로 변환합니다.
     *
     * 변환 규칙:
     *   1. systemPrompt가 있으면 SystemMessage로 맨 앞에 추가
     *   2. SamplingMessage.role == USER      → UserMessage
     *   3. SamplingMessage.role == ASSISTANT → AssistantMessage
     *   4. Content가 TextContent가 아니면 "[비텍스트 콘텐츠]" 플레이스홀더 사용
     */
    private List<Message> buildSpringAiMessages(McpSchema.CreateMessageRequest request) {
        List<Message> messages = new ArrayList<>();

        if (StringUtils.hasText(request.systemPrompt())) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }

        for (McpSchema.SamplingMessage samplingMsg : request.messages()) {
            String text = extractText(samplingMsg.content());
            messages.add(switch (samplingMsg.role()) {
                case USER -> new UserMessage(text);
                case ASSISTANT -> new AssistantMessage(text);
            });
        }

        return messages;
    }

    /**
     * MCP Content에서 텍스트를 추출합니다.
     * TextContent 이외의 콘텐츠 타입(이미지 등)은 플레이스홀더로 대체합니다.
     */
    private String extractText(McpSchema.Content content) {
        if (content instanceof McpSchema.TextContent textContent) {
            return textContent.text() != null ? textContent.text() : "";
        }
        return "[비텍스트 콘텐츠]";
    }
}
