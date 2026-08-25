package com.ai.konwledgerepo.dto;

/**
 * 创建工作空间请求（任意已登录用户可创建，创建者成为拥有者）。
 */
public record CreateWorkspaceRequest(String name) {
}
