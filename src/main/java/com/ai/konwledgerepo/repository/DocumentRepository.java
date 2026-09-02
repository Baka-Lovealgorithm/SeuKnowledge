package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.Document;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByKbIdOrderByIdDesc(Long kbId);

    /** 初洗/精修队列：按知识库查处于初洗中/精修中状态的文档（跨文档聚合待决断项） */
    List<Document> findByKbIdAndCurateStatusInOrderByIdDesc(Long kbId, List<String> curateStatuses);

    long countByKbId(Long kbId);

    /** 行级悲观锁查询（SELECT ... FOR UPDATE），供解析/删除/重试的并发串行化使用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") Long id);

    /**
     * 原子重试入口：仅允许从 SUCCESS/FAILED/ERROR 迁入 PENDING（禁止 PENDING/PARSING 重试）。
     * 返回 0 表示文档不存在、解析中或已被处理。
     */
    @Modifying
    @Query("update Document d set d.parseStatus = 'PENDING', d.errorMsg = null, d.chunkCount = 0 where d.id = :id and d.parseStatus not in ('PENDING', 'PARSING')")
    int casPendingForRetry(@Param("id") Long id);
}
