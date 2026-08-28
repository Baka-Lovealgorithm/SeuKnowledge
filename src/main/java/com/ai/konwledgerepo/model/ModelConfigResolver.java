package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 模型配置解析器：按「用途匹配 → 通用 → 同类型默认」链解析配置 id，
 * 结果缓存于 Redis（TTL 可配），miss 回源 DB。
 */
@Component
public class ModelConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigResolver.class);

    private final ModelConfigRepository modelConfigRepository;
    private final RedisCacheService redisCacheService;
    private final Duration modelTtl;

    public ModelConfigResolver(ModelConfigRepository modelConfigRepository,
                               RedisCacheService redisCacheService,
                               SeuCacheProperties cacheProps) {
        this.modelConfigRepository = modelConfigRepository;
        this.redisCacheService = redisCacheService;
        this.modelTtl = Duration.ofSeconds(cacheProps.modelTtlSeconds());
    }

    /** 解析「空间+类型+用途 → 配置 id」：Redis 缓存优先，miss 回源 DB。 */
    public Long resolveConfigId(Long workspaceId, String modelType, String usage) {
        String key = RedisKeys.modelResolve(workspaceId, modelType, usage);
        Optional<Long> cachedId = cachedConfigId(key);
        if (cachedId.isPresent()) {
            return cachedId.get();
        }
        ModelConfig cfg = modelConfigRepository
                .findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(workspaceId, modelType, usage)
                .orElseGet(() -> modelConfigRepository
                        .findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(workspaceId, modelType)
                        .orElseGet(() -> defaultConfig(workspaceId, modelType)));
        redisCacheService.setString(key, String.valueOf(cfg.getId()), modelTtl);
        return cfg.getId();
    }

    /** 可选解析（未命中返回 empty，不抛异常，不缓存失败）。 */
    public Optional<Long> tryResolveConfigId(Long workspaceId, String modelType, String usage) {
        String key = RedisKeys.modelResolve(workspaceId, modelType, usage);
        Optional<Long> cachedId = cachedConfigId(key);
        if (cachedId.isPresent()) {
            return cachedId;
        }
        Optional<ModelConfig> cfg = modelConfigRepository
                .findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(workspaceId, modelType, usage)
                .or(() -> modelConfigRepository
                        .findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(workspaceId, modelType))
                .or(() -> modelConfigRepository
                        .findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(workspaceId, modelType));
        cfg.ifPresent(c -> redisCacheService.setString(key, String.valueOf(c.getId()), modelTtl));
        return cfg.map(ModelConfig::getId);
    }

    /** 工作空间内同类型默认配置（解析结果缓存于 Redis）。 */
    public ModelConfig defaultConfig(Long workspaceId, String type) {
        String key = RedisKeys.modelDefault(workspaceId, type);
        Optional<Long> cachedId = cachedConfigId(key);
        if (cachedId.isPresent()) {
            return getConfig(cachedId.get());
        }
        ModelConfig cfg = modelConfigRepository
                .findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(workspaceId, type)
                .orElseGet(() -> modelConfigRepository
                        .findByWorkspaceIdAndModelTypeAndEnabledTrueOrderByIdAsc(workspaceId, type).stream()
                        .findFirst()
                        .orElseThrow(() -> new BizException("未配置启用的 " + type + " 模型，请先在模型配置中添加")));
        redisCacheService.setString(key, String.valueOf(cfg.getId()), modelTtl);
        return cfg;
    }

    /** 配置实体读取：Redis 快照优先，miss 回源 DB 回填。 */
    public ModelConfig getConfig(Long id) {
        Optional<ModelConfigSnapshot> cached = redisCacheService.get(RedisKeys.modelConfig(id), ModelConfigSnapshot.class);
        if (cached.isPresent()) {
            return cached.get().toEntity();
        }
        ModelConfig cfg = modelConfigRepository.findById(id)
                .orElseThrow(() -> new BizException("模型配置不存在"));
        redisCacheService.set(RedisKeys.modelConfig(id), ModelConfigSnapshot.from(cfg), modelTtl);
        return cfg;
    }

    /** TITLE 类型链：usage=TITLE → 通用 → 同类型默认。 */
    public Long resolveTitleConfigId(Long workspaceId) {
        Optional<Long> titleId = tryResolveConfigId(workspaceId, ModelType.TITLE.value(), ModelUsage.TITLE.value());
        return titleId.orElseGet(() -> resolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.GENERATE.value()));
    }

    /** 清除 Redis 解析缓存。 */
    public void evictCache() {
        redisCacheService.deleteByPattern(RedisKeys.PREFIX + "model:*");
    }

    private Optional<Long> cachedConfigId(String key) {
        return redisCacheService.getString(key).map(Long::valueOf);
    }
}