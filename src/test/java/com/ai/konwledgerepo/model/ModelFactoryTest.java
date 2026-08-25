package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型工厂测试（多工作空间）：解析严格限定 workspaceId，跨空间不回退；
 * 解析结果按空间缓存；配置缺失时报错；重排模型（RERANK）可选解析不抛异常。
 */
class ModelFactoryTest {

    private static final long WS = 7L;

    private ModelConfigRepository repo;
    private RedisCacheService cache;
    private ModelProvider provider;
    private ModelFactory factory;

    @BeforeEach
    void setUp() {
        repo = mock(ModelConfigRepository.class);
        cache = mock(RedisCacheService.class);
        provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("TEST");
        factory = new ModelFactory(repo, cache, new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400),
                List.of(provider), new SeuRerankProperties(4, 4, 20, 1500, 10000), new ObjectMapper());
    }

    private ModelConfig cfg(long id, String type, String usage) {
        ModelConfig c = new ModelConfig();
        c.setId(id);
        c.setProvider("TEST");
        c.setModelType(type);
        c.setUsage(usage);
        c.setModelName("m" + id);
        c.setEnabled(true);
        c.setIsDefault(false);
        return c;
    }

    /** RERANK 类型配置（DASHSCOPE；apiKey 用明文，env: 引用解析由 ModelKeyResolverTest 覆盖） */
    private ModelConfig rerankCfg(long id, String usage) {
        ModelConfig c = new ModelConfig();
        c.setId(id);
        c.setProvider("DASHSCOPE");
        c.setModelType("RERANK");
        c.setUsage(usage);
        c.setModelName("gte-rerank-v2");
        c.setApiKey("sk-test");
        c.setEnabled(true);
        c.setIsDefault(false);
        return c;
    }

    @Test
    void getChatModelByUsage_resolutionCachedInRedis_noRepeatDbQuery() {
        String resolveKey = RedisKeys.modelResolve(WS, "CHAT", "GENERATE");
        // 首次 miss（回源 DB 解析并回填），第二次命中 Redis 解析结果
        when(cache.getString(resolveKey))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of("1"));
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "CHAT", "GENERATE"))
                .thenReturn(Optional.of(cfg(1L, "CHAT", "GENERATE")));
        when(cache.get(eq(RedisKeys.modelConfig(1L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, "CHAT", "GENERATE")));
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel first = factory.getChatModelByUsage("GENERATE", WS);
        ChatModel second = factory.getChatModelByUsage("GENERATE", WS);

        assertSame(model, first);
        assertSame(model, second, "模型实例应进程内复用");
        verify(repo, times(1)).findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "CHAT", "GENERATE");
        verify(cache).setString(eq(resolveKey), eq("1"), any());
    }

    @Test
    void getChatModelByUsage_usageThenGenericThenDefault_fallbackChain() {
        String resolveKey = RedisKeys.modelResolve(WS, "CHAT", "EXTRACT");
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "CHAT", "EXTRACT"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, "CHAT"))
                .thenReturn(Optional.of(cfg(2L, "CHAT", null)));
        when(cache.get(eq(RedisKeys.modelConfig(2L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(2L)).thenReturn(Optional.of(cfg(2L, "CHAT", null)));
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getChatModelByUsage("EXTRACT", WS);

        assertSame(model, result);
        verify(cache).setString(eq(resolveKey), eq("2"), any());
    }

    @Test
    void resolution_workspaceScoped_noCrossWorkspaceFallback() {
        // 空间 7 无任何模型 → 报错（不得回退到其它空间的配置）
        when(cache.getString(RedisKeys.modelResolve(WS, "CHAT", "GENERATE"))).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "CHAT", "GENERATE"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, "CHAT"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(WS, "CHAT"))
                .thenReturn(Optional.empty());
        when(repo.findByWorkspaceIdAndModelTypeAndEnabledTrueOrderByIdAsc(WS, "CHAT")).thenReturn(List.of());

        assertThrows(Exception.class, () -> factory.getChatModelByUsage("GENERATE", WS));
        // 其它空间存在配置也不得被引用
        verify(repo, times(0)).findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(99L, "CHAT", "GENERATE");
    }

    @Test
    void getVisionModel_usesUppercaseVisionResolveKeyAndUsage() {
        // 有意行为变更回归：识图解析键（类型位 + 用途位）统一为大写 VISION，
        // 修复旧 "vision" 小写用途位与 repository 查询大写不一致导致的缓存键不命中潜在 bug
        String resolveKey = RedisKeys.modelResolve(WS, "VISION", "VISION");
        assertEquals("seuknowledge:model:resolve:" + WS + ":VISION:VISION", resolveKey);
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, "VISION"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "VISION", "VISION"))
                .thenReturn(Optional.of(cfg(9L, "VISION", "VISION")));
        when(cache.get(eq(RedisKeys.modelConfig(9L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(9L)).thenReturn(Optional.of(cfg(9L, "VISION", "VISION")));
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        Optional<ChatModel> result = factory.getVisionModel(WS);

        assertTrue(result.isPresent());
        assertSame(model, result.get());
        verify(cache).setString(eq(resolveKey), eq("9"), any());
        // 旧小写用途键不再被读写
        verify(cache, times(0)).getString(RedisKeys.modelResolve(WS, "VISION", "vision"));
        verify(cache, times(0)).setString(eq(RedisKeys.modelResolve(WS, "VISION", "vision")), any(), any());
    }

    @Test
    void getReranker_resolvesRerankConfig_returnsConfiguredClient() {
        // 用途 RERANK 命中 → 创建 RerankClient（DASHSCOPE 默认端点），apiKey 非空 → isConfigured true
        String resolveKey = RedisKeys.modelResolve(WS, "RERANK", "RERANK");
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "RERANK", "RERANK"))
                .thenReturn(Optional.of(rerankCfg(5L, "RERANK")));
        when(cache.get(eq(RedisKeys.modelConfig(5L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(5L)).thenReturn(Optional.of(rerankCfg(5L, "RERANK")));

        Optional<EvidenceReranker> result = factory.getReranker(WS);

        assertTrue(result.isPresent(), "配置了 RERANK 模型应返回精排客户端");
        assertTrue(result.get().isConfigured(), "apiKey 非空应视为已配置");
        verify(cache).setString(eq(resolveKey), eq("5"), any());
    }

    @Test
    void getReranker_noConfig_returnsEmptyWithoutThrowing() {
        // 未配置任何 RERANK 模型 → Optional.empty（不抛异常，问答降级 ES 分）
        String resolveKey = RedisKeys.modelResolve(WS, "RERANK", "RERANK");
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "RERANK", "RERANK"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, "RERANK"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(WS, "RERANK"))
                .thenReturn(Optional.empty());

        Optional<EvidenceReranker> result = factory.getReranker(WS);

        assertTrue(result.isEmpty(), "未配置重排模型应返回 empty 而非抛异常");
        verify(cache, times(0)).setString(eq(resolveKey), any(), any());
    }

    @Test
    void getReranker_invalidOpenAiCompatMissingBaseUrl_returnsEmpty() {
        // OpenAI 兼容但缺 baseUrl → 实例创建失败应返回 empty（降级），不破坏问答链路
        String resolveKey = RedisKeys.modelResolve(WS, "RERANK", "RERANK");
        ModelConfig bad = new ModelConfig();
        bad.setId(6L);
        bad.setProvider("OPENAI_COMPAT");
        bad.setModelType("RERANK");
        bad.setUsage("RERANK");
        bad.setModelName("rerank-model");
        bad.setApiKey("env:ALIBABA_API_KEY");
        bad.setEnabled(true);
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "RERANK", "RERANK"))
                .thenReturn(Optional.of(bad));
        when(cache.get(eq(RedisKeys.modelConfig(6L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(6L)).thenReturn(Optional.of(bad));

        Optional<EvidenceReranker> result = factory.getReranker(WS);

        assertTrue(result.isEmpty(), "OpenAI 兼容缺 baseUrl 应返回 empty（降级），不抛异常");
    }

    @Test
    void evictCache_clearsRedisModelKeys() {
        factory.evictCache();
        verify(cache).deleteByPattern(RedisKeys.PREFIX + "model:*");
    }

    @Test
    void getTitleChatModel_titleTypeConfigured_usesTitleModel() {
        // TITLE 类型 + usage=TITLE 命中 → 创建 ChatModel，缓存键回填
        String resolveKey = RedisKeys.modelResolve(WS, "TITLE", "TITLE");
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "TITLE", "TITLE"))
                .thenReturn(Optional.of(cfg(10L, "TITLE", "TITLE")));
        when(cache.get(eq(RedisKeys.modelConfig(10L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(10L)).thenReturn(Optional.of(cfg(10L, "TITLE", "TITLE")));
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getTitleChatModel(WS);

        assertSame(model, result);
        verify(cache).setString(eq(resolveKey), eq("10"), any());
        // 未触发 GENERATE 回退——TITLE 泛型/默认链在本测试中未走到（usage=TITLE 直接命中）
        verify(repo, times(0)).findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "CHAT", "GENERATE");
    }

    @Test
    void getTitleChatModel_titleGenericUsedWhenUsageNotMatched() {
        // usage=TITLE 未命中，回退 TITLE 通用（usage null）
        String resolveKey = RedisKeys.modelResolve(WS, "TITLE", "TITLE");
        when(cache.getString(resolveKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "TITLE", "TITLE"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, "TITLE"))
                .thenReturn(Optional.of(cfg(11L, "TITLE", null)));
        when(cache.get(eq(RedisKeys.modelConfig(11L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(11L)).thenReturn(Optional.of(cfg(11L, "TITLE", null)));
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getTitleChatModel(WS);

        assertSame(model, result);
        verify(cache).setString(eq(resolveKey), eq("11"), any());
    }

    @Test
    void getTitleChatModel_noTitleConfig_fallsBackToGenerate() {
        // TITLE 类型完全未配置 → 回退 CHAT GENERATE
        String titleKey = RedisKeys.modelResolve(WS, "TITLE", "TITLE");
        when(cache.getString(titleKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "TITLE", "TITLE"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, "TITLE"))
                .thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndIsDefaultTrueAndEnabledTrue(WS, "TITLE"))
                .thenReturn(Optional.empty());

        // GENERATE 回退链：usage=GENERATE 命中
        String genKey = RedisKeys.modelResolve(WS, "CHAT", "GENERATE");
        when(cache.getString(genKey)).thenReturn(Optional.empty());
        when(repo.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, "CHAT", "GENERATE"))
                .thenReturn(Optional.of(cfg(1L, "CHAT", "GENERATE")));
        when(cache.get(eq(RedisKeys.modelConfig(1L)), any(Class.class))).thenReturn(Optional.empty());
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, "CHAT", "GENERATE")));
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getTitleChatModel(WS);

        assertSame(model, result);
        verify(cache).setString(eq(genKey), eq("1"), any());
        // TITLE 未命中不缓存
        verify(cache, times(0)).setString(eq(titleKey), any(), any());
    }
}
