package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 会话滚动摘要服务：旁路异步维护，每新增 6 条消息（=3 轮问答）压缩一次，失败不影响主链路。
 * <p>
 * 持久化：DB 为事实源——{@code chat_session.memory_summary}（摘要文本）+ {@code summary_msg_count}
 * （生成时的消息总数快照，兼作触发增量的单调基准）；Redis {@code summary:v2:{sessionId}} 为读缓存，
 * miss 回源 DB 并尽力回填。摘要随会话存亡，不再因缓存过期而丢失长期记忆。
 * <p>
 * 触发基准：会话消息总数（{@code persistAnswer}/{@code persistInterruptedAnswer} 返回值，单调递增），
 * 而非封顶的历史缓存长度——长会话不会因缓存封顶而停止更新；间隔与路由/闲聊节点的最近 3 轮窗口对齐，
 * 摘要覆盖点与近窗起点之间无空洞。
 * <p>
 * 取数（增量+重叠）：锚点是摘要快照——每次只喂「上次未压缩的增量 + 快照前 2 轮重叠原文 + 旧摘要」，
 * 不再重复喂已摘要内容；少量重叠原文供提示词"冲突以最近对话为准"规则自我纠错（修正上次压缩损耗）。
 * 从 DB 直取（不封顶对话缓存窗口），摘要连续失败积压的增量恢复后可一次补压，
 * 超过 {@link #MAX_DELTA_MESSAGES} 条时截断取最近部分并告警（极端场景显式记录，快照仍推进）。
 * <p>
 * 模型：优先 MEMORY 用途配置，未配置时回退 ROUTER 模型（便宜且已常见配置）。
 * 注入：问答链路读取摘要注入意图路由/改写/闲聊节点，与最近 3 轮原文共同构成记忆上下文。
 */
@Service
public class ChatSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryService.class);
    private static final Duration GEN_TTL = Duration.ofSeconds(120);
    /** 触发间隔：每新增 6 条消息（=3 轮问答）压缩一次，与近窗 3 轮对齐（触发前最大增量 5 < 近窗 6，覆盖无空洞） */
    private static final int TRIGGER_INTERVAL = 6;
    /** 增量外保留的重叠条数（快照前 2 轮原文）：为"冲突以最近对话为准"规则提供纠错原料，修正上次压缩损耗 */
    private static final int OVERLAP_MESSAGES = 4;
    /** 单次压缩的最大增量条数（原始消息口径）：摘要长时间失败恢复时防单次输入过大，超出部分截断并告警 */
    private static final int MAX_DELTA_MESSAGES = 60;

    private final RedisCacheService redisCacheService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageStore messageStore;
    private final ChatHistoryService historyService;
    private final PromptCatalog promptCatalog;
    private final ModelFactory modelFactory;
    private final Executor executor;
    private final Duration summaryTtl;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatSummaryService(RedisCacheService redisCacheService,
                              ChatSessionRepository sessionRepository,
                              ChatMessageStore messageStore,
                              ChatHistoryService historyService,
                              PromptCatalog promptCatalog,
                              ModelFactory modelFactory,
                              SeuCacheProperties cacheProps,
                              @Qualifier("applicationTaskExecutor") Executor executor) {
        this.redisCacheService = redisCacheService;
        this.sessionRepository = sessionRepository;
        this.messageStore = messageStore;
        this.historyService = historyService;
        this.promptCatalog = promptCatalog;
        this.modelFactory = modelFactory;
        this.executor = executor;
        this.summaryTtl = Duration.ofSeconds(cacheProps.historyTtlSeconds());
        this.mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    /**
     * 读取摘要：Redis 读缓存优先；miss 回源 DB（事实源）并尽力回填缓存。
     * Redis 过期/不可用只影响读取成本，不再丢数据。
     */
    public Optional<SummaryRecord> readSummary(Long sessionId) {
        Optional<SummaryRecord> cached = redisCacheService.get(RedisKeys.summary(sessionId), SummaryRecord.class);
        if (cached.isPresent()) {
            return cached;
        }
        return sessionRepository.findById(sessionId)
                .map(this::toRecord)
                .filter(record -> !record.text().isBlank())
                .map(record -> {
                    redisCacheService.set(RedisKeys.summary(sessionId), record, summaryTtl);
                    return record;
                });
    }

    /** DB 行 → 摘要记录（存量行两列可为 null，统一归零处理） */
    private SummaryRecord toRecord(ChatSession session) {
        return new SummaryRecord(
                session.getMemorySummary() == null ? "" : session.getMemorySummary(),
                session.getSummaryMsgCount() == null ? 0 : session.getSummaryMsgCount());
    }

    /**
     * 消息落库后触发：以会话消息总数（单调递增）对上次摘要快照的增量判定，达
     * {@link #TRIGGER_INTERVAL} 条时异步压缩。防重锁（SETNX）保证同一会话不并发生成；
     * 失败不推进快照，下条消息自愈重试。
     */
    public void maybeUpdate(Long sessionId, Long workspaceId, int messageCount) {
        Optional<SummaryRecord> prev = readSummary(sessionId);
        int lastCount = prev.map(SummaryRecord::lastMessageCount).orElse(0);
        if (messageCount - lastCount < TRIGGER_INTERVAL) {
            return;
        }
        Boolean ok = redisCacheService.setIfAbsent(RedisKeys.summaryGen(sessionId), "1", GEN_TTL);
        if (ok == null || !ok) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                // 增量+重叠取数：锚点是摘要快照——只喂上次未压缩的增量与少量纠错重叠原文，
                // 消除固定窗口重复喂已摘要内容的浪费；DB 直取不受对话缓存窗口封顶，
                // 失败积压的增量恢复后可一次补压（超上限截断告警，快照仍推进）
                int delta = messageCount - lastCount;
                String oldSummary = prev.map(SummaryRecord::text).orElse("");
                if (delta > MAX_DELTA_MESSAGES) {
                    log.warn("摘要增量超上限，本次截断取最近部分 sessionId={} delta={} max={}",
                            sessionId, delta, MAX_DELTA_MESSAGES);
                }
                int fetchRaw = Math.min(delta, MAX_DELTA_MESSAGES)
                        + (oldSummary.isBlank() ? 0 : OVERLAP_MESSAGES);
                List<HistoryEntry> valid = historyService.loadRecentFromDb(sessionId, fetchRaw);
                if (valid.isEmpty()) {
                    log.warn("摘要生成中止：无有效历史 sessionId={}", sessionId);
                    return;
                }
                String historyJson = mapper.writeValueAsString(valid);
                String prompt = promptCatalog.render("summary-memory", Map.of(
                        "oldSummary", oldSummary.isBlank() ? "（无）" : oldSummary, "historyJson", historyJson));
                ChatModel model = modelFactory.getMemoryChatModel(workspaceId);
                String newSummary = model.call(prompt);
                if (newSummary != null) {
                    newSummary = newSummary.trim();
                }
                if (newSummary == null || newSummary.isBlank()) {
                    log.warn("摘要生成为空 sessionId={}", sessionId);
                    return;
                }
                // 落库先行（事实源）；rows=0 说明会话已删除，放弃写缓存。DB 失败则快照不推进，下条消息重试
                int updated = messageStore.persistSummary(sessionId, newSummary, messageCount);
                if (updated > 0) {
                    redisCacheService.set(RedisKeys.summary(sessionId),
                            new SummaryRecord(newSummary, messageCount), summaryTtl);
                    log.debug("摘要更新成功 sessionId={} count={} chars={}", sessionId, messageCount, newSummary.length());
                } else {
                    log.warn("摘要落库跳过（会话已删除） sessionId={}", sessionId);
                }
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
