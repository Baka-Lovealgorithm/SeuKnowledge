package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.entity.ChatMessage;
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
 * 缓存策略（fail-open，Redis 不可用自动回源 DB）：history:{sessionId}（TTL 600s，消息保存后追加）。
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

    /** 会话记忆读取：Redis 缓存优先（请求窗口不超过缓存容量 messageWindow 时），miss 回源 DB 回填 */
    public List<HistoryEntry> cachedHistory(Long sessionId, int window) {
        int safeWindow = window <= 0 ? messageWindow : window;
        Optional<List<HistoryEntry>> cached = redisCacheService.get(RedisKeys.history(sessionId),
                new TypeReference<List<HistoryEntry>>() {
                });
        if (cached.isPresent() && safeWindow <= messageWindow) {
            List<HistoryEntry> entries = cached.get();
            if (entries.size() > safeWindow) {
                return new ArrayList<>(entries.subList(entries.size() - safeWindow, entries.size()));
            }
            return entries;
        }
        List<HistoryEntry> entries = loadHistoryFromDb(sessionId, safeWindow);
        redisCacheService.set(RedisKeys.history(sessionId), entries, historyTtl);
        return entries;
    }

    /** 从 DB 加载最近 window 条记忆（缓存容量按全局窗口上限截断，保证任意窗口请求可命中缓存） */
    private List<HistoryEntry> loadHistoryFromDb(Long sessionId, int window) {
        List<ChatMessage> recent = new ArrayList<>(
                messageRepository.findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, window)));
        Collections.reverse(recent);
        List<HistoryEntry> entries = recent.stream()
                .map(m -> new HistoryEntry(m.getRole(), m.getContent()))
                .toList();
        if (entries.size() > messageWindow) {
            entries = new ArrayList<>(entries.subList(entries.size() - messageWindow, entries.size()));
        }
        return entries;
    }

    /** 消息保存后追加到会话记忆缓存（截断到全局窗口）；缓存 miss 时不维护，由下次读取回源重建 */
    public void appendHistory(Long sessionId, String role, String content) {
        Optional<List<HistoryEntry>> cached = redisCacheService.get(RedisKeys.history(sessionId),
                new TypeReference<List<HistoryEntry>>() {
                });
        if (cached.isEmpty()) {
            return;
        }
        List<HistoryEntry> updated = new ArrayList<>(cached.get());
        updated.add(new HistoryEntry(role, content));
        if (updated.size() > messageWindow) {
            updated = new ArrayList<>(updated.subList(updated.size() - messageWindow, updated.size()));
        }
        redisCacheService.set(RedisKeys.history(sessionId), updated, historyTtl);
    }
}
