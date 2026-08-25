package com.ai.konwledgerepo.dto;

import java.util.List;

/**
 * 登录响应：token + 用户信息 + 当前工作空间上下文 + 所属工作空间列表（供前端切换）。
 */
public record LoginResponse(String token, Long userId, String username, String role, Long workspaceId,
                            List<WorkspaceInfo> workspaces) {
}
