package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByKbIdOrderByIdDesc(Long kbId);

    long countByKbId(Long kbId);
}
