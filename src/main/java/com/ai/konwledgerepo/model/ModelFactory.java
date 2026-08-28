package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import com.ai.konwledgerepo.service.rerank.RerankClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型工厂：依据库内 ModelConfig 动态创建并缓存 ChatModel / EmbeddingModel / 重排客户端（EvidenceReranker）。
 * 配置变更后调用 {@link #evictCache()} 刷新缓存。
 *
 * 按工作空间隔离：所有「类型+用途 → 配置」解析严格限定 workspaceId，
 * 跨空间不回退、不共享（避免模型 key 跨空间泄漏）。
 *
 * 缓存分层：
 * - 模型实例（ChatModel / EmbeddingModel / EvidenceReranker，昂贵且不可序列化）→ 进程内 ConcurrentHashMap；
 * - "空间+类型+用途 → configId" 解析结果、默认配置、配置快照 → Redis（TTL 600s，可配），
 *   避免一次问答中多次解析的 DB 查询（问答链路可触发 5-15+ 次）。
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final ModelConfigRepository modelConfigRepository;
    private final RedisCacheService redisCacheService;
    private final Map<String, ModelProvider> providerMap;
    private final SeuRerankProperties rerankProps;
    private final ObjectMapper objectMapper;
    private final Duration modelTtl;

    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final Map<String, EvidenceReranker> rerankerCache = new ConcurrentHashMap<>();

    public ModelFactory(ModelConfigRepository modelConfigRepository,
                        RedisCacheService redisCacheService,
                        SeuCacheProperties cacheProps,
                        List<ModelProvider> providers,
                        SeuRerankProperties rerankProps,
                        ObjectMapper objectMapper) {
        this.modelConfigRepository = modelConfigRepository;
        this.redisCacheService = redisCacheService;
        this.modelTtl = Duration.ofSeconds(cacheProps.modelTtlSeconds());
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::providerName, Function.identity()));
        this.rerankProps = rerankProps;
        this.objectMapper = objectMapper;
    }

    public ChatModel getChatModel(Long configId) {
        ModelConfig cfg = getConfig(configId);
        return chatModelCache.computeIfAbsent("chat:" + configId, k -> provider(cfg).createChatModel(cfg));
    }

    public EmbeddingModel getEmbeddingModel(Long configId) {
        ModelConfig cfg = getConfig(configId);
        return embeddingModelCache.computeIfAbsent("emb:" + configId, k -> provider(cfg).createEmbeddingModel(cfg));
    }

    /** 工作空间内默认启用的文本模型 */
    public ChatModel getDefaultChatModel(Long workspaceId) {
        return getChatModel(defaultConfig(workspaceId, ModelType.CHAT.value()).getId());
    }

    /** 工作空间内默认启用的向量模型 */
    public EmbeddingModel getDefaultEmbeddingModel(Long workspaceId) {
        return getEmbeddingModel(defaultConfig(workspaceId, ModelType.EMBEDDING.value()).getId());
    }

    /**
     * 按用途绑定取文本模型（GENERATE 生成 / EXTRACT 抽取等，限当前工作空间）：
     * 优先取「类型 CHAT + 用途匹配」的启用模型，其次取通用（未绑定用途）模型，最后回退同类型默认。
     * 解析结果缓存于 Redis（seuknowledge:model:resolve:{ws}:*），配置变更后随 evictCache() 失效。
     */
    public ChatModel getChatModelByUsage(String usage, Long workspaceId) {
        return getChatModel(resolveConfigId(workspaceId, ModelType.CHAT.value(), usage));
    }

    /**
     * 按用途解析的 CHAT 模型配置实体（judge 调用读取 disableThinking / thinkingParams 等运行参数）；
     * 解析失败（未配置）返回 null，调用方按不关思考处理。
     */
    public ModelConfig resolveChatConfig(String usage, Long workspaceId) {
        try {
            return getConfig(resolveConfigId(workspaceId, ModelType.CHAT.value(), usage));
        } catch (Exception e) {
            log.warn("按用途解析 CHAT 模型配置失败（usage={}）: {}", usage, e.getMessage());
            return null;
        }
    }

    /**
     * 按用途绑定取向量模型（RETRIEVE 检索，限当前工作空间）：优先用途匹配，其次通用，最后回退同类型默认。
     * 解析结果缓存于 Redis。
     */
    public EmbeddingModel getEmbeddingModelByUsage(String usage, Long workspaceId) {
        return getEmbeddingModel(resolveConfigId(workspaceId, ModelType.EMBEDDING.value(), usage));
    }

    /** 识图（多模态）模型（限当前工作空间）：VISION 类型；未配置时返回空，调用方回退文本解析。解析结果缓存于 Redis。 */
    public Optional<ChatModel> getVisionModel(Long workspaceId) {
        Optional<Long> cachedId = cachedConfigId(RedisKeys.modelResolve(workspaceId, ModelType.VISION.value(), ModelUsage.VISION.value()));
        if (cachedId.isPresent()) {
            return Optional.of(getChatModel(cachedId.get()));
        }
        Optional<ModelConfig> cfg = modelConfigRepository
                .findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(workspaceId, ModelType.VISION.value())
                .or(() -> modelConfigRepository
                        .findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(workspaceId, ModelType.VISION.value(), ModelUsage.VISION.value()));
        cfg.ifPresent(c -> redisCacheService.setString(
                RedisKeys.modelResolve(workspaceId, ModelType.VISION.value(), ModelUsage.VISION.value()), String.valueOf(c.getId()), modelTtl));
        return cfg.map(c -> getChatModel(c.getId()));
    }

    /**
     * 重排模型（交叉编码器精排，限当前工作空间）：RERANK 类型，可选——解析不到配置时返回 empty，
     * 问答链路降级为按 ES 分截断（不抛异常）。解析结果缓存于 Redis。
     * 实例创建失败（如 OpenAI 兼容缺 baseUrl）同样返回 empty 并告警，保证问答可用。
     */
    public Optional<EvidenceReranker> getReranker(Long workspaceId) {
        String type = ModelType.RERANK.value();
        String usage = ModelUsage.RERANK.value();
        String key = RedisKeys.modelResolve(workspaceId, type, usage);
        Optional<Long> cachedId = cachedConfigId(key);
        if (cachedId.isPresent()) {
            return rerankerInstance(cachedId.get());
        }
        Optional<ModelConfig> cfg = modelConfigRepository
                .findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(workspaceId, type, usage)
                .or(() -> modelConfigRepository
                        .findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(workspaceId, type))
                .or(() -> modelConfigRepository
                        .findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(workspaceId, type));
        if (cfg.isEmpty()) {
            return Optional.empty();
        }
        redisCacheService.setString(key, String.valueOf(cfg.get().getId()), modelTtl);
        return rerankerInstance(cfg.get().getId());
    }

    /**
     * 标题模型（限当前工作空间）：优先 TITLE 类型配置（usage=TITLE → 通用 → 默认），
     * 未配置 TITLE 类型时回退 CHAT GENERATE（保持现有行为）。
     * 解析结果缓存于 Redis；TITLE 未命中不缓存（避免每次标题生成重复查 DB）。
     */
    public ChatModel getTitleChatModel(Long workspaceId) {
        return getChatModel(resolveTitleConfigId(workspaceId));
    }

    /**
     * 标题模型配置实体（judge 参数读取 disableThinking / thinkingParams 等）；
     * 解析失败返回 null（调用方按不关思考处理）。
     */
    public ModelConfig resolveTitleChatConfig(Long workspaceId) {
        try {
            return getConfig(resolveTitleConfigId(workspaceId));
        } catch (Exception e) {
            log.warn("按用途解析标题模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 会话记忆摘要模型（限当前工作空间）：优先「CHAT + usage=MEMORY」专用配置
     * （用途匹配 → 通用 → 同类型默认），未配置时直接复用 ROUTER 模型的配置（意图路由/改写档，
     * 便宜且已常见配置）；ROUTER 自身未配置时继续回退通用/默认 chat。
     */
    public ChatModel getMemoryChatModel(Long workspaceId) {
        Optional<Long> memoryId = tryResolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.MEMORY.value());
        if (memoryId.isPresent()) {
            return getChatModel(memoryId.get());
        }
        return getChatModel(resolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.ROUTER.value()));
    }

    /**
     * 解析标题模型配置 id：TITLE 类型优先 → 未命中回退 CHAT GENERATE（缓存仅命中时回填）。
     * TITLE 类型链：usage=TITLE → 通用 → 同类型默认。
     */
    private Long resolveTitleConfigId(Long workspaceId) {
        Optional<Long> titleId = tryResolveConfigId(workspaceId, ModelType.TITLE.value(), ModelUsage.TITLE.value());
        if (titleId.isPresent()) {
            return titleId.get();
        }
        return resolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.GENERATE.value());
    }

    /**
     * 可选解析配置 id：按「用途匹配 → 通用 → 同类型默认」链查询，命中回填 Redis 解析缓存，
     * 全部未命中返回 Optional.empty()（不缓存失败，不抛异常）。
     * 供 TITLE / RERANK 等可选模型类型的解析。
     */
    private Optional<Long> tryResolveConfigId(Long workspaceId, String modelType, String usage) {
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

    /** 重排客户端实例（进程内缓存；创建失败返回 empty，不缓存失败态以免每次重试） */
    private Optional<EvidenceReranker> rerankerInstance(Long configId) {
        String cacheKey = "rerank:" + configId;
        EvidenceReranker cached = rerankerCache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ModelConfig cfg = getConfig(configId);
            RerankClient client = RerankClient.from(cfg, rerankProps, objectMapper);
            rerankerCache.put(cacheKey, client);
            return Optional.of(client);
        } catch (Exception e) {
            log.warn("重排模型实例创建失败，问答将降级为按 ES 分截断: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 解析「空间+类型+用途 → 配置 id」：Redis 缓存优先，miss 回源 DB（保持原优先级：用途匹配 → 通用 → 默认）。
     */
    private Long resolveConfigId(Long workspaceId, String modelType, String usage) {
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

    private Optional<Long> cachedConfigId(String key) {
        return redisCacheService.getString(key).map(Long::valueOf);
    }

    public void evictCache() {
        chatModelCache.clear();
        embeddingModelCache.clear();
        rerankerCache.clear();
        redisCacheService.deleteByPattern(RedisKeys.PREFIX + "model:*");
    }

    public boolean testConnection(ModelConfig cfg) {
        // 重排模型不走供应商 Chat/Embedding 协议，单独用 RerankClient 打一次分验证
        if (ModelType.RERANK.is(cfg.getModelType())) {
            return RerankClient.testConnection(cfg, rerankProps, objectMapper);
        }
        ModelProvider provider = providerMap.get(cfg.getProvider());
        if (provider == null) {
            throw new BizException("不支持的模型供应商: " + cfg.getProvider());
        }
        return provider.testConnection(cfg);
    }

    /** 工作空间内同类型默认配置（解析结果缓存于 Redis seuknowledge:model:default:{ws}:{type}） */
    private ModelConfig defaultConfig(Long workspaceId, String type) {
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

    /** 配置实体读取：Redis 快照优先，miss 回源 DB 回填 */
    private ModelConfig getConfig(Long id) {
        Optional<ModelConfigSnapshot> cached = redisCacheService.get(RedisKeys.modelConfig(id), ModelConfigSnapshot.class);
        if (cached.isPresent()) {
            return cached.get().toEntity();
        }
        ModelConfig cfg = modelConfigRepository.findById(id)
                .orElseThrow(() -> new BizException("模型配置不存在"));
        redisCacheService.set(RedisKeys.modelConfig(id), ModelConfigSnapshot.from(cfg), modelTtl);
        return cfg;
    }

    private ModelProvider provider(ModelConfig cfg) {
        ModelProvider provider = providerMap.get(cfg.getProvider());
        if (provider == null) {
            throw new BizException("不支持的模型供应商: " + cfg.getProvider());
        }
        return provider;
    }
}
