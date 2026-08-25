package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.ChatSession;

import java.time.LocalDateTime;

/**
 * 会话响应。
 */
public record ChatSessionResponse(Long id, Long kbId, String title, Integer messageCount,
                                  LocalDateTime createdAt, LocalDateTime lastMessageAt) {

    public static ChatSessionResponse from(ChatSession s) {
        return new ChatSessionResponse(s.getId(), s.getKbId(), s.getTitle(), s.getMessageCount(),
                s.getCreatedAt(), s.getLastMessageAt());
    }
}
