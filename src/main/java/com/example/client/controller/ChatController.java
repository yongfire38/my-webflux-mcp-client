package com.example.client.controller;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.client.service.ChatService;
import com.example.client.service.ChatSessionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;

    /**
     * RAG 기반 스트리밍 응답 생성 (searchDocuments 강제 선호출 + 전체 MCP 도구 제공)
     */
    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> streamRagResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("RAG 기반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return resolveSessionId(sessionId, message)
                .flatMapMany(resolvedId -> chatService.streamRagResponse(message, model, resolvedId));
    }

    /**
     * 일반 스트리밍 응답 생성 (전체 MCP 도구 제공, LLM이 호출 여부 자율 판단)
     */
    @GetMapping(value = "/simple/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> streamSimpleResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("일반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return resolveSessionId(sessionId, message)
                .flatMapMany(resolvedId -> chatService.streamSimpleResponse(message, model, resolvedId));
    }

    /**
     * 세션 ID를 확인하고 첫 메시지면 제목을 자동 생성합니다.
     * 블로킹 JPA 호출이 있으므로 boundedElastic 스케줄러에서 실행합니다.
     *
     * @return 실제 사용할 세션 ID (Mono)
     */
    private Mono<String> resolveSessionId(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    if (sessionId == null || sessionId.isEmpty()) {
                        log.warn("세션 ID가 제공되지 않음, 기본 세션으로 처리");
                        return ChatMemory.DEFAULT_CONVERSATION_ID;
                    }

                    if (!chatSessionService.sessionExists(sessionId)) {
                        log.warn("존재하지 않는 세션 ID: {}, 기본 세션으로 처리", sessionId);
                        return ChatMemory.DEFAULT_CONVERSATION_ID;
                    }

                    // 첫 메시지인 경우 세션 제목 업데이트
                    if (chatSessionService.getSessionMessages(sessionId).isEmpty()) {
                        String title = chatSessionService.generateSessionTitle(message);
                        chatSessionService.updateSessionTitle(sessionId, title);
                    } else {
                        chatSessionService.updateLastMessageTime(sessionId);
                    }

                    return sessionId;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
