package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 组成员响应（含用户名，前端展示用）。
 */
public record GroupMemberResponse(Long userId, String username, LocalDateTime createdAt) {
}