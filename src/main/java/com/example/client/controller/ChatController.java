package com.example.client.controller;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
     * @deprecated 서버 상태 패널 + 단일 탭 방식으로 전환. /api/chat/simple/stream 을 사용.
     */
    @Deprecated
    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> streamRagResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("RAG 기반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        return resolveSessionId(sessionId, message)
                .flatMapMany(resolvedId -> chatService.streamRagResponse(message, model, resolvedId))
                .onErrorResume(IllegalArgumentException.class,
                        e -> Flux.just(errorResponse(e.getMessage())));
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
                .flatMapMany(resolvedId -> chatService.streamSimpleResponse(message, model, resolvedId))
                .onErrorResume(IllegalArgumentException.class,
                        e -> Flux.just(errorResponse(e.getMessage())));
    }

    /**
     * 세션 ID를 확인하고 첫 메시지면 제목을 자동 생성합니다.
     * 블로킹 JPA 호출이 있으므로 boundedElastic 스케줄러에서 실행합니다.
     *
     * Spring AI 1.1.7부터 ChatMemory.CONVERSATION_ID는 advisor param 키 이름일 뿐
     * 기본 대화 ID 값이 아니므로(공식 문서: "There is no default conversation ID"),
     * 유효하지 않은 sessionId는 폴백하지 않고 명시적으로 오류를 발생시킨다.
     *
     * @return 실제 사용할 세션 ID (Mono), 유효하지 않으면 IllegalArgumentException
     */
    private Mono<String> resolveSessionId(String sessionId, String message) {
        return Mono.fromCallable(() -> {
                    if (sessionId == null || sessionId.isEmpty()) {
                        throw new IllegalArgumentException("세션 ID가 제공되지 않았습니다. 새 세션을 먼저 생성하세요.");
                    }

                    if (!chatSessionService.sessionExists(sessionId)) {
                        throw new IllegalArgumentException("존재하지 않는 세션입니다: " + sessionId);
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

    private ChatResponse errorResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
