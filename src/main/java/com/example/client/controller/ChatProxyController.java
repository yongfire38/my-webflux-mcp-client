package com.example.client.controller;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatProxyController {

    private final WebClient serverWebClient;

    /**
     * SSE 스트림을 서버에 중계합니다.
     *
     * EventSource는 헤더를 설정할 수 없으므로 JWT를 query param으로 수신합니다.
     * 서버 요청 시 Authorization 헤더로 변환하여 전달합니다.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestParam(required = false) String token) {

        String authHeader = (token != null && !token.isBlank()) ? "Bearer " + token : "";

        // server의 POST /api/chat 을 호출하여 SSE 스트림을 중계
        return serverWebClient.post()
                .uri("/api/chat")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionId", sessionId, "message", message))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnError(e -> log.warn("[채팅 중계] 오류: {}", e.getMessage()));
    }
}
