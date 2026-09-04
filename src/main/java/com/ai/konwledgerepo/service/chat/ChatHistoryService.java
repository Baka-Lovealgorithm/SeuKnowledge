package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.MessageRole;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 会话记忆域：问答历史窗口的读取与缓存维护。
 * 缓存策略（fail-open，Redis 不可用自动回源 DB）：history:v2:{sessionId}（TTL 600s，消息保存后追加）。
 * <p>
 * v2：拦截被中断的 assistant 消息（interrupted=true），不进入 LLM 多轮上下文。
 */
@Service
public class ChatHistoryService {

    private final ChatMessageRepository messageRepository;
    private final RedisCacheService redisCacheService;
    private final int messageWindow;
    private final Duration historyTtl;

    public ChatHistoryService(ChatMessageRepository messageRepository,
                              RedisCacheService redisCacheService,
                              SeuQaProperties qaProps,
                              SeuCacheProperties cacheProps) {
        this.messageRepository = messageRepository;
        this.redisCacheService = redisCacheService;
        this.messageWindow = qaProps.messageWindow();
        this.historyTtl = Duration.ofSeconds(cacheProps.historyTtlSeconds());
    }

    /** 会话记忆读取：Redis 缓存优先（请求窗口不超过缓存容量 messageWindow 时），miss 回源 DB 回填。
     * 返回值已过滤被中断的 assistant 消息（保留其前面的 user 问题）。 */
    public List<HistoryEntry> cachedHistory(Long sessionId, int window) {
        int safeWindow = window <= 0 ? messageWindow : window;
        Optional<List<HistoryEntry>> cached = redisCacheService.get(RedisKeys.history(sessionId),
                new TypeReference<List<HistoryEntry>>() {
                });
        if (cached.isPresent() && safeWindow <= messageWindow) {
            List<HistoryEntry> entries = filterInterrupted(cached.get());
            if (entries.size() > safeWindow) {
                return new ArrayList<>(entries.subList(entries.size() - safeWindow, entries.size()));
            }
            return entries;
        }
        List<HistoryEntry> entries = loadHistoryFromDb(sessionId, safeWindow);
        redisCacheService.set(RedisKeys.history(sessionId), entries, historyTtl);
        return entries;
    }

    /** 从 DB 加载最近 window 条记忆（缓存容量按全局窗口上限截断，保证任意窗口请求可命中缓存）。
     * 返回值已过滤被中断的 assistant 消息。 */
    private List<HistoryEntry> loadHistoryFromDb(Long sessionId, int window) {
        List<ChatMessage> recent = new ArrayList<>(
                messageRepository.findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, window)));
        Collections.reverse(recent);
        List<HistoryEntry> entries = toEntries(recent);
        if (entries.size() > messageWindow) {
            entries = new ArrayList<>(entries.subList(entries.size() - messageWindow, entries.size()));
        }
        return entries;
    }

    /**
     * 摘要专用读取：从 DB 直取最近 rawCount 条**原始**消息（不封顶 messageWindow、不走缓存、不回填），
     * 过滤被中断的 assistant 消息。取数条数由调用方按摘要快照计算（增量+重叠），与对话缓存窗口解耦——
     * 摘要连续失败积压的增量不受 20 条窗口限制，恢复后可一次补压。
     */
    public List<HistoryEntry> loadRecentFromDb(Long sessionId, int rawCount) {
        int count = Math.max(1, rawCount);
        List<ChatMessage> recent = new ArrayList<>(
                messageRepository.findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, count)));
        Collections.reverse(recent);
        return toEntries(recent);
    }

    /** 消息实体 → 历史条目（携带 interrupted 标记），并过滤被中断的 assistant 消息 */
    private static List<HistoryEntry> toEntries(List<ChatMessage> messages) {
        List<HistoryEntry> entries = messages.stream()
                .map(m -> {
                    Boolean interrupted = m.getInterrupted();
                    return new HistoryEntry(m.getRole(), m.getContent(), interrupted != null && interrupted);
                })
                .toList();
        return filterInterrupted(entries);
    }

    /** 丢弃被中断的 assistant 消息；保留其前面的 user 问题（后续多轮仍在同一会话中操作） */
    static List<HistoryEntry> filterInterrupted(List<HistoryEntry> entries) {
        return entries.stream()
                .filter(e -> !(MessageRole.ASSISTANT.value().equals(e.role()) && Boolean.TRUE.equals(e.interrupted())))
                .toList();
    }

    /** 消息保存后追加到会话记忆缓存（截断到全局窗口）；缓存 miss 时不维护，由下次读取回源重建 */
    public void appendHistory(Long sessionId, String role, String content) {
        appendHistory(sessionId, role, content, false);
    }

    /** 消息保存后追加到会话记忆缓存（带 interrupted 标记） */
    public void appendHistory(Long sessionId, String role, String content, boolean interrupted) {
        Optional<List<HistoryEntry>> cached = redisCacheService.get(RedisKeys.history(sessionId),
                new TypeReference<List<HistoryEntry>>() {
                });
        if (cached.isEmpty()) {
            return;
        }
        List<HistoryEntry> updated = new ArrayList<>(cached.get());
        updated.add(new HistoryEntry(role, content, interrupted));
        if (updated.size() > messageWindow) {
            updated = new ArrayList<>(updated.subList(updated.size() - messageWindow, updated.size()));
        }
        redisCacheService.set(RedisKeys.history(sessionId), updated, historyTtl);
    }
}