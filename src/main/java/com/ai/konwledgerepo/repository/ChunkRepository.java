package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.Chunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChunkRepository extends JpaRepository<Chunk, Long> {

    List<Chunk> findByDocIdOrderBySeqAsc(Long docId);

    /**
     * 分页查文档分块：seq 自然顺序 + 可空清洗状态过滤（cleanStatus 为 null 不过滤）。
     * 文档精修 / 初洗预览的分页加载。
     */
    @Query("select c from Chunk c where c.docId = :docId "
            + "and (:cleanStatus is null or c.cleanStatus = :cleanStatus) "
            + "order by c.seq asc")
    Page<Chunk> pageByDocSeq(@Param("docId") Long docId,
                             @Param("cleanStatus") String cleanStatus,
                             Pageable pageable);

    /**
     * 分页查文档分块：待人工审核（SUSPECT）优先，其余按 seq；同样支持可空状态过滤。
     * 对应精修页「待审核优先」排序模式。
     */
    @Query("select c from Chunk c where c.docId = :docId "
            + "and (:cleanStatus is null or c.cleanStatus = :cleanStatus) "
            + "order by case when c.cleanStatus = 'SUSPECT' then 0 else 1 end, c.seq asc")
    Page<Chunk> pageByDocSuspectFirst(@Param("docId") Long docId,
                                      @Param("cleanStatus") String cleanStatus,
                                      Pageable pageable);

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

    /** 按文档计数指定向量状态（重建向量前后核对、向量化收尾回写） */
    long countByDocIdAndStatus(Long docId, String status);

    /**
     * 按知识库一次 group-by 取回全部文档的 chunk 状态分布（文档列表"向量 i/t"标记）。
     * 逐文档 count 会随文档数线性放大查询数，这里聚合为单条查询。
     */
    @Query("select c.docId as docId, c.status as status, c.cleanStatus as cleanStatus, count(c) as cnt "
            + "from Chunk c where c.kbId = :kbId group by c.docId, c.status, c.cleanStatus")
    List<ChunkStatusCount> statusCountsByKb(@Param("kbId") Long kbId);

    /**
     * 重建向量前置：把该文档 FAILED（且未被清洗丢弃）的块退回 EMBEDDING，使其能被
     * {@code VectorIngestionService.ingest} 重新拾取（ingest 只处理 EMBEDDING 块）。
     *
     * @return 退回待向量化的块数
     */
    @Modifying
    @Query("update Chunk c set c.status = 'EMBEDDING' where c.docId = :docId and c.status = 'FAILED'"
            + " and (c.cleanStatus is null or c.cleanStatus <> 'FILTERED')")
    int resetFailedToEmbedding(@Param("docId") Long docId);

    /**
     * 插入新块让位：把 docId 下 seq >= fromSeq 的块整体后移一位（人工新增分块用）。
     * 表无 (docId, seq) 唯一约束（删除/合并本就留空洞），让位与插入同事务内安全。
     */
    @Modifying
    @Query("update Chunk c set c.seq = c.seq + 1 where c.docId = :docId and c.seq >= :fromSeq")
    int shiftSeqFrom(@Param("docId") Long docId, @Param("fromSeq") Integer fromSeq);
}
