package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 模型配置请求。update 时 apiKey 为空保留原值。
 */
public record ModelConfigRequest(
        @NotBlank(message = "配置名称不能为空")
        @Size(max = 128)
        String name,

        @NotBlank(message = "供应商不能为空")
        @Pattern(regexp = "DASHSCOPE|OPENAI_COMPAT", message = "供应商仅支持 DASHSCOPE / OPENAI_COMPAT")
        String provider,

        @NotBlank(message = "模型类型不能为空")
        @Pattern(regexp = "CHAT|EMBEDDING|VISION|RERANK|TITLE", message = "模型类型仅支持 CHAT / EMBEDDING / VISION / RERANK / TITLE")
        String modelType,

        /** 按用途绑定：EXTRACT / GENERATE / RETRIEVE / VISION / RERANK / VERIFY / TITLE / ROUTER；为空表示通用 */
        @Pattern(regexp = "EXTRACT|GENERATE|RETRIEVE|VISION|RERANK|VERIFY|TITLE|ROUTER", message = "用途仅支持 EXTRACT / GENERATE / RETRIEVE / VISION / RERANK / VERIFY / TITLE / ROUTER")
        String usage,

        @NotBlank(message = "模型名不能为空")
        @Size(max = 128)
        String modelName,

        @Size(max = 255)
        String apiKey,

        @Size(max = 255)
        String baseUrl,

        BigDecimal temperature,
        Integer maxTokens,
        Boolean isDefault,
        Boolean enabled,

        /** 是否关闭思考（deepseek 系需 true，避免 reasoning 占满 max_tokens 触顶即空输出） */
        Boolean disableThinking,

        /** 关闭思考时传给模型的参数模板 JSON（空用默认 {"thinking":{"type":"disabled"}}） */
        @Size(max = 500)
        String thinkingParams) {
}
