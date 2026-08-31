package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ChunkReviewLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkReviewLogRepository extends JpaRepository<ChunkReviewLog, Long> {

    List<ChunkReviewLog> findByChunkIdOrderByIdAsc(Long chunkId);

    List<ChunkReviewLog> findByDocIdOrderByIdAsc(Long docId);
}
