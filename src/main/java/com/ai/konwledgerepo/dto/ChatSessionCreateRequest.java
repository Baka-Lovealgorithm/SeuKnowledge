package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 新建会话请求。
 */
public record ChatSessionCreateRequest(@NotNull(message = "知识库不能为空") Long kbId) {
}
