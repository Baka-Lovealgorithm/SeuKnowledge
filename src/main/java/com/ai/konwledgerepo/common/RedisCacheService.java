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

/**
 * Redis 缓存统一封装（fail-open）：
 * - 键值均以 JSON 字符串存储（复用 Spring 管理的 ObjectMapper，支持 LocalDateTime / record）；
 * - 所有操作 try/catch，Redis 不可用时读返回 empty、写静默丢弃，调用方回源 DB，应用不挂；
 * - 所有 set 必须携带 TTL，防止陈旧数据与内存膨胀。
 */
@Component
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ===== 字符串 =====

    /** 写字符串值（Redis 不可用时静默丢弃） */
    public void setString(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis setString 失败，降级跳过 key={} err={}", key, e.getMessage());
        }
    }

    public Optional<String> getString(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (Exception e) {
            log.debug("Redis getString 失败，回源 DB key={} err={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ===== 对象（JSON） =====

    /** 写对象（JSON 序列化，Redis 不可用时静默丢弃） */
    public void set(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Redis set 失败，降级跳过 key={} err={}", key, e.getMessage());
        }
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.debug("Redis get 失败，回源 DB key={} err={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public <T> Optional<T> get(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.debug("Redis get 失败，回源 DB key={} err={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写 List<T>（JSON 序列化；与 {@link #getList} 配套，收敛调用方 TypeReference 匿名类） */
    public <T> void setList(String key, List<T> value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Redis setList 失败，降级跳过 key={} err={}", key, e.getMessage());
        }
    }

    /** 读 List<T>（元素类型为具体类，如 ChatSessionResponse） */
    public <T> Optional<List<T>> getList(String key, Class<T> elementType) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType)));
        } catch (Exception e) {
            log.debug("Redis getList 失败，回源 DB key={} err={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // ===== 通用 =====

    public void delete(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis delete 失败，降级跳过 key={} err={}", key, e.getMessage());
        }
    }

    /** 按前缀批量删除（用于配置类缓存整体失效；本场景 key 量小，KEYS 可接受） */
    public void deleteByPattern(String pattern) {
        try {
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis deleteByPattern 失败 pattern={} err={}", pattern, e.getMessage());
        }
    }

    /** 原子自增；首次自增时设置 TTL，返回当前计数（失败返回 null） */
    public Long increment(String key, Duration ttl) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, ttl);
            }
            return count;
        } catch (Exception e) {
            log.debug("Redis increment 失败 key={} err={}", key, e.getMessage());
            return null;
        }
    }

    /** 原子占位（防重复提交）：成功占位返回 true，已存在返回 false（失败返回 null 由调用方降级） */
    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
            return ok == null ? false : ok;
        } catch (Exception e) {
            log.debug("Redis setIfAbsent 失败 key={} err={}", key, e.getMessage());
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
        } catch (Exception e) {
            log.warn("Redis hset 失败，降级跳过 key={} err={}", key, e.getMessage());
        }
    }

    public Map<Object, Object> hgetAll(String key) {
        try {
            return redis.opsForHash().entries(key);
        } catch (Exception e) {
            log.debug("Redis hgetAll 失败 key={} err={}", key, e.getMessage());
            return Collections.emptyMap();
        }
    }

    public boolean hasKey(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> lrange(String key, long start, long end) {
        try {
            return redis.opsForList().range(key, start, end);
        } catch (Exception e) {
            log.debug("Redis lrange 失败 key={} err={}", key, e.getMessage());
            return Collections.emptyList();
        }
    }

    public void rpush(String key, String value, Duration ttl) {
        try {
            redis.opsForList().rightPush(key, value);
            redis.expire(key, ttl);
        } catch (Exception e) {
            log.warn("Redis rpush 失败，降级跳过 key={} err={}", key, e.getMessage());
        }
    }
}
