package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.Hashing;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuRateLimitProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 问答限流与防重复提交（默认关闭，可开关配置）：
 * - 限流：seuknowledge:rl:{userId} INCR + 60s 过期，每分钟每用户最多 ask-per-minute 次；
 * - 防重：seuknowledge:dup:{userId}:{questionHash} SETNX + 5s，相同用户相同问题防双击提交。
 * Redis 不可用时全部放行（fail-open），不影响业务。
 */
@Component
public class RateLimitService {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration DUP_TTL = Duration.ofSeconds(5);

    private final RedisCacheService redisCacheService;
    private final boolean enabled;
    private final int askPerMinute;

    public RateLimitService(RedisCacheService redisCacheService, SeuRateLimitProperties rateLimitProps) {
        this.redisCacheService = redisCacheService;
        this.enabled = rateLimitProps.enabled();
        this.askPerMinute = Math.max(1, rateLimitProps.askPerMinute());
    }

    /** 每用户每分钟问答限流：允许返回 true，超限返回 false；Redis 不可用放行 */
    public boolean tryAcquireAsk(Long userId) {
        if (!enabled || userId == null) {
            return true;
        }
        Long count = redisCacheService.increment(RedisKeys.rateLimit(userId), WINDOW);
        return count == null || count <= askPerMinute;
    }

    /** 防重复提交：5 秒内相同用户相同问题仅放行一次；Redis 不可用放行 */
    public boolean tryDedup(Long userId, String question) {
        if (!enabled || userId == null || question == null || question.isBlank()) {
            return true;
        }
        String hash = Hashing.sha256Hex(question.strip());
        Boolean ok = redisCacheService.setIfAbsent(RedisKeys.dup(userId, hash), "1", DUP_TTL);
        return ok == null || ok;
    }
}
