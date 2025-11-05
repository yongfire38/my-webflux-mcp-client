package com.example.client.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;
    private final AsyncMcpToolCallbackProvider toolCallbackProvider;

    public Mono<String> chat(String userMessage) {
        log.info("User message: {}", userMessage);

        return Mono.fromCallable(() -> {
            var toolCallbacks = toolCallbackProvider.getToolCallbacks();

            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .build();

            Prompt prompt = new Prompt(userMessage, options);
            ChatResponse response = chatModel.call(prompt);
            String answer = response.getResult().getOutput().getText();

            log.info("AI response: {}", answer);
            return answer.replaceAll("<think>.*?</think>\\s*", "");
        })
        .subscribeOn(Schedulers.boundedElastic())  // 블로킹 작업을 별도 스레드 풀에서 실행
        .doOnError(e -> log.error("Error during chat", e));
    }
}
