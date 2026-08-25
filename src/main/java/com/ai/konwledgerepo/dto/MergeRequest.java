package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 合并请求：将来源记录并入 targetId。
 */
public record MergeRequest(@NotNull(message = "目标 id 不能为空") Long targetId) {
}
