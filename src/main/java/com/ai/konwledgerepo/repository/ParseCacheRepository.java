package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ParseCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParseCacheRepository extends JpaRepository<ParseCache, Long> {

    Optional<ParseCache> findByFileHash(String fileHash);
}
