package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.entity.ExtractTask;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 抽取任务进度存储：Redis Hash（seuknowledge:task:{id}，TTL 24h）的读写与字段名定义。
 * 执行器（{@link ExtractTaskExecutor}）写入、任务查询（{@link ExtractTaskService}）读取，
 * 字段常量唯一归属本类，消除两侧散落定义。
 */
@Component
public class TaskProgressStore {

    /** Redis 进度 Hash 字段名（写入方与读取方共用，保持一致） */
    public static final String H_STATUS = "status";
    public static final String H_TOTAL = "totalDocs";
    public static final String H_PROCESSED = "processedDocs";
    public static final String H_PROGRESS = "progress";
    public static final String H_RESULT = "resultSummary";
    public static final String H_ERROR = "errorLog";
    public static final String H_FAILED = "failedDocIds";
    public static final String H_DURATION = "durationMs";
    public static final String H_TOKEN_IN = "tokenInput";
    public static final String H_TOKEN_OUT = "tokenOutput";
    public static final String H_TOKEN_TOTAL = "tokenTotal";
    public static final String H_FINISHED = "finishedAt";

    private final RedisCacheService redisCacheService;
    private final Duration taskTtl;

    public TaskProgressStore(RedisCacheService redisCacheService, SeuCacheProperties cacheProps) {
        this.redisCacheService = redisCacheService;
        this.taskTtl = Duration.ofSeconds(cacheProps.taskTtlSeconds());
    }

    /**
     * 将任务当前进度写入 Redis Hash（字段名见 H_* 常量），TTL 兜底清理。
     * <p>
     * <b>全部字段无条件写入</b>（实体值为 null 时写空串），不可用 "null 则跳过" 的写法：
     * {@code RedisCacheService.hset} 底层是 {@code HSET}（putAll，只覆盖不删除），
     * 跳过 null 字段会让上一轮执行遗留的值永久留在 Hash 里。典型触发是任务重试——
     * 新一轮开跑时 {@link ExtractTaskExecutor} 已把 errorLog/failedDocIds 等置 null，
     * 若跳过写入，用户读到的仍是上一轮的错误信息，误判本轮也失败。
     */
    public void write(ExtractTask task) {
        Map<String, String> fields = new HashMap<>();
        fields.put(H_STATUS, Texts.str(task.getStatus()));
        fields.put(H_TOTAL, String.valueOf(task.getTotalDocs()));
        fields.put(H_PROCESSED, String.valueOf(task.getProcessedDocs()));
        fields.put(H_PROGRESS, String.valueOf(task.getProgress()));
        fields.put(H_ERROR, Texts.str(task.getErrorLog()));
        fields.put(H_FAILED, Texts.str(task.getFailedDocIds()));
        fields.put(H_RESULT, Texts.str(task.getResultSummary()));
        fields.put(H_DURATION, numberOrEmpty(task.getDurationMs()));
        fields.put(H_TOKEN_IN, numberOrEmpty(task.getTokenInput()));
        fields.put(H_TOKEN_OUT, numberOrEmpty(task.getTokenOutput()));
        fields.put(H_TOKEN_TOTAL, numberOrEmpty(task.getTokenTotal()));
        fields.put(H_FINISHED, task.getFinishedAt() == null ? "" : task.getFinishedAt().toString());
        redisCacheService.hset(RedisKeys.task(task.getId()), fields, taskTtl);
    }

    private static String numberOrEmpty(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 读取任务进度 Hash（RUNNING 期间实时；无进度时为空 Map） */
    public Map<Object, Object> read(Long taskId) {
        return redisCacheService.hgetAll(RedisKeys.task(taskId));
    }
}
