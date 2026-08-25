package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 工作空间成员响应。
 */
public record MemberResponse(Long id, Long userId, String username, String role, LocalDateTime createdAt) {
}
