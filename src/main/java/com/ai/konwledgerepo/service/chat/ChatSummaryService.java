package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.model.ModelFactory;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 会话滚动摘要服务：旁路异步维护，每新增 10 条消息触发一次压缩，失败不影响主链路。
 * <p>
 * 摘要存储：Redis {@code summary:v2:{sessionId}}，摘要文本 + 生成时的消息计数。
 * 模型：优先 MEMORY 用途配置，未配置时回退 ROUTER 模型（便宜且已常见配置）。
 * 注入：问答链路读取摘要注入 QueryRewrite 节点，与最近 3 轮原文共同构成改写上下文。
 */
@Service
public class ChatSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryService.class);
    private static final Duration GEN_TTL = Duration.ofSeconds(120);
    private static final int TRIGGER_INTERVAL = 10;

    private final RedisCacheService redisCacheService;
    private final PromptCatalog promptCatalog;
    private final ModelFactory modelFactory;
    private final Executor executor;
    private final Duration summaryTtl;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatSummaryService(RedisCacheService redisCacheService,
                              PromptCatalog promptCatalog,
                              ModelFactory modelFactory,
                              SeuCacheProperties cacheProps,
                              @Qualifier("applicationTaskExecutor") Executor executor) {
        this.redisCacheService = redisCacheService;
        this.promptCatalog = promptCatalog;
        this.modelFactory = modelFactory;
        this.executor = executor;
        this.summaryTtl = Duration.ofSeconds(cacheProps.historyTtlSeconds());
        this.mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    /**
     * 读取已存摘要（miss 返回空）。
     */
    public Optional<SummaryRecord> readSummary(Long sessionId) {
        return redisCacheService.get(RedisKeys.summary(sessionId), SummaryRecord.class);
    }

    /**
     * 消息落库后触发：检查历史缓存消息数增量，达阈值时异步生成摘要。
     * 防重锁（SETNX）保证同一会话不会并发生成；缓存 miss 直接跳过（fail-open）。
     */
    public void maybeUpdate(Long sessionId, Long workspaceId) {
        Optional<List<HistoryEntry>> rawHistory = redisCacheService.get(
                RedisKeys.history(sessionId), new TypeReference<List<HistoryEntry>>() {
                });
        if (rawHistory.isEmpty()) {
            return; // 缓存 miss，跳过
        }
        int size = rawHistory.get().size();
        SummaryRecord prev = readSummary(sessionId).orElse(SummaryRecord.EMPTY);
        int lastCount = prev.lastMessageCount();
        if (size - lastCount < TRIGGER_INTERVAL) {
            return;
        }
        Boolean ok = redisCacheService.setIfAbsent(RedisKeys.summaryGen(sessionId), "1", GEN_TTL);
        if (ok == null || !ok) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                // 重新读取最新的有效历史（过滤被中断的 assistant）
                List<HistoryEntry> allHistory = redisCacheService.get(
                        RedisKeys.history(sessionId), new TypeReference<List<HistoryEntry>>() {
                        }).orElse(List.of());
                List<HistoryEntry> valid = ChatHistoryService.filterInterrupted(allHistory);
                String historyJson = mapper.writeValueAsString(valid);
                String oldSummary = prev.text();
                String prompt = promptCatalog.get("summary-memory").formatted(
                        oldSummary.isBlank() ? "（无）" : oldSummary, historyJson);
                ChatModel model = modelFactory.getMemoryChatModel(workspaceId);
                String newSummary = model.call(prompt);
                if (newSummary != null) {
                    newSummary = newSummary.trim();
                }
                if (newSummary == null || newSummary.isBlank()) {
                    log.warn("摘要生成为空 sessionId={}", sessionId);
                    return;
                }
                SummaryRecord record = new SummaryRecord(newSummary, size);
                redisCacheService.set(RedisKeys.summary(sessionId), record, summaryTtl);
                log.debug("摘要更新成功 sessionId={} size={} chars={}", sessionId, size, newSummary.length());
            } catch (Exception e) {
                log.warn("摘要生成失败 sessionId={}: {}", sessionId, e.getMessage());
            } finally {
                redisCacheService.delete(RedisKeys.summaryGen(sessionId));
            }
        }, executor);
    }

    /**
     * 滚动摘要记录（可序列化，用于 Redis 缓存）。
     */
    public record SummaryRecord(String text, int lastMessageCount) {
        public static final SummaryRecord EMPTY = new SummaryRecord("", 0);
    }
}