package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.QaPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QaPairRepository extends JpaRepository<QaPair, Long> {

    List<QaPair> findByKbIdAndDeletedFalseOrderByIdDesc(Long kbId);

    List<QaPair> findByKbIdAndDeletedFalseAndStatusOrderByIdDesc(Long kbId, String status);

    List<QaPair> findByHistoryGroupIdOrderByVersionDesc(String historyGroupId);

    Optional<QaPair> findFirstByHistoryGroupIdAndDeletedFalse(String historyGroupId);

    /** 按来源文档查询（抽取任务结果预览） */
    List<QaPair> findBySourceDocIdInAndDeletedFalseAndStatus(Collection<Long> docIds, String status);

    /** 同知识库相同问题的未删记录（抽取去重：DRAFT 将被替换为最新抽取结果） */
    List<QaPair> findByKbIdAndQuestionAndDeletedFalseAndStatus(Long kbId, String question, String status);

    /** 按知识库硬删除全部记录（含软删版本，清空知识库内容时调用） */
    void deleteByKbId(Long kbId);

    /**
     * 来源文档改名后同步冗余的展示名（含软删历史版本）。口径与
     * {@link BusinessKnowledgeRepository#updateSourceDocNameByDocId} 一致：仅展示用冗余字段，bulk update 跳过 @Version。
     */
    @Modifying
    @Query("update QaPair q set q.sourceDocName = :docName where q.sourceDocId = :docId")
    int updateSourceDocNameByDocId(@Param("docId") Long docId, @Param("docName") String docName);

    /**
     * 来源文档删除时仅解除关联，不删除任何问答对或历史版本。
     * sourceDocName 作为来源快照保留，兼容现有可空列，无需新增外键或执行 DDL。
     */
    @Modifying
    @Query("update QaPair q set q.sourceDocId = null where q.sourceDocId = :docId")
    int detachSourceDocumentByDocId(@Param("docId") Long docId);
}
