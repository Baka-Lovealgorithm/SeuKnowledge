package com.ai.konwledgerepo.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskLock 单元测试：fail-open 语义（Redis 故障放行，由 DB 层 CAS 兜底）。
 */
class TaskLockTest {

    private RedisCacheService redisCacheService;
    private TaskLock taskLock;

    @BeforeEach
    void setUp() {
        redisCacheService = mock(RedisCacheService.class);
        taskLock = new TaskLock(redisCacheService);
    }

    @Test
    void tryAcquire_returnsFalse_whenKeyExists() {
        when(redisCacheService.setIfAbsent(eq("k"), eq("1"), any())).thenReturn(false);
        assertFalse(taskLock.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test
    void tryAcquire_returnsTrue_whenKeyNotExists() {
        when(redisCacheService.setIfAbsent(eq("k"), eq("1"), any())).thenReturn(true);
        assertTrue(taskLock.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test
    void tryAcquire_failOpen_returnsTrue_whenRedisDown() {
        // null → Redis 故障，放行（fail-open）
        when(redisCacheService.setIfAbsent(eq("k"), eq("1"), any())).thenReturn(null);
        assertTrue(taskLock.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test
    void release_deletesKey() {
        taskLock.release("k");
        verify(redisCacheService).delete("k");
    }
}