package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.DocumentCurateLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 文档策展动作审计。
 */
public interface DocumentCurateLogRepository extends JpaRepository<DocumentCurateLog, Long> {

    List<DocumentCurateLog> findByDocIdOrderByIdAsc(Long docId);

    void deleteByDocId(Long docId);
}
