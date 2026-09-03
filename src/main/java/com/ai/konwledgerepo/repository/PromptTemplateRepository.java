package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {

    /** 按 key + 作用域取模板（PLATFORM 时 scopeId 为 null） */
    Optional<PromptTemplate> findByKeyAndScopeTypeAndScopeId(String key, String scopeType, Long scopeId);

    /** 列出一层作用域全部模板 */
    List<PromptTemplate> findByScopeTypeOrderByKeyAsc(String scopeType);

    boolean existsByKeyAndScopeTypeAndScopeId(String key, String scopeType, Long scopeId);
}
