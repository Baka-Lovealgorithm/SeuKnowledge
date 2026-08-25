package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    List<Workspace> findAllByOrderByIdAsc();
}
