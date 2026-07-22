package com.example.client.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthProxyController {

    private final WebClient serverWebClient;

    @PostMapping("/login")
    public Mono<ResponseEntity<String>> login(@RequestBody String body) {
        return serverWebClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }

    @PostMapping("/register")
    public Mono<ResponseEntity<String>> register(@RequestBody String body) {
        return serverWebClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }
}
