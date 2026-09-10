package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.BusinessKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BusinessKnowledgeRepository extends JpaRepository<BusinessKnowledge, Long> {

    List<BusinessKnowledge> findByKbIdAndDeletedFalseOrderByIdDesc(Long kbId);

    List<BusinessKnowledge> findByKbIdAndDeletedFalseAndStatusOrderByIdDesc(Long kbId, String status);

    /** 某版本组的全部历史版本（含软删，按版本倒序） */
    List<BusinessKnowledge> findByHistoryGroupIdOrderByVersionDesc(String historyGroupId);

    /** 某版本组当前激活行 */
    Optional<BusinessKnowledge> findFirstByHistoryGroupIdAndDeletedFalse(String historyGroupId);

    /** 按来源文档查询（抽取任务结果预览） */
    List<BusinessKnowledge> findBySourceDocIdInAndDeletedFalseAndStatus(Collection<Long> docIds, String status);

    /** 同知识库同名术语的未删记录（抽取去重：DRAFT 将被替换为最新抽取结果） */
    List<BusinessKnowledge> findByKbIdAndTermAndDeletedFalseAndStatus(Long kbId, String term, String status);

    /** 按知识库硬删除全部记录（含软删版本，清空知识库内容时调用） */
    void deleteByKbId(Long kbId);

    /**
     * 来源文档改名后同步冗余的展示名（含软删历史版本，保证版本列表里名字一致）。
     * <p>
     * 冗余字段（{@code source_doc_name}）仅供列表展示，不参与检索判定；bulk update 跳过 @Version
     * 自增，属可接受（改名与知识审核并发概率极低，且冲突只会造成显示名短暂滞后）。
     *
     * @return 受影响行数
     */
    @Modifying
    @Query("update BusinessKnowledge b set b.sourceDocName = :docName where b.sourceDocId = :docId")
    int updateSourceDocNameByDocId(@Param("docId") Long docId, @Param("docName") String docName);

    /**
     * 来源文档删除时仅解除关联，不删除任何知识或历史版本。
     * sourceDocName 作为来源快照保留，兼容现有可空列，无需新增外键或执行 DDL。
     */
    @Modifying
    @Query("update BusinessKnowledge b set b.sourceDocId = null where b.sourceDocId = :docId")
    int detachSourceDocumentByDocId(@Param("docId") Long docId);
}
