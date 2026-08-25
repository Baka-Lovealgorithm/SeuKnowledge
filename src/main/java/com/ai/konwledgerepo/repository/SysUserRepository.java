package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);
}
