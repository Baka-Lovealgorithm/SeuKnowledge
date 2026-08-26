package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ExtractTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExtractTaskRepository extends JpaRepository<ExtractTask, Long> {

    List<ExtractTask> findByKbIdInOrderByIdDesc(Collection<Long> kbIds);

    List<ExtractTask> findByKbIdOrderByIdDesc(Long kbId);

    /** 行级悲观锁查询（SELECT ... FOR UPDATE），供抽取任务启动时原子抢占 RUNNING */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ExtractTask t where t.id = :id")
    Optional<ExtractTask> findByIdForUpdate(@Param("id") Long id);
}
