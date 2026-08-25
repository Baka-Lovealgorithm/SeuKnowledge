package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提问请求。
 */
public record AskRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题最长 2000 字符")
        String question) {
}
