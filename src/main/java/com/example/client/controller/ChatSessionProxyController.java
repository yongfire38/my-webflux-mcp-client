package com.example.client.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class ChatSessionProxyController {

    private final WebClient serverWebClient;

    @PostMapping
    public Mono<ResponseEntity<String>> createSession(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.post()
                .uri("/api/sessions")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }

    @GetMapping
    public Mono<ResponseEntity<String>> listSessions(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.get()
                .uri("/api/sessions")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }

    @DeleteMapping("/{sessionId}")
    public Mono<ResponseEntity<Void>> deleteSession(
            @PathVariable String sessionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.delete()
                .uri("/api/sessions/{id}", sessionId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.<Void>status(e.getStatusCode()).build()));
    }

    @PatchMapping("/{sessionId}/title")
    public Mono<ResponseEntity<Void>> updateTitle(
            @PathVariable String sessionId,
            @RequestBody String body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.patch()
                .uri("/api/sessions/{id}/title", sessionId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.<Void>status(e.getStatusCode()).build()));
    }

    @GetMapping("/{sessionId}/messages")
    public Mono<ResponseEntity<String>> getMessages(
            @PathVariable String sessionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.get()
                .uri("/api/sessions/{id}/messages", sessionId)
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }
}
