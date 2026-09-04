package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话滚动摘要服务测试：触发判定（单调消息总数基准）、防重锁、落库先行、Redis miss 回源 DB。
 */
class ChatSummaryServiceTest {

    private RedisCacheService redis;
    private ChatSessionRepository sessionRepository;
    private ChatMessageStore messageStore;
    private ChatHistoryService historyService;
    private ChatSummaryService service;

    @BeforeEach
    void setUp() {
        redis = mock(RedisCacheService.class);
        sessionRepository = mock(ChatSessionRepository.class);
        messageStore = mock(ChatMessageStore.class);
        historyService = mock(ChatHistoryService.class);
        PromptCatalog catalog = mock(PromptCatalog.class);
        when(catalog.render(eq("summary-memory"), any())).thenReturn("旧摘要：{{oldSummary}}\n\n最近对话：{{historyJson}}");
        ModelFactory modelFactory = mock(ModelFactory.class);
        ChatModel chat = mock(ChatModel.class);
        when(chat.call(anyString())).thenReturn("新摘要内容");
        when(modelFactory.getMemoryChatModel(anyLong())).thenReturn(chat);
        // 同步执行器，方便测试异步触发
        service = new ChatSummaryService(redis, sessionRepository, messageStore, historyService, catalog, modelFactory,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400),
                new SeuQaProperties(20, 2, 32, 30, true, false, false, 0.4, false, 60, 200),
                Runnable::run);
    }

    private void stubPrevRecord(ChatSummaryService.SummaryRecord record) {
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.of(record));
    }

    private static ChatSession sessionWithSummary(String summary, Integer count) {
        ChatSession s = new ChatSession();
        s.setId(1L);
        s.setMemorySummary(summary);
        s.setSummaryMsgCount(count);
        return s;
    }

    /** 触发判定回归：基准是会话消息总数（单调），冻结场景（lastCount 已到封顶值 20）仍能继续触发 */
    @Test
    void maybeUpdate_deltaReachesSix_triggers() {
        stubPrevRecord(new ChatSummaryService.SummaryRecord("旧摘要", 20));
        when(redis.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(historyService.cachedHistory(1L, 20)).thenReturn(List.of(new HistoryEntry("user", "问题1")));
        when(messageStore.persistSummary(1L, "新摘要内容", 26)).thenReturn(1);

        service.maybeUpdate(1L, 1L, 26);

        verify(redis).setIfAbsent(eq(RedisKeys.summaryGen(1L)), eq("1"), any());
        verify(messageStore).persistSummary(1L, "新摘要内容", 26);
        verify(redis).set(eq(RedisKeys.summary(1L)), eq(new ChatSummaryService.SummaryRecord("新摘要内容", 26)), any());
    }

    @Test
    void maybeUpdate_deltaInsufficient_skips() {
        stubPrevRecord(new ChatSummaryService.SummaryRecord("旧摘要", 20));
        service.maybeUpdate(1L, 1L, 25);
        verify(redis, never()).setIfAbsent(anyString(), anyString(), any());
    }

    /** 负增量（如外部重置计数）不触发 */
    @Test
    void maybeUpdate_negativeDelta_skips() {
        stubPrevRecord(new ChatSummaryService.SummaryRecord("旧摘要", 20));
        service.maybeUpdate(1L, 1L, 8);
        verify(redis, never()).setIfAbsent(anyString(), anyString(), any());
    }

    @Test
    void maybeUpdate_lockBusy_skips() {
        stubPrevRecord(ChatSummaryService.SummaryRecord.EMPTY);
        when(redis.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        service.maybeUpdate(1L, 1L, 6);
        verify(redis).setIfAbsent(eq(RedisKeys.summaryGen(1L)), eq("1"), any());
        verify(messageStore, never()).persistSummary(anyLong(), anyString(), anyInt());
        verify(redis, never()).set(eq(RedisKeys.summary(1L)), any(), any());
    }

    @Test
    void maybeUpdate_emptyHistory_aborts() {
        stubPrevRecord(ChatSummaryService.SummaryRecord.EMPTY);
        when(redis.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(historyService.cachedHistory(1L, 20)).thenReturn(List.of());

        service.maybeUpdate(1L, 1L, 6);

        // 无有效历史：不落库不写缓存（避免空输入产生垃圾摘要），快照不推进
        verify(messageStore, never()).persistSummary(anyLong(), anyString(), anyInt());
        verify(redis, never()).set(eq(RedisKeys.summary(1L)), any(), any());
    }

    /** 生成期间会话被删除（落库 0 行）：不写 Redis 缓存，避免幽灵缓存 */
    @Test
    void maybeUpdate_sessionDeletedDuringGen_skipsCache() {
        stubPrevRecord(ChatSummaryService.SummaryRecord.EMPTY);
        when(redis.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(historyService.cachedHistory(1L, 20)).thenReturn(List.of(new HistoryEntry("user", "问题1")));
        when(messageStore.persistSummary(1L, "新摘要内容", 6)).thenReturn(0);

        service.maybeUpdate(1L, 1L, 6);

        verify(redis, never()).set(eq(RedisKeys.summary(1L)), any(), any());
    }

    @Test
    void readSummary_returnsCached() {
        stubPrevRecord(new ChatSummaryService.SummaryRecord("用户讨论了报销流程。", 10));
        Optional<ChatSummaryService.SummaryRecord> result = service.readSummary(1L);
        assertTrue(result.isPresent());
        assertEquals("用户讨论了报销流程。", result.get().text());
        assertEquals(10, result.get().lastMessageCount());
    }

    /** Redis miss 回源 DB（事实源）并尽力回填缓存——摘要随会话存亡，不再因缓存过期丢失 */
    @Test
    void readSummary_missFallsBackToDb_andBackfillsCache() {
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.empty());
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(sessionWithSummary("DB摘要", 18)));

        Optional<ChatSummaryService.SummaryRecord> result = service.readSummary(1L);

        assertTrue(result.isPresent());
        assertEquals("DB摘要", result.get().text());
        assertEquals(18, result.get().lastMessageCount());
        verify(redis).set(eq(RedisKeys.summary(1L)), eq(new ChatSummaryService.SummaryRecord("DB摘要", 18)), any());
    }

    @Test
    void readSummary_dbBlankOrNullCount_returnsEmpty() {
        when(redis.get(eq(RedisKeys.summary(1L)), eq(ChatSummaryService.SummaryRecord.class)))
                .thenReturn(Optional.empty());
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(sessionWithSummary(null, null)));
        assertTrue(service.readSummary(1L).isEmpty());
        verify(redis, never()).set(eq(RedisKeys.summary(1L)), any(), any());
    }
}
