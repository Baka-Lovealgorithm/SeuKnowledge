package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.KbGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbGroupRepository extends JpaRepository<KbGroup, Long> {

    List<KbGroup> findByWorkspaceIdOrderByIdAsc(Long workspaceId);

    Optional<KbGroup> findByWorkspaceIdAndName(Long workspaceId, String name);

    /** 归属校验：组必须属于指定工作空间 */
    Optional<KbGroup> findByIdAndWorkspaceId(Long id, Long workspaceId);

    boolean existsByWorkspaceIdAndName(Long workspaceId, String name);
}