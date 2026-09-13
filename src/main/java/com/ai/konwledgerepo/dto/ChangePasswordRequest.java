package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 自助修改密码请求（对齐 Dify /account/password）：须提供当前旧密码，新密码需二次确认。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "当前密码不能为空") String password,
        @NotBlank(message = "新密码不能为空") String newPassword,
        @NotBlank(message = "确认密码不能为空") String confirmPassword) {
}
