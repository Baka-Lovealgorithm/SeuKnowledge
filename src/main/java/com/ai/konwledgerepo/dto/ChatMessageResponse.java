package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.ChatMessage;

import java.time.LocalDateTime;

/**
 * 会话消息响应。
 */
public record ChatMessageResponse(Long id, String role, String content, String refs, LocalDateTime createdAt,
                                   Boolean interrupted) {

    public static ChatMessageResponse from(ChatMessage m) {
        return new ChatMessageResponse(m.getId(), m.getRole(), m.getContent(), m.getRefs(), m.getCreatedAt(),
                m.getInterrupted());
    }
}
