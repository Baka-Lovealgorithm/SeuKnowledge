package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.entity.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话标题异步生成服务测试：判定 → SETNX 防重 → 异步生成 → 条件补写（补写事务经 ChatMessageStore 跨 bean 生效）。
 */
class SessionTitleServiceTest {

    private ChatSessionService sessionService;
    private ChatMessageStore messageStore;
    private SessionTitleGenerator titleGenerator;
    private RedisCacheService redisCacheService;
    private SessionTitleService titleService;

    @BeforeEach
    void setUp() {
        sessionService = mock(ChatSessionService.class);
        messageStore = mock(ChatMessageStore.class);
        titleGenerator = mock(SessionTitleGenerator.class);
        redisCacheService = mock(RedisCacheService.class);
        titleService = new SessionTitleService(sessionService, messageStore, titleGenerator,
                redisCacheService, Runnable::run); // 同步执行，便于测试验证
    }

    private static ChatSession session(long id, boolean autoTitle) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setKbId(1L);
        s.setUserId(999L);
        s.setTitle(autoTitle ? "新会话" : "手动标题");
        s.setTitleAuto(autoTitle);
        return s;
    }

    @Test
    void needAutoTitle_false_skips() {
        ChatSession s = session(1, false);
        when(sessionService.needAutoTitle(s)).thenReturn(false);
        titleService.submitAutoTitle(s, "问题", 1L);
        verify(redisCacheService, never()).setIfAbsent(anyString(), anyString(), any());
    }

    @Test
    void setnxAlreadyExists_skips() {
        ChatSession s = session(1, true);
        when(sessionService.needAutoTitle(s)).thenReturn(true);
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        titleService.submitAutoTitle(s, "问题", 1L);
        verify(titleGenerator, never()).generate(anyString(), anyLong());
    }

    @Test
    void redisDown_skipsGracefully() {
        ChatSession s = session(1, true);
        when(sessionService.needAutoTitle(s)).thenReturn(true);
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(null);
        titleService.submitAutoTitle(s, "问题", 1L);
        verify(titleGenerator, never()).generate(anyString(), anyLong());
    }

    @Test
    void normalFlow_generatesAndUpdates() throws Exception {
        ChatSession s = session(1, true);
        when(sessionService.needAutoTitle(s)).thenReturn(true);
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(titleGenerator.generate("问题", 1L)).thenReturn("生成标题");
        when(messageStore.applyTitleIfAuto(1L, "生成标题")).thenReturn(1);

        titleService.submitAutoTitle(s, "问题", 1L);

        // 异步执行完成（Runnable::run 同步执行，等待 verify）
        verify(titleGenerator, timeout(1000)).generate("问题", 1L);
        verify(messageStore).applyTitleIfAuto(1L, "生成标题");
        verify(sessionService).evictSessionList(999L, 1L);
        verify(redisCacheService).delete(RedisKeys.titleGen(1L));
    }

    @Test
    void manualRename_skipsEvict() {
        ChatSession s = session(1, true);
        when(sessionService.needAutoTitle(s)).thenReturn(true);
        when(redisCacheService.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(titleGenerator.generate("问题", 1L)).thenReturn("标题");
        when(messageStore.applyTitleIfAuto(1L, "标题")).thenReturn(0);

        titleService.submitAutoTitle(s, "问题", 1L);

        verify(messageStore).applyTitleIfAuto(1L, "标题");
        // 更新 0 行（已手动重命名）→ 不失效缓存
        verify(sessionService, never()).evictSessionList(s.getUserId(), 1L);
        verify(redisCacheService).delete(RedisKeys.titleGen(1L));
    }
}
