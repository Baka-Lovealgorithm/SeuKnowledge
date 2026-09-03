package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    long countByGroupId(Long groupId);

    List<GroupMember> findByGroupIdOrderByIdAsc(Long groupId);

    /** 用户所属全部组成员关系（用于 ACL：取一组 groupId 集合） */
    List<GroupMember> findByUserId(Long userId);

    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByGroupId(Long groupId);
}