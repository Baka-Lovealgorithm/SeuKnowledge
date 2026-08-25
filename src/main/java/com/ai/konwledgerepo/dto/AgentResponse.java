package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * Agent 配置响应。
 */
public record AgentResponse(Long id, Long kbId, String name, String description, String systemPrompt,
                            Integer topK, Integer topN, Double verifyThreshold, Integer maxRetry,
                            Integer memoryWindow, Double chunkWeight, Double businessWeight, Double qaWeight,
                            LocalDateTime createdAt) {
}
