package com.ai.konwledgerepo.service.modelconfig;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ModelConfigRequest;
import com.ai.konwledgerepo.dto.ModelConfigResponse;
import com.ai.konwledgerepo.dto.ModelConfigTestResponse;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模型配置业务（按工作空间隔离）：CRUD、默认模型互斥（空间内）、连通性测试、配置变更后刷新 ModelFactory 缓存。
 * 模型配置严格限定当前工作空间，跨空间不可见、不可操作（key 不泄漏）。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private final ModelConfigRepository repository;
    private final ModelFactory modelFactory;
    private final WorkspaceAccess workspaceAccess;

    public ModelConfigService(ModelConfigRepository repository, ModelFactory modelFactory, WorkspaceAccess workspaceAccess) {
        this.repository = repository;
        this.modelFactory = modelFactory;
        this.workspaceAccess = workspaceAccess;
    }

    public List<ModelConfigResponse> list(Long workspaceId) {
        return repository.findByWorkspaceIdOrderByIdDesc(workspaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ModelConfigResponse create(ModelConfigRequest request, Long workspaceId) {
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        validateBaseUrl(request);
        ModelConfig cfg = new ModelConfig();
        apply(cfg, request, true);
        cfg.setWorkspaceId(workspaceId);
        if (Boolean.TRUE.equals(cfg.getIsDefault())) {
            clearDefault(workspaceId, cfg.getModelType(), null);
        }
        repository.save(cfg);
        modelFactory.evictCache();
        return toResponse(cfg);
    }

    @Transactional
    public ModelConfigResponse update(Long id, ModelConfigRequest request, Long workspaceId) {
        ModelConfig cfg = requireInWorkspace(id, workspaceId);
        validateBaseUrl(request);
        apply(cfg, request, false);
        if (Boolean.TRUE.equals(cfg.getIsDefault())) {
            clearDefault(workspaceId, cfg.getModelType(), cfg.getId());
        }
        repository.save(cfg);
        modelFactory.evictCache();
        return toResponse(cfg);
    }

    @Transactional
    public void delete(Long id, Long workspaceId) {
        requireInWorkspace(id, workspaceId);
        repository.deleteById(id);
        modelFactory.evictCache();
    }

    public ModelConfigTestResponse test(Long id, Long workspaceId) {
        ModelConfig cfg = requireInWorkspace(id, workspaceId);
        long start = System.currentTimeMillis();
        boolean success = modelFactory.testConnection(cfg);
        log.info("模型连通性测试 modelId={} provider={} type={} model={} 结果={} 耗时={}ms",
                id, cfg.getProvider(), cfg.getModelType(), cfg.getModelName(),
                success, System.currentTimeMillis() - start);
        String message = success ? "连接成功" : "连接失败，请检查 apiKey / baseUrl / 网络";
        return new ModelConfigTestResponse(id, success, message);
    }

    /** 校验配置存在且属于当前工作空间（归属校验唯一入口：WorkspaceAccess） */
    private ModelConfig requireInWorkspace(Long id, Long workspaceId) {
        ModelConfig cfg = repository.findById(id)
                .orElseThrow(() -> new BizException("模型配置不存在"));
        workspaceAccess.requireBelongs(cfg.getWorkspaceId(), workspaceId, "无权操作该模型配置");
        return cfg;
    }

    private void apply(ModelConfig cfg, ModelConfigRequest req, boolean create) {
        cfg.setName(req.name());
        cfg.setProvider(req.provider());
        cfg.setModelType(req.modelType());
        cfg.setUsage(req.usage());
        validateUsageType(req.modelType(), req.usage());
        cfg.setModelName(req.modelName());
        if (create || (req.apiKey() != null && !req.apiKey().isBlank())) {
            cfg.setApiKey(req.apiKey());
        }
        cfg.setBaseUrl(req.baseUrl());
        cfg.setTemperature(req.temperature());
        cfg.setMaxTokens(req.maxTokens());
        cfg.setIsDefault(Boolean.TRUE.equals(req.isDefault()));
        cfg.setEnabled(req.enabled() == null || req.enabled());
        cfg.setDisableThinking(Boolean.TRUE.equals(req.disableThinking()));
        cfg.setThinkingParams(req.thinkingParams());
    }

    /**
     * 校验模型类型与用途绑定组合：CHAT→抽取/生成/校验/路由/记忆/闲聊/标题；EMBEDDING→检索；
     * VISION→识图；RERANK→重排（用途留空=该类型通用）。
     * <p>类型白名单的唯一出口在 {@code ModelConfigRequest} 的 {@code @Pattern}（历史类型 TITLE 在那里被拒），
     * 本方法只判「类型 × 用途」组合，不判类型是否还能新建。
     */
    private void validateUsageType(String modelType, String usage) {
        if (usage == null || usage.isBlank()) {
            return;
        }
        ModelType type = ModelType.of(modelType);
        if (type == null || !type.validUsage(usage)) {
            throw new BizException("模型类型 " + modelType + " 不支持用途绑定 " + usage
                    + "（CHAT→EXTRACT/GENERATE/VERIFY/ROUTER/MEMORY/CHITCHAT/TITLE，EMBEDDING→RETRIEVE，"
                    + "VISION→VISION，RERANK→RERANK）");
        }
    }

    private void validateBaseUrl(ModelConfigRequest req) {
        if ("OPENAI_COMPAT".equals(req.provider()) && (req.baseUrl() == null || req.baseUrl().isBlank())) {
            throw new BizException("OpenAI 兼容模型必须填写 baseUrl");
        }
    }

    /** 工作空间内同类型仅一个默认：清除同类型其它默认（保留当前 excludeId） */
    private void clearDefault(Long workspaceId, String modelType, Long excludeId) {
        repository.findByWorkspaceIdAndModelTypeAndEnabledTrueOrderByIdAsc(workspaceId, modelType).stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsDefault()))
                .filter(c -> excludeId == null || !c.getId().equals(excludeId))
                .forEach(c -> {
                    c.setIsDefault(false);
                    repository.save(c);
                });
    }

    private ModelConfigResponse toResponse(ModelConfig cfg) {
        return new ModelConfigResponse(cfg.getId(), cfg.getName(), cfg.getProvider(), cfg.getModelType(),
                cfg.getUsage(), cfg.getModelName(), cfg.getBaseUrl(), cfg.getTemperature(), cfg.getMaxTokens(),
                cfg.getIsDefault(), cfg.getEnabled(), cfg.getCreatedAt(),
                cfg.getDisableThinking(), cfg.getThinkingParams());
    }
}
