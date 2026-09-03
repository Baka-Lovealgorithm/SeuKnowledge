package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.model.ModelFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话滚动摘要服务测试：触发条件、防重锁、摘要读写。
 */
class ChatSummaryServiceTest {

    private RedisCacheService redis;
    private ChatSummaryService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(RedisCacheService.class);
        PromptCatalog catalog = mock(PromptCatalog.class);
        when(catalog.render(eq("summary-memory"), any())).thenReturn("旧摘要：{{oldSummary}}\n\n最近对话：{{historyJson}}");
        ModelFactory modelFactory = mock(ModelFactory.class);
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(anyString())).thenReturn("新摘要内容");
        when(modelFactory.getMemoryChatModel(anyLong())).thenReturn(chat);
        // 同步执行器，方便测试异步触发
        service = new ChatSummaryService(redis, catalog, modelFactory,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400),
                Runnable::run);
    }

    @Test
    void maybeUpdate_cacheMiss_skips() {
        when(redis.get(eq(RedisKeys.history(1L)), any(TypeReference.class)))
                .thenReturn(Optional.empty());
        service.maybeUpdate(1L, 1L);
        verify(redis, never()).setIfAbsent(anyString(), anyString(), any());
    }

    @Test
    void maybeUpdate_insufficientIncrement_skips() {
        List<HistoryEntry> history = List.of(
                new HistoryEntry("user", "问题1"),
                new HistoryEntry("assistant", "答案1"));
        when(redis.get(eq(RedisKeys.history(1L)), any(TypeReference.class)))
                .thenReturn(Optional.of(history));
        // 已有摘要 lastCount=0，size=2，增量 2 < 10
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.of(new ChatSummaryService.SummaryRecord("旧摘要", 0)));
        service.maybeUpdate(1L, 1L);
        verify(redis, never()).setIfAbsent(anyString(), anyString(), any());
    }

    @Test
    void maybeUpdate_sufficientIncrement_triggers() {
        List<HistoryEntry> history = List.of(
                new HistoryEntry("user", "1"), new HistoryEntry("assistant", "1"),
                new HistoryEntry("user", "2"), new HistoryEntry("assistant", "2"),
                new HistoryEntry("user", "3"), new HistoryEntry("assistant", "3"),
                new HistoryEntry("user", "4"), new HistoryEntry("assistant", "4"),
                new HistoryEntry("user", "5"), new HistoryEntry("assistant", "5"),
                new HistoryEntry("user", "6"), new HistoryEntry("assistant", "6"));
        when(redis.get(eq(RedisKeys.history(1L)), any(TypeReference.class)))
                .thenReturn(Optional.of(history));
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.of(ChatSummaryService.SummaryRecord.EMPTY));
        when(redis.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        service.maybeUpdate(1L, 1L);
        // 增量 12 >= 10，应触发 SETNX
        verify(redis).setIfAbsent(eq(RedisKeys.summaryGen(1L)), eq("1"), any());
    }

    @Test
    void maybeUpdate_lockBusy_skips() {
        List<HistoryEntry> history = List.of(
                new HistoryEntry("user", "1"), new HistoryEntry("assistant", "1"),
                new HistoryEntry("user", "2"), new HistoryEntry("assistant", "2"),
                new HistoryEntry("user", "3"), new HistoryEntry("assistant", "3"),
                new HistoryEntry("user", "4"), new HistoryEntry("assistant", "4"),
                new HistoryEntry("user", "5"), new HistoryEntry("assistant", "5"),
                new HistoryEntry("user", "6"), new HistoryEntry("assistant", "6"));
        when(redis.get(eq(RedisKeys.history(1L)), any(TypeReference.class)))
                .thenReturn(Optional.of(history));
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.of(ChatSummaryService.SummaryRecord.EMPTY));
        when(redis.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        service.maybeUpdate(1L, 1L);
        // SETNX 返回 false，不执行摘要
        verify(redis).setIfAbsent(eq(RedisKeys.summaryGen(1L)), eq("1"), any());
        verify(redis, never()).set(eq(RedisKeys.summary(1L)), any(), any());
    }

    @Test
    void readSummary_returnsCached() {
        ChatSummaryService.SummaryRecord record = new ChatSummaryService.SummaryRecord("用户讨论了报销流程。", 10);
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.of(record));
        Optional<ChatSummaryService.SummaryRecord> result = service.readSummary(1L);
        assertTrue(result.isPresent());
        assertEquals("用户讨论了报销流程。", result.get().text());
        assertEquals(10, result.get().lastMessageCount());
    }

    @Test
    void readSummary_missReturnsEmpty() {
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.empty());
        assertTrue(service.readSummary(1L).isEmpty());
    }
}