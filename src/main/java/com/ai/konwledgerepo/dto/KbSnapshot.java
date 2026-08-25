package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.KnowledgeBase;

/**
 * 知识库快照（可序列化，用于 Redis 缓存，仅含问答热路径字段）。
 */
public record KbSnapshot(Long id, String name, String status, Boolean archived, Long workspaceId) {

    public static KbSnapshot from(KnowledgeBase kb) {
        return new KbSnapshot(kb.getId(), kb.getName(), kb.getStatus(), kb.getArchived(), kb.getWorkspaceId());
    }
}
