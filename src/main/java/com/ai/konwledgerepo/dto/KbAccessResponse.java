package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 知识库授权记录响应（含用户名，前端展示用）。
 */
public record KbAccessResponse(Long id, Long kbId, Long userId, String username, String permission,
                               LocalDateTime createdAt) {
}
