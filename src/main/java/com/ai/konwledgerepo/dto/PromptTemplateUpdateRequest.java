package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 提示词模板更新请求。
 */
public record PromptTemplateUpdateRequest(
        @NotBlank(message = "模板内容不能为空")
        String content) {
}
