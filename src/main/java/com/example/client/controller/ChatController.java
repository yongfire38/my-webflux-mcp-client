package com.example.client.controller;

import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.client.context.SessionContext;
import com.example.client.service.ChatService;
import com.example.client.service.ChatSessionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;

    /**
     * RAG 기반 스트리밍 응답 생성 (MCP 도구 포함)
     */
    @GetMapping("/rag/stream")
    public Flux<ChatResponse> streamRagResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("RAG 기반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        setupSessionContext(sessionId, message);

        return chatService.streamRagResponse(message, model)
                .doFinally(signalType -> {
                    SessionContext.clear();
                    log.debug("SessionContext 정리 완료 - 세션: {}, 신호: {}", sessionId, signalType);
                });
    }

    /**
     * 일반 스트리밍 응답 생성 (MCP 도구 미포함)
     */
    @GetMapping("/simple/stream")
    public Flux<ChatResponse> streamSimpleResponse(
            @RequestParam(value = "message", defaultValue = "Tell me about this document") String message,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.info("일반 스트리밍 질의 수신: {}, 모델: {}, 세션: {}", message, model, sessionId);

        setupSessionContext(sessionId, message);

        return chatService.streamSimpleResponse(message, model)
                .doFinally(signalType -> {
                    SessionContext.clear();
                    log.debug("SessionContext 정리 완료 - 세션: {}, 신호: {}", sessionId, signalType);
                });
    }

    /**
     * 세션 컨텍스트를 설정하고 첫 메시지면 제목을 자동 생성합니다.
     */
    private void setupSessionContext(String sessionId, String message) {
        if (sessionId != null && !sessionId.isEmpty()) {
            if (chatSessionService.sessionExists(sessionId)) {
                SessionContext.setCurrentSessionId(sessionId);

                // 첫 메시지인 경우 세션 제목 업데이트
                List<Message> history = chatSessionService.getSessionMessages(sessionId);
                if (history.isEmpty()) {
                    String title = chatSessionService.generateSessionTitle(message);
                    chatSessionService.updateSessionTitle(sessionId, title);
                } else {
                    chatSessionService.updateLastMessageTime(sessionId);
                }
            } else {
                log.warn("존재하지 않는 세션 ID: {}, 기본 세션으로 처리", sessionId);
                SessionContext.setCurrentSessionId(ChatMemory.DEFAULT_CONVERSATION_ID);
            }
        } else {
            log.warn("세션 ID가 제공되지 않음, 기본 세션으로 처리");
            SessionContext.setCurrentSessionId(ChatMemory.DEFAULT_CONVERSATION_ID);
        }
    }
}
