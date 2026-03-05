package com.example.client.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅 세션 엔티티
 */
@Entity
@Table(name = "spring_ai_chat_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionEntity {

    @Id
    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "title", length = 500, nullable = false)
    private String title = "새 채팅";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastMessageAt == null) {
            lastMessageAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastMessageAt = LocalDateTime.now();
    }
}
