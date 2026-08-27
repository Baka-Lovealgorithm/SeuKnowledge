package com.ai.konwledgerepo.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动基础设施自检（开发期防"Redis/MySQL/ES 未就绪却无感知"：
 * 曾出现 Redis requirepass 未注入导致认证失败、fail-open 全走降级而无人察觉）。
 * <p>
 * - 时机：{@link ApplicationReadyEvent}（晚于各 Runner / 数据初始化，此时探测最贴近"可服务"状态）；
 * - 行为：依次探测 Redis(PING) / MySQL(connection.isValid) / ES(ping)，输出 [OK]/[FAIL] 健康表；
 * - 失败项附带"受影响能力"清单并打 ERROR；
 * - 开关：seuknowledge.infra-check.enabled=false 整体跳过；
 * - 阻断：seuknowledge.infra-check.fail-fast=true 时任一失败抛异常阻断启动（生产建议）；
 *   默认 false 只告警不阻断（开发可无基础设施调试）。
 */
@Component
public class InfraStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(InfraStartupCheck.class);

    private final StringRedisTemplate redis;
    private final DataSource dataSource;
    private final ElasticsearchClient esClient;

    @Value("${seuknowledge.infra-check.enabled:true}")
    private boolean enabled;

    @Value("${seuknowledge.infra-check.fail-fast:false}")
    private boolean failFast;

    // 展示用端点（与应用配置同源）
    @Value("${spring.data.redis.host:127.0.0.1}:${spring.data.redis.port:6379}")
    private String redisTarget;

    @Value("${spring.datasource.url:jdbc:mysql://127.0.0.1:3306/seuknowledge}")
    private String mysqlTarget;

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esTarget;

    public InfraStartupCheck(StringRedisTemplate redis, DataSource dataSource, ElasticsearchClient esClient) {
        this.redis = redis;
        this.dataSource = dataSource;
        this.esClient = esClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (!enabled) {
            log.info("基础设施自检已关闭（seuknowledge.infra-check.enabled=false）");
            return;
        }

        log.info("========== 基础设施启动自检 ==========");
        List<Result> results = new ArrayList<>();
        results.add(checkRedis());
        results.add(checkMysql());
        results.add(checkEs());

        boolean anyFail = false;
        for (Result r : results) {
            if (r.ok()) {
                log.info("[OK]   {} target={} {}ms", r.name(), r.target(), r.costMs());
            } else {
                anyFail = true;
                log.error("[FAIL] {} target={} {}ms err={}", r.name(), r.target(), r.costMs(), r.err());
                log.error("      - 影响: {}", influence(r.name()));
            }
        }

        if (anyFail) {
            log.error("!! 基础设施存在不可用项，相关能力已降级或不可用（见上表）；修复后重启即可消除 !!");
            if (failFast) {
                throw new IllegalStateException("基础设施自检失败（seuknowledge.infra-check.fail-fast=true）: "
                        + results.stream().filter(r -> !r.ok()).map(Result::name).toList());
            }
        } else {
            log.info("基础设施自检全部通过");
        }
        log.info("====================================");
    }

    // ===== 探测项 =====

    private Result checkRedis() {
        long t0 = System.nanoTime();
        try {
            String pong = redis.execute((RedisCallback<String>) c -> c.ping());
            boolean ok = "PONG".equals(pong);
            return new Result(ok, "Redis", redisTarget, costMs(t0), ok ? null : "ping 返回异常: " + pong);
        } catch (Exception e) {
            return new Result(false, "Redis", redisTarget, costMs(t0), concise(e));
        }
    }

    private Result checkMysql() {
        long t0 = System.nanoTime();
        try (Connection c = dataSource.getConnection()) {
            boolean valid = c.isValid(2);
            return new Result(valid, "MySQL", mysqlTarget, costMs(t0), valid ? null : "connection.isValid=false");
        } catch (Exception e) {
            return new Result(false, "MySQL", mysqlTarget, costMs(t0), concise(e));
        }
    }

    private Result checkEs() {
        long t0 = System.nanoTime();
        try {
            esClient.ping();
            return new Result(true, "ES", esTarget, costMs(t0), null);
        } catch (Exception e) {
            return new Result(false, "ES", esTarget, costMs(t0), concise(e));
        }
    }

    // ===== 工具 =====

    private static long costMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** 失败信息只取首行，避免多行堆栈刷屏 */
    private static String concise(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        int nl = msg.indexOf('\n');
        return (nl > 0 ? msg.substring(0, nl) : msg).trim();
    }

    /** 基础设施不可用时受影响的能力面（与各 service fail-open 注释对齐） */
    private static String influence(String name) {
        return switch (name) {
            case "Redis" -> "登录 token 降级内存存储、/ask 限流失效、登录防爆破失效、会话互斥锁失效、各类缓存回源 DB";
            case "MySQL" -> "登录/会话/知识库/抽取任务等全部业务数据读写不可用";
            case "ES" -> "问答链路的向量检索与文档召回不可用（上传向量化、检索均失败）";
            default -> "-";
        };
    }

    private record Result(boolean ok, String name, String target, long costMs, String err) {
    }
}