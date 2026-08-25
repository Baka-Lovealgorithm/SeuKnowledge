package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话重命名请求。
 */
public record RenameRequest(
        @NotBlank(message = "会话标题不能为空")
        @Size(max = 255, message = "标题最长 255 字符")
        String title) {
}
