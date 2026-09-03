package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 知识库授权记录响应（含被授权对象名称，前端展示用）。
 * granteeType：USER（用户名）或 GROUP（组名）；granteeName 为对应名称。
 */
public record KbAccessResponse(Long id, Long kbId, String granteeType, Long granteeId,
                               String granteeName, String permission, LocalDateTime createdAt) {
}