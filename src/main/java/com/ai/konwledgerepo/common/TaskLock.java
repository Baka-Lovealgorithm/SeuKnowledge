package com.ai.konwledgerepo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis SETNX 互斥轻量封装（fail-open）：占位成功返回 true，失败返回 false。
 * 唯一例外：Redis 故障（setIfAbsent 返回 null）→ 视为放行（true），
 * 由 DB 层 CAS 兜底保证正确性——互斥锁是快速路径，不是唯一防线。
 * <p>
 * 持有者校验（F-1 加强）：acquire 时 value 写入随机 owner token 并在本实例 {@link #owners} 记录，
 * release 时经 Lua「GET == owner 才 DEL」原子释放，避免 TTL 过期后误删被他人重新获取的锁。
 * fail-open 获取（Redis 故障放行）不记录 token，release 时静默跳过（无锁可删，TTL 最终清理）。
 * <p>
 * 使用方：{@link com.ai.konwledgerepo.service.document.DocumentParseExecutor}（解析 in-flight guard）、
 * {@link com.ai.konwledgerepo.service.extract.ExtractTaskExecutor}（任务/文档互斥）、
 * {@link com.ai.konwledgerepo.service.chat.QaExecutionService}（同会话问答串行 askLock）。
 */
@Component
public class TaskLock {

    private static final Logger log = LoggerFactory.getLogger(TaskLock.class);

    private final RedisCacheService redisCacheService;

    /** 本实例获取的锁 owner token：key -> token（仅 acquire 成功时记录，release 时移除） */
    private final ConcurrentHashMap<String, String> owners = new ConcurrentHashMap<>();

    public TaskLock(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    /**
     * 尝试获取互斥锁（SETNX + TTL + owner token）。
     * {@code true} 获取成功可以执行；{@code false} 已被占用，应跳过。
     * Redis 不可用时返回 {@code true}（fail-open：由 DB 层 CAS 兜底），不记录 owner。
     */
    public boolean tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisCacheService.setIfAbsent(key, token, ttl);
        // null → Redis 故障，放行（不记录 owner，release 时静默）
        if (ok == null) {
            return true;
        }
        if (ok) {
            owners.put(key, token);
            return true;
        }
        return false;
    }

    /**
     * 释放互斥锁（原子校验持有者）：仅当 key 当前值为本实例写入的 owner token 时才删除，
     * 避免误删 TTL 过期后被他人重新获取的锁。fail-open 获取（无 owner 记录）时静默跳过。
     * Redis 不可用时静默降级（TTL 最终清理）。
     */
    public void release(String key) {
        String token = owners.remove(key);
        if (token == null) {
            // 未 acquire 过（或 fail-open 放行获取的），无锁可删
            return;
        }
        redisCacheService.releaseIfOwner(key, token);
    }
}
