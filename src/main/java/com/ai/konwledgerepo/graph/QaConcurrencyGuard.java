package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QA 链路全局并发保护：公平信号量限制同时进入链路的问答数，防止并发过高触发上游 LLM 429
 * （TokenRythm 429 fail-open 会假满分 fairness=1.0）。
 * <p>
 * 同步 ask（Tomcat 虚拟线程）与流式 ask（@Async 平台线程池）均经 {@code QaGraphRunner}
 * 统一 acquire/release，形成单实例级背压。
 * <p>
 * 并发上限与超时由 {@code seuknowledge.qa.concurrency-limit} / {@code concurrency-timeout-seconds} 配置，
 * 环境变量 {@code KB_CONCURRENCY_LIMIT} / {@code KB_CONCURRENCY_TIMEOUT} 可覆盖。
 */
@Component
public class QaConcurrencyGuard {

    private static final Logger log = LoggerFactory.getLogger(QaConcurrencyGuard.class);

    private final Semaphore semaphore;
    private final int timeoutSeconds;
    private final AtomicLong acquired = new AtomicLong(0);
    private final AtomicLong released = new AtomicLong(0);
    private final AtomicLong timedOut = new AtomicLong(0);

    public QaConcurrencyGuard(SeuQaProperties props) {
        int limit = Math.max(1, props.concurrencyLimit());
        this.semaphore = new Semaphore(limit, true);
        this.timeoutSeconds = Math.max(1, props.concurrencyTimeoutSeconds());
        log.info("QA 并发保护初始化：上限={} 超时={}s（公平信号量）", limit, timeoutSeconds);
    }

    /**
     * 获取许可（阻塞等待，超时抛 BizException）。
     * 调用方在 {@code finally} 中必须调用 {@link #release()}。
     */
    public void acquire() {
        boolean ok;
        try {
            ok = semaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("系统繁忙，请稍后再试");
        }
        if (!ok) {
            timedOut.incrementAndGet();
            throw new BizException("系统繁忙，请稍后再试");
        }
        acquired.incrementAndGet();
    }

    /** 释放许可 */
    public void release() {
        semaphore.release();
        released.incrementAndGet();
    }

    // ---- 可观测性 ----

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public long acquiredCount() {
        return acquired.get();
    }

    public long releasedCount() {
        return released.get();
    }

    public long timedOutCount() {
        return timedOut.get();
    }
}