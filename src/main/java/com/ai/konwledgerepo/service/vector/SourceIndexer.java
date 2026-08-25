package com.ai.konwledgerepo.service.vector;

import com.ai.konwledgerepo.entity.ReviewStatus;
import org.springframework.stereotype.Component;

/**
 * 结构化来源（业务知识 / 问答对）的 ES 索引同步：审核通过（APPROVED）写入索引，
 * 软删/拒绝/禁用等移出索引。幂等：重复调用仅覆盖写入或删除不存在的文档。
 * {@code BusinessKnowledgeService} / {@code QaPairService} 共用，消除重复的 syncIndex 模式。
 */
@Component
public class SourceIndexer {

    private final VectorIngestionService vectorIngestionService;

    public SourceIndexer(VectorIngestionService vectorIngestionService) {
        this.vectorIngestionService = vectorIngestionService;
    }

    /**
     * 按实体当前状态同步索引。
     *
     * @param sourceType     来源类型（BUSINESS / QA，见 SourceType）
     * @param entityId       实体 id
     * @param kbId           知识库 id
     * @param sourceDocId    来源文档 id（可为 null）
     * @param sourceDocName  来源文档名（可为 null）
     * @param title          索引标题（业务知识为术语，问答对为问题）
     * @param content        索引正文（索引内容构建由调用方负责）
     * @param deleted        是否软删（软删优先移出索引）
     * @param status         审核状态（APPROVED 写入，其余移出）
     */
    public void sync(String sourceType, Long entityId, Long kbId, Long sourceDocId, String sourceDocName,
                     String title, String content, boolean deleted, String status) {
        if (deleted) {
            vectorIngestionService.deleteBySource(sourceType, entityId);
            return;
        }
        if (ReviewStatus.APPROVED.is(status)) {
            vectorIngestionService.indexSource(sourceType, entityId, kbId, sourceDocId, sourceDocName,
                    0, title, content);
        } else {
            vectorIngestionService.deleteBySource(sourceType, entityId);
        }
    }
}
