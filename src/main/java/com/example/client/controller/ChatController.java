package com.example.client.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.client.service.ChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("Received chat request: {}", request.message());
        return chatService.chat(request.message())
              .map(ChatResponse::new);
    }

    @GetMapping
    public Mono<ChatResponse> chatByParam(@RequestParam String message) {
        log.info("Received chat request (GET): {}", message);
        return chatService.chat(message)
              .map(ChatResponse::new);
    }

    // Request DTO
    public record ChatRequest(String message) {}

    // Response DTO
    public record ChatResponse(String response) {}
}
