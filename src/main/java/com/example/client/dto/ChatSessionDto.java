package com.example.client.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 채팅 세션 정보를 담는 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDto {
    private String sessionId;
    private String title;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastMessageAt;

    /**
     * 3-parameter constructor for creating new sessions
     */
    public ChatSessionDto(String sessionId, String title, LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = createdAt;
        this.lastMessageAt = createdAt;
    }
}
