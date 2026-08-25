package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    /** 用户全部工作空间成员记录（多工作空间：一用户可属多个空间） */
    List<WorkspaceMember> findByUserIdOrderByIdAsc(Long userId);

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    List<WorkspaceMember> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    long countByWorkspaceIdAndRole(Long workspaceId, String role);

    long countByWorkspaceId(Long workspaceId);
}
