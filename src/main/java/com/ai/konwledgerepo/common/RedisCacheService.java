package com.ai.konwledgerepo.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 缓存统一封装（fail-open）：
 * - 键值均以 JSON 字符串存储（复用 Spring 管理的 ObjectMapper，支持 LocalDateTime / record）；
 * - 所有操作 try/catch，Redis 不可用时读返回 empty、写静默丢弃，调用方回源 DB，应用不挂；
 * - 所有 set 必须携带 TTL，防止陈旧数据与内存膨胀；
 * - 降级可观测：任何操作失败都会记入 {@link #degraded} 标志，首次失败打聚合 WARN（列出受影响能力），
 *   后续失败降为 debug 防刷屏；任一次成功自动复位并打"已恢复"WARN，保证"Redis 挂了/恢复"永远可见。
 */
@Component
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** Redis 是否处于降级态（任一操作失败即置位，任一次成功复位） */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public RedisCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 操作失败统一出口：首次失败响亮告警，后续降级为 debug 防刷屏 */
    private void onFailure(String op, String key, Exception e) {
        if (degraded.compareAndSet(false, true)) {
            log.warn("Redis 不可用，能力降级（token→内存兜底、限流/防爆破/会话互斥锁失效、缓存回源 DB）"
                    + "op={} key={} err={}", op, key, e.getMessage());
        } else {
            log.debug("Redis 仍不可用 op={} key={} err={}", op, key, e.getMessage());
        }
    }

    /** 操作成功统一出口：降级态复位并提示恢复 */
    private void onSuccess() {
        if (degraded.compareAndSet(true, false)) {
            log.warn("Redis 已恢复，降级能力重新生效");
        }
    }

    // ===== 字符串 =====

    /** 写字符串值（Redis 不可用时静默丢弃） */
    public void setString(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
            onSuccess();
        } catch (Exception e) {
            onFailure("setString", key, e);
        }
    }

    public Optional<String> getString(String key) {
        try {
            Optional<String> v = Optional.ofNullable(redis.opsForValue().get(key));
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("getString", key, e);
            return Optional.empty();
        }
    }

    // ===== 对象（JSON） =====

    /** 写对象（JSON 序列化，Redis 不可用时静默丢弃） */
    public void set(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            onSuccess();
        } catch (Exception e) {
            onFailure("set", key, e);
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                onSuccess();
                return Optional.empty();
            }
            Optional<T> v = Optional.ofNullable(objectMapper.readValue(json, type));
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("get", key, e);
            return Optional.empty();
        }
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                onSuccess();
                return Optional.empty();
            }
            Optional<T> v = Optional.ofNullable(objectMapper.readValue(json, type));
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("get", key, e);
            return Optional.empty();
        }
    }

    /** 写 List<T>（JSON 序列化；与 {@link #getList} 配套，收敛调用方 TypeReference 匿名类） */
    public <T> void setList(String key, List<T> value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            onSuccess();
        } catch (Exception e) {
            onFailure("setList", key, e);
        }
    }

    /** 读 List<T>（元素类型为具体类，如 ChatSessionResponse） */
    public <T> Optional<List<T>> getList(String key, Class<T> elementType) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                onSuccess();
                return Optional.empty();
            }
            Optional<List<T>> v = Optional.ofNullable(objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)));
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("getList", key, e);
            return Optional.empty();
        }
    }

    // ===== 通用 =====

    public void delete(String key) {
        try {
            redis.delete(key);
            onSuccess();
        } catch (Exception e) {
            onFailure("delete", key, e);
        }
    }

    /** 按前缀批量删除（用于配置类缓存整体失效；本场景 key 量小，KEYS 可接受） */
    public void deleteByPattern(String pattern) {
        try {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            onSuccess();
        } catch (Exception e) {
            onFailure("deleteByPattern", pattern, e);
        }
    }

    /** 原子自增；首次自增时设置 TTL，返回当前计数（失败返回 null） */
    public Long increment(String key, Duration ttl) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, ttl);
            }
            onSuccess();
            return count;
        } catch (Exception e) {
            onFailure("increment", key, e);
            return null;
        }
    }

    /** 原子占位（防重复提交）：成功占位返回 true，已存在返回 false（失败返回 null 由调用方降级） */
    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
            onSuccess();
            return ok == null ? false : ok;
        } catch (Exception e) {
            onFailure("setIfAbsent", key, e);
            return null;
        }
    }

    // ===== Hash（抽取任务进度等） =====

    public void hset(String key, Map<String, String> fields, Duration ttl) {
        try {
            if (fields == null || fields.isEmpty()) {
                return;
            }
            redis.opsForHash().putAll(key, fields);
            if (ttl != null) {
                redis.expire(key, ttl);
            }
            onSuccess();
        } catch (Exception e) {
            onFailure("hset", key, e);
        }
    }

    public Map<Object, Object> hgetAll(String key) {
        try {
            Map<Object, Object> v = redis.opsForHash().entries(key);
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("hgetAll", key, e);
            return Collections.emptyMap();
        }
    }

    public boolean hasKey(String key) {
        try {
            boolean v = Boolean.TRUE.equals(redis.hasKey(key));
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("hasKey", key, e);
            return false;
        }
    }

    public List<String> lrange(String key, long start, long end) {
        try {
            List<String> v = redis.opsForList().range(key, start, end);
            onSuccess();
            return v;
        } catch (Exception e) {
            onFailure("lrange", key, e);
            return Collections.emptyList();
        }
    }

    public void rpush(String key, String value, Duration ttl) {
        try {
            redis.opsForList().rightPush(key, value);
            redis.expire(key, ttl);
            onSuccess();
        } catch (Exception e) {
            onFailure("rpush", key, e);
        }
    }
}