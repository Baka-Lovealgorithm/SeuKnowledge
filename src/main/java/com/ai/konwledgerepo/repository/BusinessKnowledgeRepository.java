package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.BusinessKnowledge;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
