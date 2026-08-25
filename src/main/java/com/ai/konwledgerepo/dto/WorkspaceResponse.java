package com.ai.konwledgerepo.dto;

/**
 * 工作空间响应（当前空间信息：名称/角色/成员数）。
 */
public record WorkspaceResponse(Long id, String name, String role, Long memberCount) {
}
