package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    /** 一个知识库绑定一个默认 Agent */
    Optional<Agent> findByKbId(Long kbId);
}
