package com.example.client.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class ChatClientConfig {

    @Autowired
    private JdbcChatMemoryRepository chatMemoryRepository;

    @Value("${app.rag.query-transform.translation-target-language:Korean}")
    private String translationTargetLanguage;

    @Bean
    public ChatMemory chatMemory() {
        log.info("JDBC 기반 ChatMemory 생성 (PostgreSQL 영속화, 최대 10개 메시지 유지)");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        log.info("ChatClient 구성: 기본 어드바이저 없이 생성 (세션별 동적 추가)");
        return ChatClient.builder(chatModel)
                .build();
    }

    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(ChatModel chatModel) {
        return RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .targetSearchSystem("vector similarity search over Korean documents")
                .build();
    }

    @Bean
    public TranslationQueryTransformer translationQueryTransformer(ChatModel chatModel) {
        return TranslationQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .targetLanguage(translationTargetLanguage)
                .build();
    }
}
