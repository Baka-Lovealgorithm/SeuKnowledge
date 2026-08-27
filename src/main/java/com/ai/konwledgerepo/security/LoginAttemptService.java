package com.ai.konwledgerepo.security;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录防爆破：失败计数（Redis INCR）窗口 + 独立锁定标记。
 * <p>
 * 连续失败达 {@code loginFailMax} 次时，set 锁定键（TTL {@code loginLockSeconds}），
 * 锁定期间即使用户名/密码正确也拒绝登录（统一返回"用户名或密码错误"防用户名枚举），
 * 锁定到期后自动解锁；登录成功后清除失败计数与锁定标记。
 * Redis 不可用时全部放行（fail-open），维持项目降级惯例。
 * <p>
 * 使用方：{@link AuthService}（登录入口）。
 */
@Component
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final RedisCacheService redis;
    private final int maxFailures;
    private final Duration failWindow;
    private final Duration lockDuration;

    public LoginAttemptService(RedisCacheService redis, SeuSecurityProperties props) {
        this.redis = redis;
        this.maxFailures = Math.max(1, props.loginFailMax());
        this.failWindow = Duration.ofSeconds(Math.max(1, props.loginFailWindowSeconds()));
        this.lockDuration = Duration.ofSeconds(Math.max(1, props.loginLockSeconds()));
    }

    /** 当前用户名是否已被锁定（Redis 故障 → 放行） */
    public boolean isLocked(String username) {
        return redis.getString(RedisKeys.loginLock(username)).isPresent();
    }

    /** 记录一次失败；连续失败达到阈值时设置锁定标记（Redis 故障 → 静默跳过） */
    public void recordFailure(String username) {
        Long count = redis.increment(RedisKeys.loginFail(username), failWindow);
        if (count != null && count >= maxFailures) {
            redis.setString(RedisKeys.loginLock(username), "1", lockDuration);
        }
    }

    /** 登录成功后清除失败计数与锁定标记 */
    public void clear(String username) {
        redis.delete(RedisKeys.loginFail(username));
        redis.delete(RedisKeys.loginLock(username));
    }
}