package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 Token 管理（Redis 版）：
 * - 主存储：Redis string {@code seuknowledge:token:{token}} → userId，TTL 默认 7 天（可配），
 *   重启不丢失、多实例共享、自动过期；
 * - 降级：Redis 不可用时写入进程内内存兜底（含过期时间），保证 Redis 故障期间登录可用；
 * - 接口签名与一期保持一致（create / resolve / remove），调用方零改动。
 */
@Component
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /** Redis 故障期间的内存兜底：token → (userId, 过期时间戳) */
    private final Map<String, long[]> fallback = new ConcurrentHashMap<>();

    public TokenService(StringRedisTemplate redis, SeuSecurityProperties securityProps) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(Math.max(1, securityProps.tokenTtlSeconds()));
    }

    public String create(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set(RedisKeys.token(token), String.valueOf(userId), ttl);
        } catch (Exception e) {
            // Redis 不可用：降级内存兜底，保证登录可用（重启或 Redis 恢复后此部分 token 失效）
            fallback.put(token, new long[]{userId, System.currentTimeMillis() + ttl.toMillis()});
            log.warn("Redis 不可用，token 降级内存存储（Redis 恢复后重新登录）: {}", e.getMessage());
        }
        return token;
    }

    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String userId = redis.opsForValue().get(RedisKeys.token(token));
            return Optional.ofNullable(userId).map(Long::valueOf);
        } catch (Exception e) {
            long[] entry = fallback.get(token);
            if (entry == null || entry[1] < System.currentTimeMillis()) {
                fallback.remove(token);
                return Optional.empty();
            }
            return Optional.of(entry[0]);
        }
    }

    public void remove(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        fallback.remove(token);
        try {
            redis.delete(RedisKeys.token(token));
        } catch (Exception e) {
            log.warn("Redis 删除 token 失败（内存兜底已清理）: {}", e.getMessage());
        }
    }
}
