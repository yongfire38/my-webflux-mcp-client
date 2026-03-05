package com.example.client.service.impl;

import com.example.client.dto.ChatSessionDto;
import com.example.client.entity.ChatSessionEntity;
import com.example.client.repository.ChatSessionRepository;
import com.example.client.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends EgovAbstractServiceImpl implements ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMemory chatMemory;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public ChatSessionDto createNewSession() {
        String sessionId = "session_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now();

        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setSessionId(sessionId);
        entity.setTitle("새 채팅");
        entity.setCreatedAt(now);
        entity.setLastMessageAt(now);

        chatSessionRepository.save(entity);

        log.debug("새 채팅 세션 생성: {}", sessionId);
        return new ChatSessionDto(sessionId, "새 채팅", now);
    }

    @Override
    public List<ChatSessionDto> getAllSessions() {
        List<ChatSessionEntity> entities = chatSessionRepository.findAllOrderByLastMessageAtDesc();

        return entities.stream()
                .map(entity -> new ChatSessionDto(
                        entity.getSessionId(),
                        entity.getTitle(),
                        entity.getCreatedAt(),
                        entity.getLastMessageAt()))
                .toList();
    }

    @Override
    public ChatSessionDto getSession(String sessionId) {
        return chatSessionRepository.findById(sessionId)
                .map(entity -> new ChatSessionDto(
                        entity.getSessionId(),
                        entity.getTitle(),
                        entity.getCreatedAt(),
                        entity.getLastMessageAt()))
                .orElse(null);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return chatSessionRepository.existsBySessionId(sessionId);
    }

    @Override
    public List<Message> getSessionMessages(String sessionId) {
        try {
            List<Message> all = chatMemory.get(sessionId);
            // UI에는 사용자/어시스턴트 메시지만 노출 (System 등 내부 메시지는 제외)
            return all.stream()
                    .filter(m -> (m instanceof UserMessage) || (m instanceof AssistantMessage))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("세션 {} 메시지 조회 실패: {}", sessionId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateSessionTitle(String sessionId, String title) {
        chatSessionRepository.findById(sessionId)
                .ifPresent(entity -> {
                    entity.setTitle(title);
                    entity.setLastMessageAt(LocalDateTime.now());
                    chatSessionRepository.save(entity);
                    log.debug("세션 제목 업데이트: {} -> {}", sessionId, title);
                });
    }

    @Override
    public void updateLastMessageTime(String sessionId) {
        chatSessionRepository.findById(sessionId)
                .ifPresent(entity -> {
                    entity.setLastMessageAt(LocalDateTime.now());
                    chatSessionRepository.save(entity);
                    log.debug("세션 마지막 메시지 시간 업데이트: {}", sessionId);
                });
    }

    @Override
    public String generateSessionTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.trim().isEmpty()) {
            return "새 채팅";
        }

        // 첫 메시지에서 제목 생성 (최대 30자)
        String title = firstMessage.trim();
        if (title.length() > 30) {
            title = title.substring(0, 27) + "...";
        }

        return title;
    }

    @Override
    public void deleteSession(String sessionId) {
        log.info("세션 삭제 시작: {}", sessionId);

        // 1. spring_ai_chat_memory 테이블에서 해당 세션의 모든 메시지 삭제
        String deleteMemorySql = "DELETE FROM spring_ai_chat_memory WHERE conversation_id = ?";
        int deletedMessages = jdbcTemplate.update(deleteMemorySql, sessionId);
        log.info("세션 {} 메시지 삭제 완료: {} 개", sessionId, deletedMessages);

        // 2. ChatMemory 캐시에서도 삭제 (선택적, 실패해도 무시)
        try {
            chatMemory.clear(sessionId);
            log.debug("ChatMemory 캐시에서 세션 {} 삭제 완료", sessionId);
        } catch (Exception e) {
            log.debug("ChatMemory 캐시 삭제 실패 (무시): {}", e.getMessage());
        }

        // 3. 세션 정보 삭제
        chatSessionRepository.deleteById(sessionId);
        log.info("세션 삭제 완료: {}", sessionId);
    }
}
