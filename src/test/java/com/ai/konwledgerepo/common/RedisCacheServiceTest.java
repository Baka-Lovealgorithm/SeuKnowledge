package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.dto.ChatMessageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RedisCacheService service;
    private ObjectMapper mapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // 模拟 Spring Boot 自动配置的 ObjectMapper（JavaTimeModule + 不序列化为时间戳）
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new RedisCacheService(redis, mapper);
    }

    @Test
    void setAndGet_roundTripsRecordWithLocalDateTime() {
        ChatMessageResponse msg = new ChatMessageResponse(1L, "USER", "你好", "[]",
                LocalDateTime.of(2025, 1, 1, 10, 30), false);

        service.set("k", msg, Duration.ofSeconds(60));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("k"), captor.capture(), eq(Duration.ofSeconds(60)));
        when(valueOps.get("k")).thenReturn(captor.getValue());

        Optional<ChatMessageResponse> got = service.get("k", ChatMessageResponse.class);
        assertTrue(got.isPresent());
        assertEquals("你好", got.get().content());
        assertEquals(msg.createdAt(), got.get().createdAt(), "LocalDateTime 应可正确序列化/反序列化");
    }

    @Test
    void setAndGet_roundTripsGenericList() {
        List<ChatMessageResponse> list = List.of(
                new ChatMessageResponse(1L, "USER", "q1", null, LocalDateTime.now(), false));

        service.set("k", list, Duration.ofSeconds(60));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("k"), captor.capture(), eq(Duration.ofSeconds(60)));
        when(valueOps.get("k")).thenReturn(captor.getValue());

        Optional<List<ChatMessageResponse>> got = service.get("k",
                new com.fasterxml.jackson.core.type.TypeReference<List<ChatMessageResponse>>() {
                });
        assertTrue(got.isPresent());
        assertEquals(1, got.get().size());
        assertEquals("q1", got.get().get(0).content());
    }

    @Test
    void get_missingReturnsEmpty() {
        when(valueOps.get("k")).thenReturn(null);
        assertFalse(service.get("k", String.class).isPresent());
    }

    @Test
    void redisFailure_getReturnsEmpty_failOpen() {
        when(valueOps.get("k")).thenThrow(new RuntimeException("redis down"));
        assertFalse(service.get("k", String.class).isPresent(), "Redis 故障应回源 DB（fail-open）");
        assertFalse(service.getString("k").isPresent());
    }

    @Test
    void redisFailure_setIsSwallowed_failOpen() {
        doThrow(new RuntimeException("redis down")).when(valueOps).set(anyString(), anyString(), any());
        assertDoesNotThrow(() -> service.set("k", "v", Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> service.setString("k", "v", Duration.ofSeconds(1)));
    }

    @Test
    void redisFailure_incrementReturnsNull_failOpen() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        assertNull(service.increment("k", Duration.ofSeconds(60)));
    }

    @Test
    void redisFailure_setIfAbsentReturnsNull_failOpen() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenThrow(new RuntimeException("redis down"));
        assertNull(service.setIfAbsent("k", "v", Duration.ofSeconds(5)));
    }

    @Test
    void increment_setsTtlOnFirstIncrement() {
        when(valueOps.increment("k")).thenReturn(1L);
        assertEquals(1L, service.increment("k", Duration.ofSeconds(60)));
        verify(redis).expire("k", Duration.ofSeconds(60));
    }

    @Test
    void increment_subsequentNoTtlReset() {
        when(valueOps.increment("k")).thenReturn(3L);
        assertEquals(3L, service.increment("k", Duration.ofSeconds(60)));
        verify(redis, org.mockito.Mockito.never()).expire(anyString(), any());
    }
}
