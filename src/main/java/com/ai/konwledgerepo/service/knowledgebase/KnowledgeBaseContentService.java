package com.ai.konwledgerepo.service.knowledgebase;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库内容清空（重内容域，独立于 KnowledgeBaseService 的轻量 CRUD 域）：
 * 清空 ES 向量（CHUNK / BUSINESS / QA 全部来源）+ MySQL chunk / 业务知识 / 问答对。
 * 独立成服务同时打破了 KnowledgeBaseService ↔ VectorIngestionService 的循环依赖。
 */
@Service
public class KnowledgeBaseContentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseContentService.class);

    private final WorkspaceAccess workspaceAccess;
    private final VectorIngestionService vectorIngestionService;
    private final ChunkRepository chunkRepository;
    private final BusinessKnowledgeRepository businessKnowledgeRepository;
    private final QaPairRepository qaPairRepository;
    private final RedisCacheService redisCacheService;
    private final KnowledgeBaseService kbService;

    public KnowledgeBaseContentService(WorkspaceAccess workspaceAccess,
                                       VectorIngestionService vectorIngestionService,
                                       ChunkRepository chunkRepository,
                                       BusinessKnowledgeRepository businessKnowledgeRepository,
                                       QaPairRepository qaPairRepository,
                                       RedisCacheService redisCacheService,
                                       KnowledgeBaseService kbService) {
        this.workspaceAccess = workspaceAccess;
        this.vectorIngestionService = vectorIngestionService;
        this.chunkRepository = chunkRepository;
        this.businessKnowledgeRepository = businessKnowledgeRepository;
        this.qaPairRepository = qaPairRepository;
        this.redisCacheService = redisCacheService;
        this.kbService = kbService;
    }

    /**
     * 清空知识库全部内容：ES 向量（CHUNK / BUSINESS / QA 全部来源）+ MySQL chunk / 业务知识 / 问答对。
     * 保留文档记录与磁盘源文件，便于后续以 retry 走新解析管线重新解析向量化。
     */
    @Transactional
    public void clearContent(Long kbId, Long workspaceId) {
        workspaceAccess.requireKb(kbId, workspaceId);
        // 1) ES 全部来源一次清空
        vectorIngestionService.deleteByKbId(kbId);
        // 2) MySQL 硬删（含软删版本）
        chunkRepository.deleteByKbId(kbId);
        businessKnowledgeRepository.deleteByKbId(kbId);
        qaPairRepository.deleteByKbId(kbId);
        // 3) 缓存失效（文档计数基于 Document 行，保持不变；仅失效计数与列表防陈旧）
        kbService.evictKbCache(kbId, workspaceId);
        log.info("知识库 {} 内容已清空（保留文档记录与源文件）", kbId);
    }
}
