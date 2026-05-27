package com.example.client.controller;

import com.example.client.dto.ChatMessageDto;
import com.example.client.dto.ChatSessionDto;
import com.example.client.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatSessionController {

    private final ChatSessionService chatSessionService;

    @PostMapping
    public Mono<ResponseEntity<ChatSessionDto>> createNewSession() {
        return Mono.fromCallable(() -> {
                    ChatSessionDto session = chatSessionService.createNewSession();
                    log.info("새 채팅 세션 생성됨: {}", session.getSessionId());
                    return ResponseEntity.ok(session);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("세션 생성 실패", e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    @GetMapping
    public Mono<ResponseEntity<List<ChatSessionDto>>> getAllSessions() {
        return Mono.fromCallable(() -> {
                    List<ChatSessionDto> sessions = chatSessionService.getAllSessions();
                    log.debug("세션 목록 조회: {} 개", sessions.size());
                    return ResponseEntity.ok(sessions);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("세션 목록 조회 실패", e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    @GetMapping("/{sessionId}")
    public Mono<ResponseEntity<ChatSessionDto>> getSession(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
                    if (!chatSessionService.sessionExists(sessionId)) {
                        log.warn("존재하지 않는 세션 ID: {}", sessionId);
                        return new ResponseEntity<ChatSessionDto>(HttpStatus.NOT_FOUND);
                    }
                    ChatSessionDto session = chatSessionService.getSession(sessionId);
                    log.debug("세션 조회: {}", sessionId);
                    return ResponseEntity.ok(session);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("세션 조회 실패: {}", sessionId, e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<List<ChatMessageDto>>> getSessionMessages(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
                    if (!chatSessionService.sessionExists(sessionId)) {
                        log.warn("존재하지 않는 세션 ID: {}", sessionId);
                        return new ResponseEntity<List<ChatMessageDto>>(HttpStatus.NOT_FOUND);
                    }

                    List<Message> messages = chatSessionService.getSessionMessages(sessionId);
                    log.debug("세션 {} 메시지 조회 결과: {} 개의 메시지", sessionId, messages.size());

                    List<ChatMessageDto> messageDtos = messages.stream()
                            .map(message -> {
                                String messageTypeStr = message.getMessageType().name();
                                String content;

                                if (message instanceof UserMessage) {
                                    content = ((UserMessage) message).getText();
                                } else if (message instanceof AssistantMessage) {
                                    content = ((AssistantMessage) message).getText();
                                } else if (message instanceof SystemMessage) {
                                    content = ((SystemMessage) message).getText();
                                } else {
                                    try {
                                        Method getTextMethod = message.getClass().getMethod("getText");
                                        content = (String) getTextMethod.invoke(message);
                                    } catch (Exception e) {
                                        content = "메시지 내용을 가져올 수 없습니다";
                                    }
                                }

                                return new ChatMessageDto(messageTypeStr, content);
                            })
                            .collect(Collectors.toList());

                    return ResponseEntity.ok(messageDtos);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("세션 메시지 조회 실패: {}", sessionId, e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    @PutMapping("/{sessionId}/title")
    public Mono<ResponseEntity<Void>> updateSessionTitle(
            @PathVariable String sessionId,
            @RequestBody UpdateTitleRequest request) {
        return Mono.fromCallable(() -> {
                    if (!chatSessionService.sessionExists(sessionId)) {
                        log.warn("존재하지 않는 세션 ID: {}", sessionId);
                        return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
                    }
                    chatSessionService.updateSessionTitle(sessionId, request.getTitle());
                    log.info("세션 제목 업데이트: {} -> {}", sessionId, request.getTitle());
                    return new ResponseEntity<Void>(HttpStatus.OK);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("세션 제목 업데이트 실패: {}", sessionId, e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    @DeleteMapping("/{sessionId}")
    public Mono<ResponseEntity<Void>> deleteSession(@PathVariable String sessionId) {
        return Mono.fromCallable(() -> {
                    if (!chatSessionService.sessionExists(sessionId)) {
                        log.warn("존재하지 않는 세션 ID: {}", sessionId);
                        return new ResponseEntity<Void>(HttpStatus.NOT_FOUND);
                    }
                    chatSessionService.deleteSession(sessionId);
                    log.info("세션 삭제: {}", sessionId);
                    return new ResponseEntity<Void>(HttpStatus.OK);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("세션 삭제 실패: {}", sessionId, e);
                    return Mono.just(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                });
    }

    public static class UpdateTitleRequest {
        private String title;

        public UpdateTitleRequest() {}

        public UpdateTitleRequest(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}
