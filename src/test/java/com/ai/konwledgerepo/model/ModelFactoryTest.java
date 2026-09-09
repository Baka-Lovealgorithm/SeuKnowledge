package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 模型工厂测试（多工作空间）：解析委托 {@link ModelConfigResolver}，
 * 工厂负责实例创建与进程内缓存。
 */
class ModelFactoryTest {

    private static final long WS = 7L;

    private ModelConfigResolver resolver;
    private ModelProvider provider;
    private ModelFactory factory;

    @BeforeEach
    void setUp() {
        resolver = mock(ModelConfigResolver.class);
        provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("TEST");
        factory = new ModelFactory(resolver, List.of(provider),
                new SeuRerankProperties(4, 4, 20, 1500, 10000), new ObjectMapper());
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
    void getChatModelByUsage_resolvedByResolver() {
        ModelConfig config = cfg(1L, "CHAT", "GENERATE");
        when(resolver.resolveConfigId(WS, "CHAT", "GENERATE")).thenReturn(1L);
        when(resolver.getConfig(1L)).thenReturn(config);
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getChatModelByUsage("GENERATE", WS);

        assertSame(model, result);
        verify(resolver).resolveConfigId(WS, "CHAT", "GENERATE");
        verify(resolver).getConfig(1L);
    }

    @Test
    void getChatModelByUsage_cachesModelInstance() {
        ModelConfig config = cfg(1L, "CHAT", "GENERATE");
        when(resolver.resolveConfigId(WS, "CHAT", "GENERATE")).thenReturn(1L);
        when(resolver.getConfig(1L)).thenReturn(config);
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel first = factory.getChatModelByUsage("GENERATE", WS);
        ChatModel second = factory.getChatModelByUsage("GENERATE", WS);

        assertSame(model, first);
        assertSame(model, second, "模型实例应进程内复用");
        // resolveConfigId 每次调用都会查 Resolver（解析缓存由 Resolver 内部管理），但模型实例在 Factory 进程内缓存
        verify(resolver, times(2)).resolveConfigId(WS, "CHAT", "GENERATE");
    }

    @Test
    void resolution_workspaceScoped_noCrossWorkspaceFallback() {
        when(resolver.resolveConfigId(WS, "CHAT", "GENERATE"))
                .thenThrow(new BizException("模型不可用"));

        assertThrows(BizException.class, () -> factory.getChatModelByUsage("GENERATE", WS));
    }

    @Test
    void getVisionModel_usesResolver() {
        ModelConfig config = cfg(9L, "VISION", "VISION");
        when(resolver.tryResolveConfigId(WS, "VISION", "VISION")).thenReturn(Optional.of(9L));
        when(resolver.getConfig(9L)).thenReturn(config);
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        Optional<ChatModel> result = factory.getVisionModel(WS);

        assertTrue(result.isPresent());
        assertSame(model, result.get());
    }

    @Test
    void getVisionModel_noConfig_returnsEmpty() {
        when(resolver.tryResolveConfigId(WS, "VISION", "VISION")).thenReturn(Optional.empty());

        Optional<ChatModel> result = factory.getVisionModel(WS);

        assertTrue(result.isEmpty());
    }

    @Test
    void getReranker_resolvesRerankConfig_returnsConfiguredClient() {
        ModelConfig config = rerankCfg(5L, "RERANK");
        when(resolver.tryResolveConfigId(WS, "RERANK", "RERANK")).thenReturn(Optional.of(5L));
        when(resolver.getConfig(5L)).thenReturn(config);

        Optional<EvidenceReranker> result = factory.getReranker(WS);

        assertTrue(result.isPresent(), "配置了 RERANK 模型应返回精排客户端");
        assertTrue(result.get().isConfigured(), "apiKey 非空应视为已配置");
    }

    @Test
    void getReranker_noConfig_returnsEmptyWithoutThrowing() {
        when(resolver.tryResolveConfigId(WS, "RERANK", "RERANK")).thenReturn(Optional.empty());

        Optional<EvidenceReranker> result = factory.getReranker(WS);

        assertTrue(result.isEmpty(), "未配置重排模型应返回 empty 而非抛异常");
    }

    @Test
    void getReranker_invalidConfig_returnsEmpty() {
        ModelConfig bad = new ModelConfig();
        bad.setId(6L);
        bad.setProvider("OPENAI_COMPAT");
        bad.setModelType("RERANK");
        bad.setUsage("RERANK");
        bad.setModelName("rerank-model");
        bad.setApiKey("env:ALIBABA_API_KEY");
        bad.setEnabled(true);
        when(resolver.tryResolveConfigId(WS, "RERANK", "RERANK")).thenReturn(Optional.of(6L));
        when(resolver.getConfig(6L)).thenReturn(bad);

        Optional<EvidenceReranker> result = factory.getReranker(WS);

        assertTrue(result.isEmpty(), "OpenAI 兼容缺 baseUrl 应返回 empty（降级），不抛异常");
    }

    @Test
    void evictCache_clearsResolverCache() {
        factory.evictCache();
        verify(resolver).evictCache();
    }

    @Test
    void getTitleChatModel_titleUsageConfigured_usesTitleModel() {
        ModelConfig config = cfg(10L, "CHAT", "TITLE");
        when(resolver.resolveTitleConfigId(WS)).thenReturn(10L);
        when(resolver.getConfig(10L)).thenReturn(config);
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getTitleChatModel(WS);

        assertSame(model, result);
    }

    @Test
    void getTitleChatModel_noTitleConfig_fallsBackToGenerate() {
        // 标题绑定（CHAT+TITLE）与历史 TITLE 行均未命中 → resolveTitleConfigId 回退 CHAT GENERATE
        ModelConfig config = cfg(1L, "CHAT", "GENERATE");
        when(resolver.resolveTitleConfigId(WS)).thenReturn(1L);
        when(resolver.getConfig(1L)).thenReturn(config);
        ChatModel model = mock(ChatModel.class);
        when(provider.createChatModel(any())).thenReturn(model);

        ChatModel result = factory.getTitleChatModel(WS);

        assertSame(model, result);
    }

    @Test
    void resolveMemoryChatConfig_memoryConfigured_returnsMemoryConfig() {
        ModelConfig config = cfg(11L, "CHAT", "MEMORY");
        when(resolver.tryResolveConfigId(WS, "CHAT", "MEMORY")).thenReturn(Optional.of(11L));
        when(resolver.getConfig(11L)).thenReturn(config);

        ModelConfig result = factory.resolveMemoryChatConfig(WS);

        assertSame(config, result);
    }

    @Test
    void resolveMemoryChatConfig_noMemory_fallsBackToRouter() {
        // MEMORY 未配置 → 与 getMemoryChatModel 同款 ROUTER 回退
        ModelConfig config = cfg(12L, "CHAT", "ROUTER");
        when(resolver.tryResolveConfigId(WS, "CHAT", "MEMORY")).thenReturn(Optional.empty());
        when(resolver.resolveConfigId(WS, "CHAT", "ROUTER")).thenReturn(12L);
        when(resolver.getConfig(12L)).thenReturn(config);

        ModelConfig result = factory.resolveMemoryChatConfig(WS);

        assertSame(config, result);
    }

    @Test
    void resolveMemoryChatConfig_resolutionFails_returnsNull() {
        // 解析异常 → null（调用方按无配置处理，如 JudgeOptions 仅限 maxTokens）
        when(resolver.tryResolveConfigId(WS, "CHAT", "MEMORY")).thenReturn(Optional.empty());
        when(resolver.resolveConfigId(WS, "CHAT", "ROUTER")).thenThrow(new BizException("模型不可用"));

        assertNull(factory.resolveMemoryChatConfig(WS));
    }
}