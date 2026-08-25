package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建知识库请求。
 */
public record KbCreateRequest(
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 128, message = "知识库名称最长 128 字符")
        String name,

        @Size(max = 500, message = "描述最长 500 字符")
        String description) {
}
