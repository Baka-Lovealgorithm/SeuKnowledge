package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * Agent 配置响应。
 */
public record AgentResponse(Long id, Long kbId, String name, String description, String systemPrompt,
                            Double verifyThreshold, Integer maxRetry, Integer memoryWindow,
                            LocalDateTime createdAt) {
}
