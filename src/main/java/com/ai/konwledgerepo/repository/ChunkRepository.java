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
}
