package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.dto.FeedbackRequest;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.MessageRole;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 答案评价落库域。三类必须钉住的行为：
 * <ul>
 *   <li><b>越权</b>：只有会话属主能评价（复用 {@link ChatSessionService#getSession}，
 *       它同时校验用户属主与工作空间归属）——不校验就会出现 A 给 B 的答案打踩；</li>
 *   <li><b>缓存失效</b>：消息列表缓存存的是 ChatMessageResponse 快照，不删就会出现
 *       「点完踩刷新一下又没了」；</li>
 *   <li><b>只写反馈四列</b>：走定向 update，不碰 content/messageCount，也不与会话行锁竞争。</li>
 * </ul>
 */
class MessageFeedbackServiceTest {

    private static final Long MESSAGE_ID = 100L;
    private static final Long SESSION_ID = 7L;
    private static final Long USER_ID = 1L;
    private static final Long WS_ID = 42L;

    private ChatMessageRepository messageRepository;
    private ChatSessionService sessionService;
    private RedisCacheService redisCacheService;
    private MessageFeedbackService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ChatMessageRepository.class);
        sessionService = mock(ChatSessionService.class);
        redisCacheService = mock(RedisCacheService.class);
        service = new MessageFeedbackService(messageRepository, sessionService, redisCacheService);
    }

    private ChatMessage answer(String role) {
        ChatMessage m = new ChatMessage();
        m.setId(MESSAGE_ID);
        m.setSessionId(SESSION_ID);
        m.setRole(role);
        m.setContent("答案");
        return m;
    }

    private void givenAnswer(ChatMessage m) {
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(m));
        ChatSession session = new ChatSession();
        session.setId(SESSION_ID);
        when(sessionService.getSession(SESSION_ID, USER_ID, WS_ID)).thenReturn(session);
    }

    private void givenAllowed() {
        givenAnswer(answer(MessageRole.ASSISTANT.value()));
        // 默认让定向 update 命中一行；需要验证 0 行分支的用例自行覆盖此桩
        when(messageRepository.updateFeedback(anyLong(), anyString(), any(LocalDateTime.class), any(), any()))
                .thenReturn(1);
    }

    // ===== 越权与目标行 =====

    /** 非会话属主：getSession 抛 403，评价不能落库 */
    @Test
    void rate_byNonOwner_forbiddenAndNothingWritten() {
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(answer(MessageRole.ASSISTANT.value())));
        when(sessionService.getSession(SESSION_ID, USER_ID, WS_ID))
                .thenThrow(new BizException(ErrorCodes.FORBIDDEN, "无权访问该会话"));

        BizException e = assertThrows(BizException.class,
                () -> service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", null, null), USER_ID, WS_ID));

        assertEquals(403, e.getCode());
        verify(messageRepository, never()).updateFeedback(anyLong(), anyString(), any(), any(), any());
        verify(redisCacheService, never()).delete(anyString());
    }

    /** 用户消息不可评价：压根不该出现在点踩入口，接口层也要挡住 */
    @Test
    void rate_onUserMessage_rejected() {
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(answer(MessageRole.USER.value())));

        BizException e = assertThrows(BizException.class,
                () -> service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", "OUTDATED", null), USER_ID, WS_ID));

        assertEquals("只能评价智能体的回答", e.getMessage());
        verify(sessionService, never()).getSession(anyLong(), anyLong(), anyLong());
        verify(messageRepository, never()).updateFeedback(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void rate_messageNotFound_rejected() {
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.empty());

        assertThrows(BizException.class,
                () -> service.rate(MESSAGE_ID, new FeedbackRequest("UP", null, null), USER_ID, WS_ID));
        verify(redisCacheService, never()).delete(anyString());
    }

    // ===== 写入语义 =====

    @Test
    void rate_down_writesReasonAndEvictsMessageCache() {
        givenAllowed();
        when(messageRepository.updateFeedback(eq(MESSAGE_ID), eq("DOWN"), any(LocalDateTime.class),
                eq("OUTDATED"), eq("版本不对"))).thenReturn(1);

        service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", "OUTDATED", "版本不对"), USER_ID, WS_ID);

        // 消息列表缓存存的是评价前快照：不失效就会出现「点完踩刷新一下又没了」
        verify(redisCacheService).delete(RedisKeys.messages(SESSION_ID));
    }

    /** 点赞不接受原因/说明：脏数据不让进库（前端也不会这么发，防直连接口） */
    @Test
    void rate_up_ignoresReasonAndNote() {
        givenAllowed();

        service.rate(MESSAGE_ID, new FeedbackRequest("UP", "OUTDATED", "备注"), USER_ID, WS_ID);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).updateFeedback(eq(MESSAGE_ID), eq("UP"), any(LocalDateTime.class),
                reason.capture(), note.capture());
        assertNull(reason.getValue(), "点赞不得携带原因");
        assertNull(note.getValue(), "点赞不得携带说明");
    }

    /** 撤销：走 clearFeedback（四列字面量置 null），不走参数绑定 */
    @Test
    void rate_none_clearsFeedback() {
        givenAllowed();
        when(messageRepository.clearFeedback(MESSAGE_ID)).thenReturn(1);

        service.rate(MESSAGE_ID, new FeedbackRequest("NONE", null, null), USER_ID, WS_ID);

        verify(messageRepository).clearFeedback(MESSAGE_ID);
        verify(messageRepository, never()).updateFeedback(anyLong(), anyString(), any(), any(), any());
        verify(redisCacheService).delete(RedisKeys.messages(SESSION_ID));
    }

    /** 原因留空白 → 归一为 null（用户可跳过原因，库里不留空串） */
    @Test
    void rate_down_blankReason_normalizedToNull() {
        givenAllowed();

        service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", "   ", null), USER_ID, WS_ID);

        verify(messageRepository).updateFeedback(eq(MESSAGE_ID), eq("DOWN"), any(LocalDateTime.class),
                isNull(), isNull());
    }

    /** 绕过 DTO 校验直连服务层时，非法原因码仍要被拒 */
    @Test
    void rate_down_unknownReason_rejected() {
        givenAllowed();

        assertThrows(BizException.class,
                () -> service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", "NOT_A_REASON", null), USER_ID, WS_ID));
        verify(messageRepository, never()).updateFeedback(anyLong(), anyString(), any(), any(), any());
    }

    /** 超长备注截到 200（列宽一致），不让 insert 因数据过长失败 */
    @Test
    void rate_down_longNote_truncatedToColumnWidth() {
        givenAllowed();

        service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", "OTHER", "x".repeat(500)), USER_ID, WS_ID);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).updateFeedback(eq(MESSAGE_ID), eq("DOWN"), any(LocalDateTime.class),
                eq("OTHER"), note.capture());
        assertEquals(200, note.getValue().length());
    }

    /** 并发下会话被删（update 影响 0 行）：报错且不失效缓存（没有可读的旧快照了） */
    @Test
    void rate_whenRowGone_throwsAndKeepsCache() {
        givenAllowed();
        when(messageRepository.updateFeedback(anyLong(), anyString(), any(), any(), any())).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.rate(MESSAGE_ID, new FeedbackRequest("DOWN", null, null), USER_ID, WS_ID));
        verify(redisCacheService, never()).delete(anyString());
    }

    /** 会话不存在（已被删）：越权校验阶段就抛，不摸 update */
    @Test
    void rate_sessionDeleted_rejected() {
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(answer(MessageRole.ASSISTANT.value())));
        when(sessionService.getSession(SESSION_ID, USER_ID, WS_ID)).thenThrow(new BizException("会话不存在"));

        assertThrows(BizException.class,
                () -> service.rate(MESSAGE_ID, new FeedbackRequest("UP", null, null), USER_ID, WS_ID));
        verify(messageRepository, never()).updateFeedback(anyLong(), anyString(), any(), any(), any());
    }
}
