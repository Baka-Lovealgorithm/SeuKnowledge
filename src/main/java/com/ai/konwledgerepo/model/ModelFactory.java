package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.ai.konwledgerepo.service.rerank.RerankClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型工厂：依据库内 ModelConfig 动态创建并缓存 ChatModel / EmbeddingModel / 重排客户端。
 * 配置解析委托 {@link ModelConfigResolver}，本类聚焦实例创建与进程内缓存。
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final ModelConfigResolver resolver;
    private final Map<String, ModelProvider> providerMap;
    private final SeuRerankProperties rerankProps;
    private final ObjectMapper objectMapper;

    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final Map<String, EvidenceReranker> rerankerCache = new ConcurrentHashMap<>();

    public ModelFactory(ModelConfigResolver resolver,
                        List<ModelProvider> providers,
                        SeuRerankProperties rerankProps,
                        ObjectMapper objectMapper) {
        this.resolver = resolver;
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::providerName, Function.identity()));
        this.rerankProps = rerankProps;
        this.objectMapper = objectMapper;
    }

    public ChatModel getChatModel(Long configId) {
        ModelConfig cfg = resolver.getConfig(configId);
        return chatModelCache.computeIfAbsent("chat:" + configId, k -> provider(cfg).createChatModel(cfg));
    }

    public EmbeddingModel getEmbeddingModel(Long configId) {
        ModelConfig cfg = resolver.getConfig(configId);
        return embeddingModelCache.computeIfAbsent("emb:" + configId, k -> provider(cfg).createEmbeddingModel(cfg));
    }

    public ChatModel getDefaultChatModel(Long workspaceId) {
        return getChatModel(resolver.defaultConfig(workspaceId, ModelType.CHAT.value()).getId());
    }

    public EmbeddingModel getDefaultEmbeddingModel(Long workspaceId) {
        return getEmbeddingModel(resolver.defaultConfig(workspaceId, ModelType.EMBEDDING.value()).getId());
    }

    public ChatModel getChatModelByUsage(String usage, Long workspaceId) {
        return getChatModel(resolver.resolveConfigId(workspaceId, ModelType.CHAT.value(), usage));
    }

    public ModelConfig resolveChatConfig(String usage, Long workspaceId) {
        try {
            return resolver.getConfig(resolver.resolveConfigId(workspaceId, ModelType.CHAT.value(), usage));
        } catch (Exception e) {
            log.warn("按用途解析 CHAT 模型配置失败（usage={}）: {}", usage, e.getMessage());
            return null;
        }
    }

    public EmbeddingModel getEmbeddingModelByUsage(String usage, Long workspaceId) {
        return getEmbeddingModel(resolver.resolveConfigId(workspaceId, ModelType.EMBEDDING.value(), usage));
    }

    public Optional<ChatModel> getVisionModel(Long workspaceId) {
        Optional<Long> cachedId = resolver.tryResolveConfigId(workspaceId, ModelType.VISION.value(), ModelUsage.VISION.value());
        return cachedId.map(this::getChatModel);
    }

    public Optional<EvidenceReranker> getReranker(Long workspaceId) {
        Optional<Long> cachedId = resolver.tryResolveConfigId(workspaceId, ModelType.RERANK.value(), ModelUsage.RERANK.value());
        return cachedId.flatMap(this::rerankerInstance);
    }

    public ChatModel getTitleChatModel(Long workspaceId) {
        return getChatModel(resolver.resolveTitleConfigId(workspaceId));
    }

    public ModelConfig resolveTitleChatConfig(Long workspaceId) {
        try {
            return resolver.getConfig(resolver.resolveTitleConfigId(workspaceId));
        } catch (Exception e) {
            log.warn("按用途解析标题模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    public ChatModel getMemoryChatModel(Long workspaceId) {
        Optional<Long> memoryId = resolver.tryResolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.MEMORY.value());
        if (memoryId.isPresent()) {
            return getChatModel(memoryId.get());
        }
        return getChatModel(resolver.resolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.ROUTER.value()));
    }

    /**
     * 解析 MEMORY 用途的模型配置（供 judge 类调用选项构造，如摘要生成的关思考/限输出）：
     * 与 {@link #getMemoryChatModel} 同一解析逻辑（MEMORY → ROUTER 回退），异常时返回 null
     * （调用方按无配置处理，如 {@code JudgeOptions.of} 仅限 maxTokens）。
     */
    public ModelConfig resolveMemoryChatConfig(Long workspaceId) {
        try {
            Optional<Long> memoryId = resolver.tryResolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.MEMORY.value());
            Long id = memoryId.orElseGet(() -> resolver.resolveConfigId(workspaceId, ModelType.CHAT.value(), ModelUsage.ROUTER.value()));
            return resolver.getConfig(id);
        } catch (Exception e) {
            log.warn("按用途解析记忆模型配置失败: {}", e.getMessage());
            return null;
        }
    }

    private Optional<EvidenceReranker> rerankerInstance(Long configId) {
        String cacheKey = "rerank:" + configId;
        EvidenceReranker cached = rerankerCache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            ModelConfig cfg = resolver.getConfig(configId);
            RerankClient client = RerankClient.from(cfg, rerankProps, objectMapper);
            rerankerCache.put(cacheKey, client);
            return Optional.of(client);
        } catch (Exception e) {
            log.warn("重排模型实例创建失败，问答将降级为按 ES 分截断: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void evictCache() {
        chatModelCache.clear();
        embeddingModelCache.clear();
        rerankerCache.clear();
        resolver.evictCache();
    }

    public boolean testConnection(ModelConfig cfg) {
        if (ModelType.RERANK.is(cfg.getModelType())) {
            return RerankClient.testConnection(cfg, rerankProps, objectMapper);
        }
        ModelProvider provider = providerMap.get(cfg.getProvider());
        if (provider == null) {
            throw new BizException("不支持的模型供应商: " + cfg.getProvider());
        }
        return provider.testConnection(cfg);
    }

    private ModelProvider provider(ModelConfig cfg) {
        ModelProvider provider = providerMap.get(cfg.getProvider());
        if (provider == null) {
            throw new BizException("不支持的模型供应商: " + cfg.getProvider());
        }
        return provider;
    }
}