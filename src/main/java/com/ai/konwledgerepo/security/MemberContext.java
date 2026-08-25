package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.entity.WorkspaceMember;

/**
 * 用户工作空间成员上下文快照（可序列化，用于 Redis 缓存，避免缓存 JPA 实体）。
 */
public record MemberContext(Long workspaceId, String role) {

    public static MemberContext from(WorkspaceMember member) {
        return new MemberContext(member.getWorkspaceId(), member.getRole());
    }
}
