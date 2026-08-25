package com.ai.konwledgerepo.service.knowledgebase;

import org.springframework.stereotype.Component;

/**
 * 知识库 → 工作空间 id 解析：问答、向量化、抽取、文档解析管线的模型选择共用。
 * 统一走 {@link KnowledgeBaseService#getEntityCached} 快照缓存，
 * 消除各处重复的「getEntityCached(...).getWorkspaceId()」拼写。
 */
@Component
public class WorkspaceIdResolver {

    private final KnowledgeBaseService kbService;

    public WorkspaceIdResolver(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    /** 解析知识库所属工作空间；kbId 为 null（历史数据/异常路径）时返回 null */
    public Long resolve(Long kbId) {
        return kbId == null ? null : kbService.getEntityCached(kbId).getWorkspaceId();
    }
}
