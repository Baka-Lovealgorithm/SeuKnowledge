package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Component;

/**
 * 工作空间归属校验：所有按知识库/文档 id 的访问先校验其属于当前工作空间，
 * 防止跨工作空间访问（一期单工作空间为防御性设计，多工作空间升级后生效）。
 */
@Component
public class WorkspaceAccess {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;

    public WorkspaceAccess(KnowledgeBaseRepository kbRepository, DocumentRepository documentRepository) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
    }

    /** 校验知识库存在且属于当前工作空间，返回该知识库 */
    public KnowledgeBase requireKb(Long kbId, Long workspaceId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException("知识库不存在"));
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(workspaceId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权访问该知识库");
        }
        return kb;
    }

    /** 校验文档存在且其知识库属于当前工作空间 */
    public Document requireDoc(Long docId, Long workspaceId) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        requireKb(doc.getKbId(), workspaceId);
        return doc;
    }

    /**
     * 通用归属谓词：校验已加载资源的工作空间归属（资源实体本身不带 kbId 的场景，如模型配置/会话）。
     * 资源 workspaceId 为空（历史数据未回填）时视为可访问，与 requireKb 语义一致。
     */
    public void requireBelongs(Long resourceWorkspaceId, Long workspaceId, String deniedMessage) {
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(workspaceId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, deniedMessage);
        }
    }
}
