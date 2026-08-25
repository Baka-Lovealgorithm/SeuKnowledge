package com.ai.konwledgerepo.dto;

/**
 * 邀请已有用户加入工作空间请求（管理员/拥有者调用，按用户名邀请）。
 */
public record InviteMemberRequest(String username, String role) {
}
