package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.dto.AskResponse;
import com.ai.konwledgerepo.dto.ChatMessageResponse;
import com.ai.konwledgerepo.dto.ChatSessionResponse;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaGraphRunner;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.service.agent.AgentService;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatService 单测：会话 CRUD/列表/消息、ask 三路径（知识库停用 / 正常链路 / graph 异常）、
 * askStreamAsync SSE 成功与异常、越权（用户不匹配 / 知识库跨工作空间）。
 * <p>
 * 约束：纯 JUnit5 + 普通 Mockito mock()（无 MockitoExtension / mockStatic / Spring 容器）；
 * OverAllState 为 final 类 → 构造真实实例（new OverAllState(Map.of(...))）；
 * SseEmitter 为普通类 → 匿名/静态子类捕获 send 事件与完成状态；测试结束清理 SseStreamContext ThreadLocal。
 */
class ChatServiceTest {

    private static final Long KB_ID = 5L;
    private static final Long USER_ID = 1L;
    private static final Long WS_ID = 1L;
    private static final Long SESSION_ID = 10L;

    /** 与 AgentService 默认模板一致：memoryWindow=20 == messageWindow，history 窗口不截断 */
    private static final AgentConfig AGENT =
            new AgentConfig("默认 Agent", "prompt", 5, 0.7, 2, 20);

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private KnowledgeBaseRepository kbRepository;
    private KnowledgeBaseService kbService;
    private QaGraphRunner qaGraphRunner;
    private AgentService agentService;
    private SessionTitleService titleService;
    private RedisCacheService redisCacheService;
    private ChatService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        kbRepository = mock(KnowledgeBaseRepository.class);
        kbService = mock(KnowledgeBaseService.class);
        qaGraphRunner = mock(QaGraphRunner.class);
        agentService = mock(AgentService.class);
        titleService = mock(SessionTitleService.class);
        redisCacheService = mock(RedisCacheService.class);
        // ChatService 为门面：内部组装五个子服务（与生产 Spring 注入同构），保持真实逻辑可测
        SeuQaProperties qaProps = new SeuQaProperties(20, 2, 32, 30, true);
        SeuCacheProperties cacheProps =
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400);
        // WorkspaceAccess 用真实实例（requireBelongs 不触库），保证跨空间越权用例仍走真实归属校验
        WorkspaceAccess workspaceAccess = new WorkspaceAccess(kbRepository, mock(DocumentRepository.class));
        ChatSessionService sessionService = new ChatSessionService(
                sessionRepository, messageRepository, kbRepository, kbService, workspaceAccess, redisCacheService, cacheProps);
        ChatHistoryService historyService = new ChatHistoryService(messageRepository, redisCacheService, qaProps, cacheProps);
        ChatMessageStore messageStore = new ChatMessageStore(
                messageRepository, sessionRepository, redisCacheService, historyService, sessionService);
        QaAnswerService answerService = new QaAnswerService(
                sessionService, historyService, messageStore, kbService, agentService, qaGraphRunner,
                titleService, qaProps);
        ChatStreamService streamService = new ChatStreamService(
                sessionService, historyService, messageStore, kbService, agentService, qaGraphRunner,
                titleService, qaProps);
        service = new ChatService(sessionService, answerService, streamService);
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        // askStreamAsync 内部 finally 已 clear，这里兜底防断言中断导致 ThreadLocal 残留
        SseStreamContext.clear();
    }

    private static KnowledgeBase kb(long id, String name, Long workspaceId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setName(name);
        kb.setWorkspaceId(workspaceId);
        kb.setStatus("AVAILABLE");
        kb.setArchived(false);
        return kb;
    }

    private static ChatSession session(long id, long userId, long kbId) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setUserId(userId);
        s.setKbId(kbId);
        s.setTitle("新会话");
        s.setTitleAuto(Boolean.TRUE);
        s.setMessageCount(0);
        s.setLastMessageAt(LocalDateTime.of(2025, 1, 1, 10, 0));
        s.setCreatedAt(LocalDateTime.of(2025, 1, 1, 9, 0));
        return s;
    }

    private static ChatMessage message(long id, long sessionId, String role, String content, String refs) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setRefs(refs);
        m.setCreatedAt(LocalDateTime.of(2025, 1, 1, 10, 0));
        return m;
    }

    /** ask/askStreamAsync 公共桩：会话归属校验、知识库、Agent 配置、历史缓存 miss（回源 DB 默认空）、标题生成 */
    private ChatSession stubAskBase(String question, KnowledgeBase kb) {
        ChatSession session = session(SESSION_ID, USER_ID, KB_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        // 落库悲观锁查询桩：返回同一实例，保持既有断言（messageCount/标题）针对同一对象
        when(sessionRepository.findByIdForUpdate(SESSION_ID)).thenReturn(Optional.of(session));
        when(kbService.getEntityCached(KB_ID)).thenReturn(kb);
        when(agentService.toAgentConfig(KB_ID, kb.getName(), 2, 20)).thenReturn(AGENT);
        when(redisCacheService.get(eq(RedisKeys.history(SESSION_ID)), any(TypeReference.class)))
                .thenReturn(Optional.empty());
        doNothing().when(titleService).submitAutoTitle(any(), anyString(), eq(WS_ID));
        return session;
    }

    // ===== createSession =====

    @Test
    void createSession_validatesKbAndSaves() {
        when(kbService.getInWorkspace(KB_ID, WS_ID)).thenReturn(kb(KB_ID, "测试库", WS_ID));

        ChatSession saved = service.createSession(KB_ID, USER_ID, WS_ID);

        verify(kbService).getInWorkspace(KB_ID, WS_ID);
        assertEquals("新会话", saved.getTitle());
        assertEquals(Boolean.TRUE, saved.getTitleAuto());
        assertEquals(0, saved.getMessageCount());
        assertEquals(KB_ID, saved.getKbId());
        assertEquals(USER_ID, saved.getUserId());
        verify(sessionRepository).save(any(ChatSession.class));
        verify(redisCacheService).delete(RedisKeys.sessionList(USER_ID, WS_ID));
    }

    // ===== listSessions =====

    @Test
    void listSessions_cacheHit_mapsResponsesBack() {
        LocalDateTime created = LocalDateTime.of(2025, 1, 1, 9, 0);
        LocalDateTime last = LocalDateTime.of(2025, 1, 1, 10, 30);
        List<ChatSessionResponse> cached = List.of(
                new ChatSessionResponse(10L, 5L, "会话一", 3, created, last),
                new ChatSessionResponse(11L, 6L, "会话二", 0, created, null));
        when(redisCacheService.getList(eq(RedisKeys.sessionList(USER_ID, WS_ID)), eq(ChatSessionResponse.class)))
                .thenReturn(Optional.of(cached));

        List<ChatSession> sessions = service.listSessions(USER_ID, WS_ID);

        assertEquals(2, sessions.size());
        assertEquals(10L, sessions.get(0).getId());
        assertEquals(5L, sessions.get(0).getKbId());
        assertEquals("会话一", sessions.get(0).getTitle());
        assertEquals(3, sessions.get(0).getMessageCount());
        assertEquals(created, sessions.get(0).getCreatedAt());
        assertEquals(last, sessions.get(0).getLastMessageAt());
        verify(kbRepository, never()).findByWorkspaceId(any());
        verify(sessionRepository, never()).findByUserIdAndKbIdInOrderByLastMessageAtDesc(any(), any());
        verify(redisCacheService, never()).setList(anyString(), any(), any(Duration.class));
    }

    @Test
    void listSessions_cacheMiss_loadsFromDbAndCaches() {
        when(redisCacheService.getList(eq(RedisKeys.sessionList(USER_ID, WS_ID)), eq(ChatSessionResponse.class)))
                .thenReturn(Optional.empty());
        when(kbRepository.findByWorkspaceId(WS_ID)).thenReturn(
                List.of(kb(5L, "知识库一", WS_ID), kb(6L, "知识库二", WS_ID)));
        ChatSession s1 = session(SESSION_ID, USER_ID, 5L);
        s1.setTitle("会话一");
        ChatSession s2 = session(11L, USER_ID, 6L);
        s2.setTitle("会话二");
        when(sessionRepository.findByUserIdAndKbIdInOrderByLastMessageAtDesc(USER_ID, List.of(5L, 6L)))
                .thenReturn(List.of(s1, s2));

        List<ChatSession> sessions = service.listSessions(USER_ID, WS_ID);

        assertEquals(2, sessions.size());
        assertSame(s1, sessions.get(0));
        ArgumentCaptor<List<ChatSessionResponse>> captor = ArgumentCaptor.forClass(List.class);
        verify(redisCacheService).setList(eq(RedisKeys.sessionList(USER_ID, WS_ID)), captor.capture(), any(Duration.class));
        assertEquals(2, captor.getValue().size());
        assertEquals(5L, captor.getValue().get(0).kbId());
        assertEquals("会话一", captor.getValue().get(0).title());
    }

    @Test
    void listSessions_noKbsInWorkspace_returnsEmpty() {
        when(redisCacheService.getList(eq(RedisKeys.sessionList(USER_ID, WS_ID)), eq(ChatSessionResponse.class)))
                .thenReturn(Optional.empty());
        when(kbRepository.findByWorkspaceId(WS_ID)).thenReturn(List.of());

        List<ChatSession> sessions = service.listSessions(USER_ID, WS_ID);

        assertTrue(sessions.isEmpty());
        verify(sessionRepository, never()).findByUserIdAndKbIdInOrderByLastMessageAtDesc(any(), any());
        verify(redisCacheService, never()).setList(eq(RedisKeys.sessionList(USER_ID, WS_ID)), any(), any());
    }

    // ===== rename =====

    @Test
    void rename_setsManualTitleAndEvictsCache() {
        ChatSession session = session(SESSION_ID, USER_ID, KB_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(kbService.getEntityCached(KB_ID)).thenReturn(kb(KB_ID, "测试库", WS_ID));

        ChatSession saved = service.rename(SESSION_ID, USER_ID, "新标题", WS_ID);

        assertEquals("新标题", saved.getTitle());
        assertEquals(Boolean.FALSE, saved.getTitleAuto());
        verify(sessionRepository).save(session);
        verify(redisCacheService).delete(RedisKeys.sessionList(USER_ID, WS_ID));
    }

    // ===== deleteSession =====

    @Test
    void deleteSession_cascadesMessagesAndEvictsAllCacheKeys() {
        ChatSession session = session(SESSION_ID, USER_ID, KB_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(kbService.getEntityCached(KB_ID)).thenReturn(kb(KB_ID, "测试库", WS_ID));

        service.deleteSession(SESSION_ID, USER_ID, WS_ID);

        verify(messageRepository).deleteBySessionId(SESSION_ID);
        verify(sessionRepository).delete(session);
        verify(redisCacheService).delete(RedisKeys.messages(SESSION_ID));
        verify(redisCacheService).delete(RedisKeys.history(SESSION_ID));
        verify(redisCacheService).delete(RedisKeys.sessionList(USER_ID, WS_ID));
    }

    // ===== messages =====

    @Test
    void messages_cacheHit_mapsResponsesBack() {
        ChatSession session = session(SESSION_ID, USER_ID, KB_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(kbService.getEntityCached(KB_ID)).thenReturn(kb(KB_ID, "测试库", WS_ID));
        LocalDateTime ts = LocalDateTime.of(2025, 1, 1, 10, 0);
        when(redisCacheService.getList(eq(RedisKeys.messages(SESSION_ID)), eq(ChatMessageResponse.class)))
                .thenReturn(Optional.of(List.of(
                        new ChatMessageResponse(1L, "USER", "你好", null, ts),
                        new ChatMessageResponse(2L, "ASSISTANT", "答案", "[{\"chunkId\":1}]", ts))));

        List<ChatMessage> msgs = service.messages(SESSION_ID, USER_ID, WS_ID);

        assertEquals(2, msgs.size());
        assertEquals("你好", msgs.get(0).getContent());
        assertEquals("USER", msgs.get(0).getRole());
        assertEquals("[{\"chunkId\":1}]", msgs.get(1).getRefs());
        assertEquals(ts, msgs.get(1).getCreatedAt());
        verify(messageRepository, never()).findBySessionIdOrderByIdAsc(any());
        verify(redisCacheService, never()).setList(anyString(), any(), any(Duration.class));
    }

    @Test
    void messages_cacheMiss_loadsFromDbAndCaches() {
        ChatSession session = session(SESSION_ID, USER_ID, KB_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(kbService.getEntityCached(KB_ID)).thenReturn(kb(KB_ID, "测试库", WS_ID));
        when(redisCacheService.getList(eq(RedisKeys.messages(SESSION_ID)), eq(ChatMessageResponse.class)))
                .thenReturn(Optional.empty());
        ChatMessage m1 = message(1L, SESSION_ID, "USER", "你好", null);
        when(messageRepository.findBySessionIdOrderByIdAsc(SESSION_ID)).thenReturn(List.of(m1));

        List<ChatMessage> msgs = service.messages(SESSION_ID, USER_ID, WS_ID);

        assertEquals(1, msgs.size());
        assertSame(m1, msgs.get(0));
        verify(redisCacheService).setList(eq(RedisKeys.messages(SESSION_ID)), any(), any(Duration.class));
    }

    // ===== ask =====

    @Test
    void ask_archivedKb_throws() {
        KnowledgeBase kb = kb(KB_ID, "测试库", WS_ID);
        kb.setArchived(true);
        stubAskBase("你好", kb);

        BizException ex = assertThrows(BizException.class, () -> service.ask(SESSION_ID, USER_ID, "你好", WS_ID));
        assertTrue(ex.getMessage().contains("停用"));
        verify(qaGraphRunner, never()).run(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void ask_disabledKb_throws() {
        KnowledgeBase kb = kb(KB_ID, "测试库", WS_ID);
        kb.setStatus("DISABLED");
        stubAskBase("你好", kb);

        BizException ex = assertThrows(BizException.class, () -> service.ask(SESSION_ID, USER_ID, "你好", WS_ID));
        assertTrue(ex.getMessage().contains("停用"));
        verify(qaGraphRunner, never()).run(any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void ask_normalPath_returnsAnswerAndPersists() {
        ChatSession session = stubAskBase("你好", kb(KB_ID, "测试库", WS_ID));
        OverAllState state = new OverAllState(Map.of(
                QaContextKey.ANSWER, "答案",
                QaContextKey.REFS, "[{\"chunkId\":1}]",
                QaContextKey.INTENT, "BUSINESS"));
        when(qaGraphRunner.run(any(QaContext.QaInput.class))).thenReturn(state);

        AskResponse resp = service.ask(SESSION_ID, USER_ID, "你好", WS_ID);

        assertEquals("答案", resp.answer());
        assertEquals("[{\"chunkId\":1}]", resp.refs());
        assertEquals("BUSINESS", resp.intent());

        ArgumentCaptor<ChatMessage> msgCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(msgCaptor.capture());
        List<ChatMessage> saved = msgCaptor.getAllValues();
        assertEquals("USER", saved.get(0).getRole());
        assertEquals("你好", saved.get(0).getContent());
        assertEquals("ASSISTANT", saved.get(1).getRole());
        assertEquals("答案", saved.get(1).getContent());
        assertEquals("[{\"chunkId\":1}]", saved.get(1).getRefs());

        assertEquals(2, session.getMessageCount());
        // 标题已异步化：落库不写标题，由 SessionTitleService 后台补写
        verify(titleService).submitAutoTitle(any(), eq("你好"), eq(WS_ID));
        verify(sessionRepository).save(session);
        verify(redisCacheService).delete(RedisKeys.sessionList(USER_ID, WS_ID));
    }

    @Test
    void ask_prefersChatOnlyAnswerWhenPresent() {
        stubAskBase("随便聊聊", kb(KB_ID, "测试库", WS_ID));
        OverAllState state = new OverAllState(Map.of(
                QaContextKey.CHAT_ONLY_ANSWER, "闲聊回答",
                QaContextKey.REFS, "[]",
                QaContextKey.INTENT, "CHITCHAT"));
        when(qaGraphRunner.run(any())).thenReturn(state);

        AskResponse resp = service.ask(SESSION_ID, USER_ID, "随便聊聊", WS_ID);

        assertEquals("闲聊回答", resp.answer());
        assertEquals("CHITCHAT", resp.intent());
    }

    @Test
    void ask_noAnswerKeys_usesFallback() {
        stubAskBase("问题", kb(KB_ID, "测试库", WS_ID));
        when(qaGraphRunner.run(any())).thenReturn(new OverAllState(Map.of()));

        AskResponse resp = service.ask(SESSION_ID, USER_ID, "问题", WS_ID);

        assertEquals("抱歉，暂时无法回答该问题。", resp.answer());
        assertEquals("[]", resp.refs());
        assertNull(resp.intent());
    }

    @Test
    void ask_graphThrows_wrapsInBizException() {
        stubAskBase("你好", kb(KB_ID, "测试库", WS_ID));
        when(qaGraphRunner.run(any())).thenThrow(new RuntimeException("模型不可用"));

        BizException ex = assertThrows(BizException.class, () -> service.ask(SESSION_ID, USER_ID, "你好", WS_ID));
        assertTrue(ex.getMessage().contains("问答处理失败"));
        verify(messageRepository, never()).save(any());
    }

    // ===== askStreamAsync =====

    @Test
    void askStreamAsync_success_sendsDeltaRefsDoneAndPersists() {
        ChatSession session = stubAskBase("你好", kb(KB_ID, "测试库", WS_ID));
        OverAllState state = new OverAllState(Map.of(
                QaContextKey.ANSWER, "答案",
                QaContextKey.REFS, "[{\"chunkId\":1}]"));
        CapturingEmitter emitter = new CapturingEmitter();
        AtomicBoolean contextSetDuringRun = new AtomicBoolean(false);
        when(qaGraphRunner.run(any())).thenAnswer(inv -> {
            contextSetDuringRun.set(SseStreamContext.get() == emitter);
            return state;
        });

        service.askStreamAsync(SESSION_ID, USER_ID, "你好", emitter, WS_ID);

        assertTrue(contextSetDuringRun.get(), "graph 执行期间 SseStreamContext 应指向当前 emitter");
        assertEquals(3, emitter.payloads.size(), "未标记 delta 时应补发 delta，事件序列应为 delta/refs/done");
        assertEquals("delta", payload(emitter, 0).get("type"));
        assertEquals("答案", payload(emitter, 0).get("content"));
        assertEquals("refs", payload(emitter, 1).get("type"));
        assertEquals("[{\"chunkId\":1}]", payload(emitter, 1).get("content"));
        assertEquals("done", payload(emitter, 2).get("type"));
        assertTrue(emitter.completed);

        verify(messageRepository, times(2)).save(any(ChatMessage.class));
        verify(redisCacheService, times(2)).delete(RedisKeys.messages(SESSION_ID));
        assertEquals(2, session.getMessageCount());
        // 标题已异步化：落库不写标题，由 SessionTitleService 后台补写
        verify(titleService).submitAutoTitle(any(), eq("你好"), eq(WS_ID));
        verify(sessionRepository).save(session);
        verify(redisCacheService).delete(RedisKeys.sessionList(USER_ID, WS_ID));

        assertNull(SseStreamContext.get(), "结束后 SseStreamContext 应已清理");
    }

    @Test
    void askStreamAsync_deltaAlreadySent_skipsDuplicateDelta() {
        stubAskBase("你好", kb(KB_ID, "测试库", WS_ID));
        OverAllState state = new OverAllState(Map.of(
                QaContextKey.ANSWER, "答案",
                QaContextKey.REFS, "[]"));
        CapturingEmitter emitter = new CapturingEmitter();
        when(qaGraphRunner.run(any())).thenAnswer(inv -> {
            SseStreamContext.markDeltaSent(); // 模拟 graph 节点已流式输出过 delta
            return state;
        });

        service.askStreamAsync(SESSION_ID, USER_ID, "你好", emitter, WS_ID);

        assertEquals(2, emitter.payloads.size(), "已标记 delta 时不应补发 delta");
        assertEquals("refs", payload(emitter, 0).get("type"));
        assertEquals("done", payload(emitter, 1).get("type"));
        assertTrue(emitter.completed);
        assertNull(SseStreamContext.get());
    }

    @Test
    void askStreamAsync_graphFails_sendsErrorAndCompletesWithError() {
        stubAskBase("你好", kb(KB_ID, "测试库", WS_ID));
        CapturingEmitter emitter = new CapturingEmitter();
        RuntimeException failure = new RuntimeException("链路异常");
        AtomicBoolean contextSetDuringRun = new AtomicBoolean(false);
        when(qaGraphRunner.run(any())).thenAnswer(inv -> {
            contextSetDuringRun.set(SseStreamContext.get() == emitter);
            throw failure;
        });

        service.askStreamAsync(SESSION_ID, USER_ID, "你好", emitter, WS_ID);

        assertTrue(contextSetDuringRun.get());
        assertEquals(1, emitter.payloads.size());
        assertEquals("error", payload(emitter, 0).get("type"));
        assertEquals("链路异常", payload(emitter, 0).get("content"));
        assertSame(failure, emitter.error);
        verify(messageRepository, never()).save(any());
        assertNull(SseStreamContext.get(), "异常路径结束后 SseStreamContext 也应清理");
    }

    // ===== 越权 =====

    @Test
    void messages_otherUsersSession_forbidden() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(SESSION_ID, 2L, KB_ID)));

        BizException ex = assertThrows(BizException.class,
                () -> service.messages(SESSION_ID, USER_ID, WS_ID));
        assertEquals(403, ex.getCode());
    }

    @Test
    void ask_kbInAnotherWorkspace_forbidden() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(SESSION_ID, USER_ID, KB_ID)));
        when(kbService.getEntityCached(KB_ID)).thenReturn(kb(KB_ID, "测试库", 99L));

        BizException ex = assertThrows(BizException.class,
                () -> service.ask(SESSION_ID, USER_ID, "你好", WS_ID));
        assertEquals(403, ex.getCode());
        verify(qaGraphRunner, never()).run(any());
        verify(messageRepository, never()).save(any());
    }

    // ===== 工具 =====

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(CapturingEmitter emitter, int index) {
        return (Map<String, Object>) emitter.payloads.get(index);
    }

    /**
     * SseEmitter 子类：ChatService.sendSseEvent 调用的是 send(SseEmitter.event()...) 重载
     * （编译期解析到更具体的 send(SseEventBuilder)），因此必须覆写该重载才能捕获事件负载；
     * 负载从 builder.build() 的 DataWithMediaType 中取出。
     */
    static class CapturingEmitter extends SseEmitter {

        final List<Object> payloads = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override
        public void send(SseEmitter.SseEventBuilder builder) throws IOException {
            // builder.build() 每个事件含 event 名行 + data 行等多个条目，只收集 Map 负载
            for (ResponseBodyEmitter.DataWithMediaType d : builder.build()) {
                if (d.getData() instanceof Map) {
                    payloads.add(d.getData());
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable t) {
            error = t;
        }
    }
}
