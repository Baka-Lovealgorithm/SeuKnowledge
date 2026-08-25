package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ExtractTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ExtractTaskRepository extends JpaRepository<ExtractTask, Long> {

    List<ExtractTask> findByKbIdInOrderByIdDesc(Collection<Long> kbIds);

    List<ExtractTask> findByKbIdOrderByIdDesc(Long kbId);
}
