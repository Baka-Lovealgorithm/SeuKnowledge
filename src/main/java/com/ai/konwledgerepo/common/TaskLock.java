package com.ai.konwledgerepo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis SETNX 互斥轻量封装（fail-open）：占位成功返回 true，失败返回 false。
 * 唯一例外：Redis 故障（setIfAbsent 返回 null）→ 视为放行（true），
 * 由 DB 层 CAS 兜底保证正确性——互斥锁是快速路径，不是唯一防线。
 * <p>
 * 使用方：{@link com.ai.konwledgerepo.service.document.DocumentParseExecutor}（解析 in-flight guard）、
 * {@link com.ai.konwledgerepo.service.extract.ExtractTaskExecutor}（任务/文档互斥）。
 */
@Component
public class TaskLock {

    private static final Logger log = LoggerFactory.getLogger(TaskLock.class);

    private final RedisCacheService redisCacheService;

    public TaskLock(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /**
     * 尝试获取互斥锁（SETNX + TTL）。
     * {@code true} 获取成功可以执行；{@code false} 已被占用，应跳过。
     * Redis 不可用时返回 {@code true}（fail-open：由 DB 层 CAS 兜底）。
     */
    public boolean tryAcquire(String key, Duration ttl) {
        Boolean ok = redisCacheService.setIfAbsent(key, "1", ttl);
        // null → Redis 故障，放行；false → 已被占用，拒绝
        return ok == null || ok;
    }

    /** 释放互斥锁（DEL）；Redis 不可用时静默降级（TTL 最终清理） */
    public void release(String key) {
        redisCacheService.delete(key);
    }
}