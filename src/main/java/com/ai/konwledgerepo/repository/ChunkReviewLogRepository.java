package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ChunkReviewLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkReviewLogRepository extends JpaRepository<ChunkReviewLog, Long> {

    List<ChunkReviewLog> findByChunkIdOrderByIdAsc(Long chunkId);

    List<ChunkReviewLog> findByDocIdOrderByIdAsc(Long docId);

    /** 文档硬删除时同步清理分块审核日志，避免 chunk/doc 引用成为孤儿。 */
    void deleteByDocId(Long docId);
}
