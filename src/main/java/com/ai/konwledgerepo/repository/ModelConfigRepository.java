package com.ai.konwledgerepo.repository;

import com.ai.konwledgerepo.entity.ModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelConfigRepository extends JpaRepository<ModelConfig, Long> {

    /** 按工作空间列出全部配置（管理页） */
    List<ModelConfig> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    /** 按工作空间取启用模型（按类型过滤时用） */
    List<ModelConfig> findByWorkspaceIdAndEnabledTrueOrderByIdAsc(Long workspaceId);

    /** 按工作空间 + 类型取启用模型（CHAT / EMBEDDING / VISION） */
    List<ModelConfig> findByWorkspaceIdAndModelTypeAndEnabledTrueOrderByIdAsc(Long workspaceId, String modelType);

    /** 按工作空间取同类型默认启用模型 */
    Optional<ModelConfig> findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(Long workspaceId, String modelType);

    /** 按工作空间 + 类型 + 用途取启用模型（用途绑定，如抽取/生成/检索/识图） */
    Optional<ModelConfig> findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(Long workspaceId, String modelType, String usage);

    /** 按工作空间取类型启用且未绑定用途（通用）的模型 */
    Optional<ModelConfig> findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(Long workspaceId, String modelType);

    /** 存量迁移：未归属工作空间的旧模型配置 */
    List<ModelConfig> findByWorkspaceIdIsNull();
}
