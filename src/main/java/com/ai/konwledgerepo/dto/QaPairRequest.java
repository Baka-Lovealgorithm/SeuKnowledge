package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答对请求。
 */
public record QaPairRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 500, message = "问题最长 500 字符")
        String question,

        @NotBlank(message = "答案不能为空")
        String answer,

        Long sourceDocId) {
}
