package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.KbAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbAccessRepository extends JpaRepository<KbAccess, Long> {

    List<KbAccess> findByKbIdOrderByIdAsc(Long kbId);

    /** 知识库对指定用户的授权记录（granteeType=USER） */
    Optional<KbAccess> findByKbIdAndGranteeTypeAndGranteeId(Long kbId, String granteeType, Long granteeId);

    List<KbAccess> findByKbIdAndGranteeType(Long kbId, String granteeType);

    List<KbAccess> findByGranteeTypeAndGranteeIdIn(String granteeType, java.util.Collection<Long> granteeIds);

    List<KbAccess> findByGranteeTypeAndGranteeId(String granteeType, Long granteeId);

    void deleteByKbId(Long kbId);

    void deleteByGranteeTypeAndGranteeId(String granteeType, Long granteeId);
}
