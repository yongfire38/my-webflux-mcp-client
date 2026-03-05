package com.example.client.service;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * 세션별 대화 기능을 제공하는 채팅 서비스 인터페이스
 */
public interface ChatService {

    /**
     * RAG 기반 스트리밍 응답 생성 (MCP 도구 포함)
     */
    Flux<ChatResponse> streamRagResponse(String message, String model);

    /**
     * 일반 스트리밍 응답 생성 (MCP 도구 미포함)
     */
    Flux<ChatResponse> streamSimpleResponse(String message, String model);
}
