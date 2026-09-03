package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 提示词渲染预览请求：传入模板变量（{{name}} → 值），返回渲染后文本。
 * 用于管理页「改提示词前先看最终效果」。
 */
public record PromptPreviewRequest(
        @NotBlank(message = "模板 key 不能为空")
        String key,
        Map<String, String> variables) {
}
