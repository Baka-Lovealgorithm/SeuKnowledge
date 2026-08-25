package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findByArchivedFalseOrderByIdDesc();

    /** 按工作空间查询未归档知识库（隔离展示） */
    List<KnowledgeBase> findByWorkspaceIdAndArchivedFalseOrderByIdDesc(Long workspaceId);

    /** 按工作空间查询全部知识库（含已归档，供会话按空间过滤保留历史） */
    List<KnowledgeBase> findByWorkspaceId(Long workspaceId);

    /** 存量迁移：未归属工作空间的旧知识库 */
    List<KnowledgeBase> findByWorkspaceIdIsNull();

    long countByStatus(String status);
}
