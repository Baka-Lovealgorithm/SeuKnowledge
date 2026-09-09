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
 * <p>例外：标题槽位走 {@link #resolveTitleConfigId} 专用链（精确 → 历史类型 → 生成档），
 * 因为标题已并入 CHAT 契约，通用档会把它顶替成「通用文本模型」。
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

    /**
     * 标题槽位解析：{@code CHAT+TITLE 精确}（现行绑定）→ {@code TITLE} 历史类型链（存量行，<b>零数据迁移</b>）
     * → {@code CHAT+GENERATE}（沿用改版前的兜底）。
     * <p><b>第一档必须只精确匹配，不能复用 {@link #tryResolveConfigId} 的整条链</b>：CHAT 类型下只要存在
     * 「用途留空的通用行」，通用档就会抢在历史 TITLE 行之前命中，把通用文本模型当成标题模型——
     * 这正是把标题并入 CHAT 契约后要避免的静默漂移（回归锁见
     * {@code ModelConfigResolverTest#resolveTitleConfigId_legacyTitleRowNotShadowedByGenericChatRow}）。
     * <p>反向注意：{@code CHAT+TITLE} 行本身也是一条 CHAT 行，在「该空间既无通用行、又无默认行」的极端配置下，
     * 它可能被第 4 档（最早启用的同类型行）选作其它文本槽位的最后兜底——这是并入契约的固有代价，
     * 配置页已用「绑定用途即不再占默认档」压低该概率。
     * <p>缓存键沿用 {@code (CHAT, TITLE)}，语义是「标题槽位的赢家」，与本方法一一对应；任何配置写入都会
     * {@link #evictCache()} 清掉。
     */
    public Long resolveTitleConfigId(Long workspaceId) {
        String key = RedisKeys.modelResolve(workspaceId, ModelType.CHAT.value(), ModelUsage.TITLE.value());
        Optional<Long> cachedId = cachedConfigId(key);
        if (cachedId.isPresent()) {
            return cachedId.get();
        }
        Long slotId = exactUsageConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.TITLE.value())
                .or(() -> tryResolveConfigId(workspaceId, ModelType.TITLE.value(), ModelUsage.TITLE.value()))
                .orElseGet(() -> resolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.GENERATE.value()));
        redisCacheService.setString(key, String.valueOf(slotId), modelTtl);
        return slotId;
    }

    /** 只认「类型 + 用途」精确绑定行（不吃通用档与默认档）。 */
    private Optional<Long> exactUsageConfigId(Long workspaceId, String modelType, String usage) {
        return modelConfigRepository
                .findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(workspaceId, modelType, usage)
                .map(ModelConfig::getId);
    }

    /** 清除 Redis 解析缓存。 */
    public void evictCache() {
        redisCacheService.deleteByPattern(RedisKeys.PREFIX + "model:*");
    }

    private Optional<Long> cachedConfigId(String key) {
        return redisCacheService.getString(key).map(Long::valueOf);
    }
}