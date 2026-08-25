package com.ai.konwledgerepo.dto;

/**
 * 创建成员请求（管理员/拥有者调用）。
 */
public record CreateMemberRequest(String username, String password, String role) {
}
