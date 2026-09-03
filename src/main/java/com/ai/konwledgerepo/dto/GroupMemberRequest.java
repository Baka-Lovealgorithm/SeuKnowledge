package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 向组添加成员请求。
 */
public record GroupMemberRequest(
        @NotNull(message = "用户 id 不能为空")
        Long userId) {
}