package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new TokenService(redis, new SeuSecurityProperties(604800, "admin", "admin123", List.of("*")));
    }

    @Test
    void create_storesUserIdWithTtl() {
        String token = service.create(42L);

        assertFalse(token.isBlank());
        verify(valueOps).set(eq(RedisKeys.token(token)), eq("42"), eq(Duration.ofSeconds(604800L)));
    }

    @Test
    void resolve_returnsUserIdFromRedis() {
        when(valueOps.get(RedisKeys.token("abc"))).thenReturn("7");

        Optional<Long> resolved = service.resolve("abc");

        assertTrue(resolved.isPresent());
        assertEquals(7L, resolved.get());
    }

    @Test
    void resolve_returnsEmptyWhenMissingOrBlank() {
        when(valueOps.get(any())).thenReturn(null);
        assertFalse(service.resolve("missing").isPresent());
        assertFalse(service.resolve(null).isPresent());
        assertFalse(service.resolve("  ").isPresent());
    }

    @Test
    void remove_deletesToken() {
        service.remove("abc");
        verify(redis).delete(RedisKeys.token("abc"));
    }

    @Test
    void redisFailure_fallsBackToMemory() {
        // Redis 写失败 → 降级内存兜底
        doThrow(new RuntimeException("redis down")).when(valueOps).set(any(), any(), any());
        String token = service.create(42L);

        // Redis 读也失败 → 从内存兜底解析
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));
        Optional<Long> resolved = service.resolve(token);

        assertTrue(resolved.isPresent());
        assertEquals(42L, resolved.get());
    }

    @Test
    void redisFailure_removeClearsFallback() {
        doThrow(new RuntimeException("redis down")).when(valueOps).set(any(), any(), any());
        String token = service.create(42L);
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));

        service.remove(token);

        assertFalse(service.resolve(token).isPresent(), "登出后内存兜底也应清除");
    }
}
