package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析链测试，聚焦两条链的顺序：标题的三档专用链
 * {@code CHAT+TITLE}（现行绑定）→ {@code TITLE} 类型链（<b>历史存量行，零迁移</b>）→ {@code CHAT+GENERATE}；
 * 以及其余用途（含记忆）共用的标准链，验证它们<b>没有</b>专用回退。
 * <p>
 * Redis 用 mock（{@code Optional} 返回值默认 empty → 一律走 DB 回源路径），
 * repository 未 stub 的方法返回 empty/空集合，等价于「该档没有配置」。
 */
class ModelConfigResolverTest {

    private static final long WS = 1L;

    private ModelConfigRepository repository;
    private ModelConfigResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.getString(anyString())).thenReturn(Optional.empty());
        resolver = new ModelConfigResolver(repository, redis,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
    }

    private ModelConfig cfg(long id, String type, String usage) {
        ModelConfig c = new ModelConfig();
        c.setId(id);
        c.setProvider("DASHSCOPE");
        c.setModelType(type);
        c.setUsage(usage);
        c.setModelName("m" + id);
        c.setEnabled(true);
        c.setIsDefault(false);
        return c;
    }

    private void stubExact(String type, String usage, ModelConfig hit) {
        when(repository.findFirstByWorkspaceIdAndModelTypeAndUsageAndEnabledTrueOrderByIdAsc(WS, type, usage))
                .thenReturn(Optional.ofNullable(hit));
    }

    private void stubGeneric(String type, ModelConfig hit) {
        when(repository.findFirstByWorkspaceIdAndModelTypeAndUsageIsNullAndEnabledTrueOrderByIdAsc(WS, type))
                .thenReturn(Optional.ofNullable(hit));
    }

    @Test
    void resolveTitleConfigId_prefersNewChatTitleBinding() {
        stubExact("CHAT", "TITLE", cfg(20L, "CHAT", "TITLE"));
        // 历史类型行也在库里，但新绑定优先
        stubExact("TITLE", "TITLE", cfg(13L, "TITLE", "TITLE"));

        assertEquals(20L, resolver.resolveTitleConfigId(WS));
    }

    /**
     * 本次改版的前提：库里存量 model_type='TITLE' 行不迁移也必须继续生效。
     * <p>数据形状照着现网（workspace 1）来：CHAT 下有一条「用途留空的通用行」id14。
     * 若第一档误走整条链（含通用档），这里会被 id14 抢走 → 标题模型静默漂移成通用文本模型。
     */
    @Test
    void resolveTitleConfigId_legacyTitleRowNotShadowedByGenericChatRow() {
        stubGeneric("CHAT", cfg(14L, "CHAT", null));          // 现网 id14：文本模型·通用
        stubExact("TITLE", "TITLE", cfg(13L, "TITLE", "TITLE")); // 现网 id13：历史标题类型行

        assertEquals(13L, resolver.resolveTitleConfigId(WS), "历史 TITLE 行应由第二档兜住（零数据迁移），不得被 CHAT 通用行顶替");
    }

    @Test
    void resolveTitleConfigId_fallsBackToChatGenerate() {
        stubExact("TITLE", "TITLE", null);
        stubExact("CHAT", "GENERATE", cfg(12L, "CHAT", "GENERATE"));

        assertEquals(12L, resolver.resolveTitleConfigId(WS), "标题未绑定 → 回退 CHAT GENERATE（与改版前行为一致）");
    }

    /** 新绑定的 CHAT+TITLE 行不得被 CHAT 通用行抢先（第 1 档就是精确匹配） */
    @Test
    void resolveTitleConfigId_chatTitleBindingWinsOverGenericChatRow() {
        stubGeneric("CHAT", cfg(14L, "CHAT", null));
        stubExact("CHAT", "TITLE", cfg(21L, "CHAT", "TITLE"));

        assertEquals(21L, resolver.resolveTitleConfigId(WS));
    }

    /** 三档全空 → 由 GENERATE 档的 defaultConfig 抛出「未配置」业务异常，不静默返回 null */
    @Test
    void resolveTitleConfigId_nothingConfigured_throws() {
        BizException e = assertThrows(BizException.class, () -> resolver.resolveTitleConfigId(WS));
        assertTrue(e.getMessage().contains("CHAT"), "异常应指明缺的是 CHAT 类型: " + e.getMessage());
    }

    /**
     * 除标题外，CHAT 各用途槽位（含记忆 MEMORY）都只有标准四级链，<b>不得存在专用回退</b>：
     * 未绑定时按顺序回退「通用档」，即使该空间另有已启用的 ROUTER 精确行也不参与。
     * <p>历史上 MEMORY 曾写作「未命中 → 回退 ROUTER」，但它的判空用了整条链（通用档必然先命中），
     * 回退分支永不可达、可达时又必然抛「未配置启用的 CHAT 模型」，故已删除。此用例锁定该决定不被改回去。
     */
    @Test
    void resolveConfigId_chatMemory_noSpecialRouterDetour() {
        stubExact("CHAT", "MEMORY", null);
        stubExact("CHAT", "ROUTER", cfg(11L, "CHAT", "ROUTER"));
        stubGeneric("CHAT", cfg(14L, "CHAT", null));

        assertEquals(14L, resolver.resolveConfigId(WS, "CHAT", "MEMORY"),
                "记忆未绑定应回退通用档，而非绕道 ROUTER 精确行");
    }

    /** 解析结果写缓存（key 由 type+usage 决定），避免每次问答回源 */
    @Test
    void resolveConfigId_cachesResolvedId() {
        stubExact("CHAT", "VERIFY", cfg(8L, "CHAT", "VERIFY"));
        RedisCacheService redis = mock(RedisCacheService.class);
        when(redis.getString(anyString())).thenReturn(Optional.empty());
        ModelConfigResolver r = new ModelConfigResolver(repository, redis,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));

        assertEquals(8L, r.resolveConfigId(WS, "CHAT", "VERIFY"));

        verify(redis).setString(anyString(), eq("8"), any());
    }
}
