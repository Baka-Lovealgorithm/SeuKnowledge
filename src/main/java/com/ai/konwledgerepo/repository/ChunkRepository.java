package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByDocIdOrderBySeqAsc(Long docId);

    List<Chunk> findByKbId(Long kbId);

    long countByDocId(Long docId);

    void deleteByDocId(Long docId);

    /** 按知识库硬删除全部 chunk（清空知识库内容时调用） */
    void deleteByKbId(Long kbId);

    /** 清洗 SUSPECT 队列：按知识库查全部待人工审核 chunk（跨文档聚合） */
    List<Chunk> findByKbIdAndCleanStatusOrderByDocIdAscSeqAsc(Long kbId, String cleanStatus);

    /** 按文档查清洗状态为指定值的 chunk（文档级筛选/计数） */
    List<Chunk> findByDocIdAndCleanStatusOrderBySeqAsc(Long docId, String cleanStatus);

    /** 按文档计数指定清洗状态（文档列表"待审核 N 块"徽标） */
    long countByDocIdAndCleanStatus(Long docId, String cleanStatus);
}
