package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.ChatMessageResponse;
import com.ai.konwledgerepo.dto.ChatSessionResponse;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.MessageRole;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话域（会话 + 消息读取）：建会话、列表、重命名、删除、消息列表与归属校验。
 * 写路径（消息落库）见 {@link ChatMessageStore}，问答编排见 {@link QaAnswerService} / {@link ChatStreamService}。
 */
@Service
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeBaseService kbService;
    private final WorkspaceAccess workspaceAccess;
    private final RedisCacheService redisCacheService;
    private final Duration sessionTtl;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              KnowledgeBaseRepository kbRepository,
                              KnowledgeBaseService kbService,
                              WorkspaceAccess workspaceAccess,
                              RedisCacheService redisCacheService,
                              SeuCacheProperties cacheProps) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.kbRepository = kbRepository;
        this.kbService = kbService;
        this.workspaceAccess = workspaceAccess;
        this.redisCacheService = redisCacheService;
        this.sessionTtl = Duration.ofSeconds(cacheProps.sessionTtlSeconds());
    }

    /** 创建会话：校验知识库属于当前工作空间 */
    public ChatSession createSession(Long kbId, Long userId, Long workspaceId) {
        kbService.getInWorkspace(kbId, workspaceId);
        ChatSession session = new ChatSession();
        session.setKbId(kbId);
        session.setUserId(userId);
        session.setTitle(Defaults.DEFAULT_SESSION_TITLE);
        session.setTitleAuto(Boolean.TRUE);
        session.setLastMessageAt(LocalDateTime.now());
        session.setMessageCount(0);
        ChatSession saved = sessionRepository.save(session);
        evictSessionList(userId, workspaceId);
        return saved;
    }

    /**
     * 会话列表（限当前工作空间）：按最后对话时间倒序（最近对话排最前）。
     * 会话按知识库归属过滤（含已归档知识库的历史会话，保证可见性）。
     * 顺带补偿旧数据：lastMessageAt 为空 → 取该会话最后一条消息时间（无消息取创建时间）；
     * 仍为默认标题且已有首条提问 → 自动截取首问命名。
     * 结果缓存于 Redis（sessionlist:{userId}:{workspaceId}，TTL 60s），会话增删改/新消息后失效。
     */
    public List<ChatSession> listSessions(Long userId, Long workspaceId) {
        Optional<List<ChatSessionResponse>> cached = redisCacheService.getList(
                RedisKeys.sessionList(userId, workspaceId), ChatSessionResponse.class);
        if (cached.isPresent()) {
            return cached.get().stream().map(this::toSession).toList();
        }
        List<Long> kbIds = kbRepository.findByWorkspaceId(workspaceId).stream()
                .map(kb -> kb.getId())
                .toList();
        if (kbIds.isEmpty()) {
            return List.of();
        }
        List<ChatSession> sessions = sessionRepository
                .findByUserIdAndKbIdInOrderByLastMessageAtDesc(userId, kbIds);
        boolean changed = backfillLastMessageAt(sessions);
        changed |= backfillDefaultTitles(sessions);
        if (changed) {
            sessionRepository.saveAll(sessions);
            // 补偿在查询后赋值 lastMessageAt（旧数据首查全为 NULL 时数据库排序无意义），此处按最后对话时间确定性重排
            sessions.sort(Comparator.comparing(ChatSession::getLastMessageAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        }
        redisCacheService.setList(RedisKeys.sessionList(userId, workspaceId),
                sessions.stream().map(ChatSessionResponse::from).toList(), sessionTtl);
        return sessions;
    }

    /** 旧会话补偿：lastMessageAt 为空 → 最后一条消息时间，无消息则取创建时间 */
    private boolean backfillLastMessageAt(List<ChatSession> sessions) {
        List<Long> missing = sessions.stream()
                .filter(s -> s.getLastMessageAt() == null)
                .map(ChatSession::getId)
                .toList();
        if (missing.isEmpty()) {
            return false;
        }
        Map<Long, LocalDateTime> maxBySession = new HashMap<>();
        for (Object[] row : messageRepository.findMaxCreatedAtBySessionIds(missing)) {
            maxBySession.put((Long) row[0], (LocalDateTime) row[1]);
        }
        boolean changed = false;
        for (ChatSession s : sessions) {
            if (s.getLastMessageAt() == null) {
                s.setLastMessageAt(maxBySession.getOrDefault(s.getId(), s.getCreatedAt()));
                changed = true;
            }
        }
        return changed;
    }

    /** 旧会话补偿：仍为默认标题且存在首条用户提问 → 用首问截取标题自动命名 */
    private boolean backfillDefaultTitles(List<ChatSession> sessions) {
        List<Long> defaultTitled = sessions.stream()
                .filter(s -> isDefaultTitle(s.getTitle()))
                .map(ChatSession::getId)
                .toList();
        if (defaultTitled.isEmpty()) {
            return false;
        }
        Map<Long, ChatMessage> firstBySession = new HashMap<>();
        for (ChatMessage m : messageRepository.findBySessionIdInAndRoleOrderByIdAsc(defaultTitled,
                MessageRole.USER.value())) {
            firstBySession.putIfAbsent(m.getSessionId(), m);
        }
        boolean changed = false;
        for (ChatSession s : sessions) {
            ChatMessage first = firstBySession.get(s.getId());
            if (first != null && isDefaultTitle(s.getTitle())) {
                s.setTitle(SessionTitleGenerator.truncate(first.getContent()));
                s.setTitleAuto(Boolean.TRUE);
                changed = true;
            }
        }
        return changed;
    }

    /** 重命名会话（手动命名后不再被自动标题覆盖；限当前工作空间） */
    public ChatSession rename(Long sessionId, Long userId, String title, Long workspaceId) {
        ChatSession session = getSession(sessionId, userId, workspaceId);
        session.setTitle(title);
        session.setTitleAuto(Boolean.FALSE);
        ChatSession saved = sessionRepository.save(session);
        evictSessionList(userId, workspaceId);
        return saved;
    }

    /** 删除会话（级联删除消息；限当前工作空间） */
    @Transactional
    public void deleteSession(Long sessionId, Long userId, Long workspaceId) {
        ChatSession session = getSession(sessionId, userId, workspaceId);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
        redisCacheService.delete(RedisKeys.messages(sessionId));
        redisCacheService.delete(RedisKeys.history(sessionId));
        evictSessionList(userId, workspaceId);
    }

    public List<ChatMessage> messages(Long sessionId, Long userId, Long workspaceId) {
        getSession(sessionId, userId, workspaceId);
        Optional<List<ChatMessageResponse>> cached = redisCacheService.getList(
                RedisKeys.messages(sessionId), ChatMessageResponse.class);
        if (cached.isPresent()) {
            return cached.get().stream().map(this::toMessage).toList();
        }
        List<ChatMessage> msgs = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        redisCacheService.setList(RedisKeys.messages(sessionId),
                msgs.stream().map(ChatMessageResponse::from).toList(), sessionTtl);
        return msgs;
    }

    /** 会话读取：校验归属用户 + 知识库属于当前工作空间（防跨空间按会话 id 越权） */
    public ChatSession getSession(Long sessionId, Long userId, Long workspaceId) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权访问该会话");
        }
        KnowledgeBase kb = kbService.getEntityCached(session.getKbId());
        workspaceAccess.requireBelongs(kb.getWorkspaceId(), workspaceId, "无权访问该会话");
        return session;
    }

    /** 会话列表缓存失效（按用户+工作空间；消息落库后调用） */
    public void evictSessionList(Long userId, Long workspaceId) {
        if (userId != null && workspaceId != null) {
            redisCacheService.delete(RedisKeys.sessionList(userId, workspaceId));
        }
    }

    /** 是否需要自动生成标题：未被手动重命名过 且 标题仍为默认值 */
    public boolean needAutoTitle(ChatSession session) {
        return !Boolean.FALSE.equals(session.getTitleAuto()) && isDefaultTitle(session.getTitle());
    }

    private static boolean isDefaultTitle(String title) {
        return title == null || title.isBlank() || Defaults.DEFAULT_SESSION_TITLE.equals(title.trim());
    }

    private ChatSession toSession(ChatSessionResponse r) {
        ChatSession s = new ChatSession();
        s.setId(r.id());
        s.setKbId(r.kbId());
        s.setTitle(r.title());
        s.setMessageCount(r.messageCount());
        s.setCreatedAt(r.createdAt());
        s.setLastMessageAt(r.lastMessageAt());
        return s;
    }

    private ChatMessage toMessage(ChatMessageResponse r) {
        ChatMessage m = new ChatMessage();
        m.setId(r.id());
        m.setRole(r.role());
        m.setContent(r.content());
        m.setRefs(r.refs());
        m.setCreatedAt(r.createdAt());
        return m;
    }
}
