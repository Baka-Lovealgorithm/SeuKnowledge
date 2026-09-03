package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 知识库访问组响应（含成员数，前端展示用）。
 */
public record GroupResponse(Long id, String name, String description, Long memberCount,
                            LocalDateTime createdAt) {
}