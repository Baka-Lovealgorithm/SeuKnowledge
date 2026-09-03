package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建/改名组请求。
 */
public record GroupRequest(
        @NotBlank(message = "组名不能为空")
        @Size(max = 64, message = "组名最长 64 字符")
        String name,

        @Size(max = 200, message = "描述最长 200 字符")
        String description) {
}