package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 知识库响应。
 */
public record KbResponse(Long id, String name, String description, String status, boolean archived,
                         long documentCount, LocalDateTime createdAt) {
}
