package com.ai.konwledgerepo.dto;

/**
 * 用户所属工作空间摘要（登录/当前用户接口返回，供前端空间切换器展示）。
 */
public record WorkspaceInfo(Long id, String name, String role) {
}
