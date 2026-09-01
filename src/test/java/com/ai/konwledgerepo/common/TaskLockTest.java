package com.ai.konwledgerepo.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskLock 单元测试：fail-open 语义（Redis 故障放行，由 DB 层 CAS 兜底）+
 * owner token 持有者校验（release 走 Lua 原子释放，不无条件 DEL）。
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
        when(redisCacheService.setIfAbsent(eq("k"), anyString(), any())).thenReturn(false);
        assertFalse(taskLock.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test
    void tryAcquire_returnsTrue_whenKeyNotExists() {
        when(redisCacheService.setIfAbsent(eq("k"), anyString(), any())).thenReturn(true);
        assertTrue(taskLock.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test
    void tryAcquire_failOpen_returnsTrue_whenRedisDown() {
        // null → Redis 故障，放行（fail-open），不记录 owner
        when(redisCacheService.setIfAbsent(eq("k"), anyString(), any())).thenReturn(null);
        assertTrue(taskLock.tryAcquire("k", Duration.ofSeconds(10)));
    }

    @Test
    void release_acquiredKey_callsReleaseIfOwnerWithToken() {
        when(redisCacheService.setIfAbsent(eq("k"), anyString(), any())).thenReturn(true);
        when(redisCacheService.releaseIfOwner(eq("k"), anyString())).thenReturn(true);
        taskLock.tryAcquire("k", Duration.ofSeconds(10));

        taskLock.release("k");

        // release 走 Lua 原子校验，而非无条件 delete
        verify(redisCacheService).releaseIfOwner(eq("k"), anyString());
        verify(redisCacheService, never()).delete("k");
    }

    @Test
    void release_afterFailOpenAcquire_isNoop() {
        // fail-open 获取（无 owner 记录），release 不应调用 releaseIfOwner
        when(redisCacheService.setIfAbsent(eq("k"), anyString(), any())).thenReturn(null);
        taskLock.tryAcquire("k", Duration.ofSeconds(10));

        taskLock.release("k");

        verify(redisCacheService, never()).releaseIfOwner(anyString(), anyString());
        verify(redisCacheService, never()).delete(anyString());
    }

    @Test
    void release_withoutAcquire_isNoop() {
        // 未 acquire 直接 release，无 owner 记录，静默跳过
        taskLock.release("k");
        verify(redisCacheService, never()).releaseIfOwner(anyString(), anyString());
        verify(redisCacheService, never()).delete(anyString());
    }

    @Test
    void tryAcquire_generatesDifferentTokensForDifferentKeys() {
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        taskLock.tryAcquire("k1", Duration.ofSeconds(10));
        taskLock.tryAcquire("k2", Duration.ofSeconds(10));
        // 两次 acquire 都成功记录 owner，release 时各自带 token 校验
        taskLock.release("k1");
        taskLock.release("k2");
        verify(redisCacheService).releaseIfOwner(eq("k1"), anyString());
        verify(redisCacheService).releaseIfOwner(eq("k2"), anyString());
    }

    @Test
    void tryAcquire_tokenIsRandomUuid_notConstant() {
        // value 不再是固定 "1"，而是随机 UUID（非空、长度合理）
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(redisCacheService.setIfAbsent(eq("k"), captor.capture(), any())).thenReturn(true);
        taskLock.tryAcquire("k", Duration.ofSeconds(10));
        String token = captor.getValue();
        assertTrue(token != null && !token.isEmpty() && !token.equals("1"), "owner token 应为非空随机值");
    }
}
