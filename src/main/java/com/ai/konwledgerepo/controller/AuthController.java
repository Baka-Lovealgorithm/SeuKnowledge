package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.LoginRequest;
import com.ai.konwledgerepo.dto.LoginResponse;
import com.ai.konwledgerepo.security.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录 / 当前用户 / 登出。
 * 业务逻辑见 {@link AuthService}，本类仅做参数转发。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<LoginResponse> me(@RequestAttribute("userId") Long userId,
                                         @RequestAttribute(value = "workspaceId", required = false) Long workspaceId) {
        return ApiResponse.ok(authService.me(userId, workspaceId));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
        return ApiResponse.ok();
    }
}
