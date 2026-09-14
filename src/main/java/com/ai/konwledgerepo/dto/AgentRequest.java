package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Agent 配置请求。update 时 null 字段保留原值。
 * 记忆策略两个字段的大小关系（summaryIntervalRounds ≤ recentRounds）由 AgentService 校验，
 * 违反会抛 BizException——单字段范围用注解，跨字段关系注解表达不了。
 */
public record AgentRequest(
        @Size(max = 128, message = "名称最长 128 字符")
        String name,

        @Size(max = 500, message = "描述最长 500 字符")
        String description,

        String systemPrompt,

        Double verifyThreshold,
        Integer maxRetry,

        @Min(value = 1, message = "最近对话轮数至少 1")
        @Max(value = 10, message = "最近对话轮数最多 10")
        Integer recentRounds,

        @Min(value = 1, message = "压缩间隔轮数至少 1")
        @Max(value = 10, message = "压缩间隔轮数最多 10")
        Integer summaryIntervalRounds) {
}
