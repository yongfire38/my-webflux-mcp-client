package com.example.client.repository;

import com.example.client.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 채팅 세션 JPA Repository
 */
@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    @Query("SELECT s FROM ChatSessionEntity s ORDER BY s.lastMessageAt DESC")
    List<ChatSessionEntity> findAllOrderByLastMessageAtDesc();

    boolean existsBySessionId(String sessionId);
}
