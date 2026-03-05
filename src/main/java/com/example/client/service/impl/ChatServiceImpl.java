package com.example.client.service.impl;

import java.util.Arrays;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.example.client.context.SessionContext;
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

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final AsyncMcpToolCallbackProvider toolCallbackProvider;

    /**
     * RAG 탭: searchDocuments 강제 선호출 방식
     *
     * LLM에게 도구 호출 여부를 맡기지 않고, 클라이언트가 직접 searchDocuments를
     * 먼저 호출하여 결과를 system 메시지로 주입합니다.
     *
     * 흐름:
     *   1. MCP 서버에서 전체 도구 목록 로드
     *   2. searchDocuments(query) 강제 실행 → RAG 컨텍스트 확보
     *   3. RAG 컨텍스트를 system 메시지로 주입
     *   4. LLM 호출 (추가 도구 호출도 허용)
     */
    @Override
    public Flux<ChatResponse> streamRagResponse(String message, String model) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("RAG 스트리밍 질의 - 메시지: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return Mono.fromCallable(() -> {
                    // boundedElastic: getToolCallbacks()와 ToolCallback.call() 모두 블로킹
                    ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();

                    // searchDocuments 강제 선호출
                    String ragContext = Arrays.stream(callbacks)
                            .filter(cb -> "searchDocuments".equals(cb.getToolDefinition().name()))
                            .findFirst()
                            .map(cb -> {
                                String input = String.format("{\"query\":\"%s\"}",
                                        message.replace("\\", "\\\\").replace("\"", "\\\""));
                                log.info("RAG 선호출 실행 - 질의: {}", message);
                                return cb.call(input);
                            })
                            .orElse("관련 문서를 찾을 수 없습니다.");

                    return new RagResult(callbacks, ragContext);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> {

                    // RAG 컨텍스트를 system 메시지로 주입
                    String systemPrompt = buildRagSystemPrompt(result.ragContext());

                    // LLM 호출: system(RAG 컨텍스트) + user(질문) + 도구(추가 호출 허용)
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
     * 예: "지금 서울 시간은?" → LLM이 getCurrentDateTimeWithZone 자동 호출
     *
     * 기존(도구 없음) → 변경(도구 제공, LLM 판단)
     */
    @Override
    public Flux<ChatResponse> streamSimpleResponse(String message, String model) {
        String sessionId = SessionContext.getCurrentSessionId();
        log.info("일반 스트리밍 질의 - 메시지: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return Mono.fromCallable(() -> toolCallbackProvider.getToolCallbacks())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(callbacks -> {
                    // 도구 제공 O, 강제 호출 X → LLM이 필요 시 자율 호출
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
