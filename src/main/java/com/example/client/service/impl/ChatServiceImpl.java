package com.example.client.service.impl;

import java.util.Arrays;
import java.util.Map;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.client.service.ChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl extends EgovAbstractServiceImpl implements ChatService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final AsyncMcpToolCallbackProvider toolCallbackProvider;
    private final RewriteQueryTransformer rewriteQueryTransformer;
    private final TranslationQueryTransformer translationQueryTransformer;

    @Value("${app.rag.query-transform.translation-enabled:false}")
    private boolean translationEnabled;

    @Value("${app.rag.query-transform.rewrite-enabled:false}")
    private boolean rewriteEnabled;

    /**
     * RAG 탭: searchDocuments 강제 선호출 방식
     *
     * 흐름:
     *   1. MCP 서버에서 전체 도구 목록 로드
     *   2. TranslationQueryTransformer → 쿼리를 한국어로 보장
     *   3. RewriteQueryTransformer → 벡터 검색에 적합한 형태로 재작성
     *   4. searchDocuments(변환된 쿼리) 강제 실행 → RAG 컨텍스트 확보
     *   5. RAG 컨텍스트를 system 메시지로 주입 후 LLM 호출
     */
    @Override
    public Flux<ChatResponse> streamRagResponse(String message, String model, String sessionId) {
        log.info("RAG 스트리밍 질의 - 메시지: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return Mono.fromCallable(() -> {
                    // boundedElastic: getToolCallbacks()와 ToolCallback.call() 모두 블로킹
                    ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();

                    log.info("MCP 도구 목록 ({} 개): {}",
                            callbacks.length,
                            Arrays.stream(callbacks)
                                    .map(cb -> cb.getToolDefinition().name())
                                    .toList());

                    // 쿼리 변환: 한국어 보장 → 벡터 검색 최적화 재작성
                    String searchQuery = transformQuery(message);

                    // searchDocuments 강제 선호출
                    String ragContext = Arrays.stream(callbacks)
                            .filter(cb -> "searchDocuments".equals(cb.getToolDefinition().name()))
                            .findFirst()
                            .map(cb -> {
                                try {
                                    String input = OBJECT_MAPPER.writeValueAsString(Map.of("query", searchQuery));
                                    log.info("RAG 선호출 실행 - 원본: {}, 변환: {}", message, searchQuery);
                                    return cb.call(input);
                                } catch (JsonProcessingException e) {
                                    log.error("RAG 선호출 JSON 직렬화 실패: {}", e.getMessage());
                                    return "문서 검색 중 오류가 발생했습니다.";
                                }
                            })
                            .orElse("관련 문서를 찾을 수 없습니다.");

                    return new RagResult(callbacks, ragContext);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    String systemPrompt = buildRagSystemPrompt(result.ragContext());

                    // LLM 호출: system(RAG 컨텍스트) + user(원본 질문) + 도구(추가 호출 허용)
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

    /**
     * 일반 탭: 도구 제공 + LLM이 호출 여부 판단
     *
     * 모든 MCP 도구를 LLM에게 제공하되, 호출 여부는 LLM이 스스로 판단합니다.
     */
    @Override
    public Flux<ChatResponse> streamSimpleResponse(String message, String model, String sessionId) {
        log.info("일반 스트리밍 질의 - 메시지: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return Mono.fromCallable(() -> toolCallbackProvider.getToolCallbacks())
                .subscribeOn(Schedulers.boundedElastic())
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

    /**
     * RAG 검색 전 쿼리를 변환합니다.
     *   1. TranslationQueryTransformer: 한국어 이외 쿼리를 한국어로 번역 (이미 한국어면 그대로 반환)
     *   2. RewriteQueryTransformer: 벡터 유사도 검색에 적합한 형태로 재작성
     * 변환 실패 시 원본 메시지를 그대로 사용합니다.
     */
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

    // RAG 선호출 결과를 담는 레코드 (Java 16+)
    private record RagResult(ToolCallback[] callbacks, String ragContext) {}
}
