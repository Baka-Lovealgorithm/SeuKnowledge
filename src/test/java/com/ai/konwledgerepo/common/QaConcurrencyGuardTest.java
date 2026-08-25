package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.config.props.SeuQaProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA 全局并发保护测试：公平信号量限并发数；超时抛 BizException；release 后恢复许可。
 */
class QaConcurrencyGuardTest {

    @Test
    void concurrentAcquire_limitedByPermits() throws Exception {
        // limit=2：最多 2 个同时持锁，第 3 个等待（超时 5s 内不触发）
        QaConcurrencyGuard guard = new QaConcurrencyGuard(new SeuQaProperties(20, 2, 2, 5, true));
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    guard.acquire();
                    int now = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    guard.release();
                    allDone.countDown();
                    return null;
                });
            }
            start.countDown();
            assertTrue(allDone.await(30, TimeUnit.SECONDS), "8 个任务应在 30s 内全部完成");
            assertEquals(2, maxInFlight.get(), "同时持锁数不应超过 limit=2");
            assertEquals(8, guard.acquiredCount());
            assertEquals(8, guard.releasedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acquire_timeout_throwsBizException() {
        // limit=1、超时 1s：持锁后第二个 acquire 等待超时抛 BizException
        QaConcurrencyGuard guard = new QaConcurrencyGuard(new SeuQaProperties(20, 2, 1, 1, true));
        guard.acquire();
        try {
            assertThrows(BizException.class, guard::acquire, "超时应抛 BizException（系统繁忙）");
            assertEquals(1, guard.timedOutCount());
        } finally {
            guard.release();
        }
    }

    @Test
    void release_restoresPermit() {
        QaConcurrencyGuard guard = new QaConcurrencyGuard(new SeuQaProperties(20, 2, 1, 1, true));
        assertEquals(1, guard.availablePermits());
        guard.acquire();
        assertEquals(0, guard.availablePermits());
        guard.release();
        assertEquals(1, guard.availablePermits(), "release 后许可应恢复");
        assertEquals(1, guard.acquiredCount());
        assertEquals(1, guard.releasedCount());
    }
}