package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 提示词模板响应（管理页展示）。
 */
public record PromptTemplateResponse(String key, String scopeType, Long scopeId, String content,
                                     Integer version, Long updatedBy,
                                     LocalDateTime createdAt, LocalDateTime updatedAt) {
}
