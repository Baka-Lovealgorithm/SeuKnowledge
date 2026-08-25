package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.Size;

/**
 * Agent 配置请求。update 时 null 字段保留原值。
 */
public record AgentRequest(
        @Size(max = 128, message = "名称最长 128 字符")
        String name,

        @Size(max = 500, message = "描述最长 500 字符")
        String description,

        String systemPrompt,

        Integer topK,
        Integer topN,
        Double verifyThreshold,
        Integer maxRetry,
        Integer memoryWindow,
        Double chunkWeight,
        Double businessWeight,
        Double qaWeight) {
}
