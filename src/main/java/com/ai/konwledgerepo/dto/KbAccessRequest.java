package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 知识库授权请求：为指定用户授予 VIEW / EDIT 权限。
 */
public record KbAccessRequest(
        @NotNull(message = "用户 id 不能为空")
        Long granteeId,

        @Pattern(regexp = "VIEW|EDIT", message = "权限只能是 VIEW / EDIT")
        String permission) {
}
