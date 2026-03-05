package com.example.client.service;

import com.example.client.dto.ChatSessionDto;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 채팅 세션 관리 서비스 인터페이스
 */
public interface ChatSessionService {

    ChatSessionDto createNewSession();

    List<ChatSessionDto> getAllSessions();

    ChatSessionDto getSession(String sessionId);

    void deleteSession(String sessionId);

    boolean sessionExists(String sessionId);

    List<Message> getSessionMessages(String sessionId);

    void updateSessionTitle(String sessionId, String title);

    String generateSessionTitle(String firstMessage);

    void updateLastMessageTime(String sessionId);
}
