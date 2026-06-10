package com.example.client.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.client.config.McpClientHolder;
import com.example.client.service.ChatService;

import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl extends EgovAbstractServiceImpl implements ChatService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final McpClientHolder mcpClientHolder;
    private final RewriteQueryTransformer rewriteQueryTransformer;
    private final TranslationQueryTransformer translationQueryTransformer;

    @Value("${app.rag.query-transform.translation-enabled:false}")
    private boolean translationEnabled;

    @Value("${app.rag.query-transform.rewrite-enabled:false}")
    private boolean rewriteEnabled;

    /**
     * RAG 탭: searchDocuments 강제 선호출 방식.
     *
     * MCP 미연결 시 동작:
     *   - 쿨다운 만료: "재연결 시도 중..." 안내 → 재연결 시도 → 성공 시 RAG, 실패 시 오류
     *   - 쿨다운 중:   즉시 "서버 연결 필요" 오류 반환 (재연결 시도 없음)
     */
    @Override
    public Flux<ChatResponse> streamRagResponse(String message, String model, String sessionId) {
        log.info("RAG 스트리밍 질의 - 메시지: {}, 모델: {}, 세션: {}", message, model, sessionId);

        if (!mcpClientHolder.isConnected()) {
            if (!mcpClientHolder.canAttemptReconnect()) {
                log.warn("RAG: MCP 미연결 + 쿨다운 중 — 즉시 오류 반환");
                return Flux.just(singleMessageResponse(
                        "MCP 서버 연결이 없습니다. 잠시 후 다시 시도하거나 일반 채팅 탭을 이용하세요."));
            }

            log.info("RAG: MCP 미연결 — 재연결 시도 시작");
            return Flux.concat(
                    Flux.just(singleMessageResponse("MCP 서버 재연결을 시도합니다...")),
                    Mono.fromCallable(mcpClientHolder::tryReconnect)
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(connected -> {
                                if (!connected) {
                                    log.warn("RAG: 재연결 실패");
                                    return Mono.delay(Duration.ofMillis(800))
                                            .thenMany(Flux.just(singleMessageResponse(
                                                    "MCP 서버 연결에 실패했습니다. 서버를 먼저 기동하고 다시 시도하세요.\n"
                                                            + "일반 채팅 탭은 서버 없이도 사용할 수 있습니다.")));
                                }
                                log.info("RAG: 재연결 성공 — RAG 흐름 진행");
                                return executeRagFlow(message, model, sessionId);
                            }));
        }

        return executeRagFlow(message, model, sessionId);
    }

    /**
     * 일반 탭: 도구 제공 + LLM이 호출 여부 판단.
     *
     * MCP 미연결 시: getToolCallbacks()가 빈 배열 반환 → 도구 없이 Ollama 직접 호출 (정상 동작).
     * 재연결 시도 없음 — 사용자가 RAG 탭에서 먼저 재연결하거나 단순 질답은 그냥 진행.
     */
    @Override
    public Flux<ChatResponse> streamSimpleResponse(String message, String model, String sessionId) {
        log.info("일반 스트리밍 질의 - 메시지: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return Mono.fromCallable(() -> mcpClientHolder.getToolCallbacks())
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                        .filter(ChatServiceImpl::isSessionError)
                        .doBeforeRetry(s -> log.warn("일반: MCP 세션 만료 감지 — 재연결 후 재시도")))
                .flatMapMany(callbacks -> {
                    ChatClientRequestSpec requestSpec = chatClient.prompt()
                            .user(message)
                            .toolCallbacks(callbacks);

                    if (model != null && !model.trim().isEmpty()) {
                        requestSpec = requestSpec.options(ChatOptions.builder()
                                .model(model)
                                .temperature(0.3)
                                .build());
                    }

                    return requestSpec
                            .advisors(messageChatMemoryAdvisor)
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                            .stream()
                            .chatResponse();
                })
                .doOnError(e -> log.error("일반 스트리밍 오류 - 세션: {}", sessionId, e));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Flux<ChatResponse> executeRagFlow(String message, String model, String sessionId) {
        return Mono.fromCallable(() -> {
                    ToolCallback[] callbacks = mcpClientHolder.getToolCallbacks();

                    log.info("MCP 도구 목록 ({} 개): {}",
                            callbacks.length,
                            Arrays.stream(callbacks)
                                    .map(cb -> cb.getToolDefinition().name())
                                    .toList());

                    String searchQuery = transformQuery(message);

                    // Optional.empty() → searchDocuments 도구 없음 (MCP 미연결 또는 서버 미노출)
                    // Optional.of(value) → MCP 연결됨 (결과가 없어도 present)
                    Optional<String> ragContext = Arrays.stream(callbacks)
                            .filter(cb -> "searchDocuments".equals(cb.getToolDefinition().name()))
                            .findFirst()
                            .map(cb -> {
                                try {
                                    String input = OBJECT_MAPPER.writeValueAsString(
                                            Map.of("query", searchQuery));
                                    log.info("RAG 선호출 실행 - 원본: {}, 변환: {}", message, searchQuery);
                                    return cb.call(input);
                                } catch (JsonProcessingException e) {
                                    log.error("RAG 선호출 JSON 직렬화 실패: {}", e.getMessage());
                                    return "문서 검색 중 오류가 발생했습니다.";
                                }
                            });

                    return new RagResult(callbacks, ragContext);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                        .filter(ChatServiceImpl::isSessionError)
                        .doBeforeRetry(s -> log.warn("RAG: MCP 세션 만료 감지 — 재연결 후 재시도")))
                .flatMapMany(result -> {

                    if (result.ragContext().isEmpty()) {
                        log.warn("RAG: searchDocuments 도구 없음");
                        return Flux.just(singleMessageResponse(
                                "RAG 기능을 사용하려면 MCP 서버 연결이 필요합니다.\n"
                                        + "MCP 서버를 먼저 기동하고 다시 시도하세요.\n"
                                        + "연결이 불필요한 경우 일반 채팅 탭을 이용하세요."));
                    }

                    String systemPrompt = buildRagSystemPrompt(result.ragContext().get());

                    ChatClientRequestSpec requestSpec = chatClient.prompt()
                            .system(systemPrompt)
                            .user(message)
                            .toolCallbacks(result.callbacks());

                    if (model != null && !model.trim().isEmpty()) {
                        requestSpec = requestSpec.options(ChatOptions.builder()
                                .model(model)
                                .temperature(0.3)
                                .build());
                    }

                    return requestSpec
                            .advisors(messageChatMemoryAdvisor)
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                            .stream()
                            .chatResponse();
                })
                .doOnError(e -> log.error("RAG 스트리밍 오류 - 세션: {}", sessionId, e));
    }

    private String transformQuery(String message) {
        if (!translationEnabled && !rewriteEnabled) {
            return message;
        }
        try {
            Query query = new Query(message);
            if (translationEnabled) {
                query = translationQueryTransformer.transform(query);
            }
            if (rewriteEnabled) {
                query = rewriteQueryTransformer.transform(query);
            }
            log.debug("쿼리 변환 완료: [{}] → [{}]", message, query.text());
            return query.text();
        } catch (Exception e) {
            log.warn("쿼리 변환 실패, 원본 사용 — {}", e.getMessage());
            return message;
        }
    }

    private String buildRagSystemPrompt(String ragContext) {
        return """
                당신은 문서 기반 AI 어시스턴트입니다.
                아래 [검색된 문서]를 근거로 답변하세요.
                문서에 없는 내용은 "문서에서 찾을 수 없습니다"라고 안내하세요.

                [검색된 문서]
                %s
                """.formatted(ragContext);
    }

    private ChatResponse singleMessageResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static boolean isSessionError(Throwable e) {
        return e instanceof McpTransportSessionNotFoundException
                || (e.getMessage() != null && e.getMessage().contains("session"));
    }

    // ragContext: Optional.empty() = searchDocuments 도구 없음, Optional.of(value) = 도구 있음
    private record RagResult(ToolCallback[] callbacks, Optional<String> ragContext) {}
}
