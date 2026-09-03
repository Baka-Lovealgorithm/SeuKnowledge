package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 知识库授权请求：为指定用户（granteeType=USER）或组（granteeType=GROUP）授予 VIEW / EDIT 权限。
 */
public record KbAccessRequest(
        @Pattern(regexp = "USER|GROUP", message = "授权对象类型只能是 USER / GROUP")
        String granteeType,

        @NotNull(message = "授权对象 id 不能为空")
        Long granteeId,

        @Pattern(regexp = "VIEW|EDIT", message = "权限只能是 VIEW / EDIT")
        String permission) {
}