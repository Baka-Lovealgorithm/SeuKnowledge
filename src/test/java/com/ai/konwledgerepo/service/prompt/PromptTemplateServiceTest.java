package com.ai.konwledgerepo.service.prompt;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.PromptTemplateResponse;
import com.ai.konwledgerepo.entity.PromptTemplate;
import com.ai.konwledgerepo.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提示词模板服务测试：缓存读、DB 回填、更新 version+1 与缓存失效、重置默认。
 */
class PromptTemplateServiceTest {

    private PromptTemplateRepository repository;
    private RedisCacheService redis;
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PromptTemplateRepository.class);
        redis = mock(RedisCacheService.class);
        service = new PromptTemplateService(repository, redis, new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
    }

    private PromptTemplate tpl(String key, String content, int version) {
        PromptTemplate t = new PromptTemplate();
        t.setKey(key);
        t.setScopeType(PromptTemplateService.SCOPE_PLATFORM);
        t.setContent(content);
        t.setVersion(version);
        return t;
    }

    @Test
    void getContent_cacheHit_returnsWithoutDb() {
        when(redis.getString(RedisKeys.promptTemplate("chat-only"))).thenReturn(Optional.of("缓存内容"));
        assertEquals("缓存内容", service.getContent("chat-only"));
        verify(repository, never()).findByKeyAndScopeTypeAndScopeId(anyString(), anyString(), any());
    }

    @Test
    void getContent_cacheMiss_readsDbAndBackfills() {
        when(redis.getString(RedisKeys.promptTemplate("chat-only"))).thenReturn(Optional.empty());
        when(repository.findByKeyAndScopeTypeAndScopeId("chat-only", "PLATFORM", null))
                .thenReturn(Optional.of(tpl("chat-only", "DB 内容", 3)));
        assertEquals("DB 内容", service.getContent("chat-only"));
        verify(redis).setString(eq(RedisKeys.promptTemplate("chat-only")), eq("DB 内容"), any());
    }

    @Test
    void getContent_missing_returnsNull() {
        when(redis.getString(RedisKeys.promptTemplate("chat-only"))).thenReturn(Optional.empty());
        when(repository.findByKeyAndScopeTypeAndScopeId("chat-only", "PLATFORM", null))
                .thenReturn(Optional.empty());
        assertNull(service.getContent("chat-only"));
    }

    @Test
    void update_bumpsVersionAndEvictsCache() {
        when(repository.findByKeyAndScopeTypeAndScopeId("chat-only", "PLATFORM", null))
                .thenReturn(Optional.of(tpl("chat-only", "旧内容", 1)));
        PromptTemplateResponse resp = service.update("chat-only", "新内容", 42L);
        assertEquals("新内容", resp.content());
        assertEquals(2, resp.version());
        assertEquals(42L, resp.updatedBy());
        verify(redis).delete(RedisKeys.promptTemplate("chat-only"));
        verify(repository).save(any(PromptTemplate.class));
    }

    @Test
    void update_unknownKey_throws() {
        when(repository.findByKeyAndScopeTypeAndScopeId("nope", "PLATFORM", null))
                .thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.update("nope", "内容", 1L));
    }

    @Test
    void resetToDefault_restoresClasspathContent() {
        when(repository.findByKeyAndScopeTypeAndScopeId("chat-only", "PLATFORM", null))
                .thenReturn(Optional.of(tpl("chat-only", "被改坏了", 5)));
        PromptTemplateResponse resp = service.resetToDefault("chat-only");
        assertNotNull(resp.content());
        assertTrue(resp.content().contains("{{question}}"), "应恢复 classpath 默认模板: " + resp.content());
        verify(redis).delete(RedisKeys.promptTemplate("chat-only"));
    }

    @Test
    void preview_rendersWithVariables() {
        when(redis.getString(RedisKeys.promptTemplate("chat-only"))).thenReturn(Optional.empty());
        when(repository.findByKeyAndScopeTypeAndScopeId("chat-only", "PLATFORM", null))
                .thenReturn(Optional.of(tpl("chat-only", "用户：{{question}}", 1)));
        String out = service.preview("chat-only", java.util.Map.of("question", "你好"));
        assertEquals("用户：你好", out);
    }
}
