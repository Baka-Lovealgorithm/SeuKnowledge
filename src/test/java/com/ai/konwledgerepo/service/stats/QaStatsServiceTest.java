package com.ai.konwledgerepo.service.stats;

import com.ai.konwledgerepo.dto.DislikeItemResponse;
import com.ai.konwledgerepo.dto.KbResponse;
import com.ai.konwledgerepo.dto.QaDislikePageResponse;
import com.ai.konwledgerepo.dto.QaOverviewResponse;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.MessageRole;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答反馈汇总口径。重点钉四件事：
 * <ul>
 *   <li><b>踩率分母是「已评价数」而不是「回答总数」</b>——否则没人评价的空间永远是 0%，
 *       而只有几个人踩过的高使用空间反而显得"问题很少"；</li>
 *   <li><b>自检均值只算含快照的行</b>，且样本为 0 时返回 null 而不是 0/NaN
 *       （NaN 会让 JSON 序列化直接失败，把整个接口打挂）；</li>
 *   <li><b>空间内无知识库时必须短路</b>：JPQL 的 {@code in :kbIds} 遇空集合生成非法 SQL；</li>
 *   <li><b>明细分页只发一次批量查询</b>取原问题，不退化成每行一次 N+1。</li>
 * </ul>
 */
class QaStatsServiceTest {

    private static final Long WS_ID = 42L;
    private static final Long USER_ID = 1L;
    private static final Long KB_ID = 9L;

    private ChatMessageRepository messageRepository;
    private KnowledgeBaseService kbService;
    private QaStatsService service;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ChatMessageRepository.class);
        kbService = mock(KnowledgeBaseService.class);
        service = new QaStatsService(messageRepository, kbService);
        when(kbService.list(WS_ID, USER_ID)).thenReturn(List.of(kb()));
    }

    private static KbResponse kb() {
        return new KbResponse(KB_ID, "测试库", null, "ACTIVE", false, 0L, LocalDateTime.now(), "PUBLIC", USER_ID);
    }

    /** 聚合列序：0 回答数 1 赞 2 踩 3 中断 4 无证据 5 快照数 6 自检均值 7 一致性均值 8 被踩自检均值 */
    private void stubOverview(Object... cells) {
        // 一行结果集：cells 本身就是那一行的 Object[]，不能让 List.of 把它摊平
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(cells);
        when(messageRepository.aggregateFeedbackOverview(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(rows);
    }

    // ===== 总览口径 =====

    @Test
    void overview_dislikeRate_usesRatedCountNotAnswerCount() {
        // 10 条回答、1 赞 3 踩 → 踩率 3/4=0.75；若错用回答总数做分母会得到 0.3
        stubOverview(10L, 1L, 3L, 0L, 0L, 0L, null, null, null);

        QaOverviewResponse r = service.overview(WS_ID, USER_ID, 30);

        assertEquals(10L, r.answerCount());
        assertEquals(4L, r.ratedCount());
        assertEquals(0.75, r.dislikeRate(), 1e-9, "踩率必须以「已评价数」为分母");
    }

    @Test
    void overview_nothingRated_rateIsZeroNotNull() {
        stubOverview(5L, 0L, 0L, 0L, 0L, 0L, null, null, null);

        QaOverviewResponse r = service.overview(WS_ID, USER_ID, 30);

        assertEquals(0.0, r.dislikeRate(), 1e-9);
        assertFalse(Double.isNaN(r.dislikeRate()), "NaN 会让 JSON 序列化失败，必须归零");
    }

    /** sum() 在零行时返回 null；另外 Hibernate 对 sum(case…) 可能给 Integer 而非 Long，读数不得炸 */
    @Test
    void overview_nullAndIntegerCells_readDefensively() {
        stubOverview(0L, null, null, null, null, 0L, null, null, null);

        QaOverviewResponse r = service.overview(WS_ID, USER_ID, 7);

        assertEquals(0L, r.dislikeCount(), "null 计数按 0 处理");
        assertEquals(0L, r.answerCount());
        assertNull(r.avgVerifyScore());

        // Integer 型计数列（方言差异）也必须能读
        stubOverview(8L, 2, 6, 1, 2, 3L, null, null, null);
        QaOverviewResponse ints = service.overview(WS_ID, USER_ID, 7);
        assertEquals(6L, ints.dislikeCount());
        assertEquals(0.75, ints.dislikeRate(), 1e-9);
    }

    /** 快照均值的口径：只统计含快照的行；无快照时返回 null（不是 0 分） */
    @Test
    void overview_snapshotAverages_keptAsGiven_andNullWhenNoSnapshot() {
        stubOverview(10L, 1L, 3L, 2L, 4L, 6L, 0.7, 0.5, 0.2);
        QaOverviewResponse withSnapshot = service.overview(WS_ID, USER_ID, 30);
        assertEquals(6L, withSnapshot.snapshotCount());
        assertEquals(0.7, withSnapshot.avgVerifyScore(), 1e-9);
        assertEquals(0.2, withSnapshot.avgVerifyScoreOfDisliked(), 1e-9,
                "被踩答案的自检分要单独给出，与整体对比才有意义");

        stubOverview(10L, 0L, 0L, 0L, 0L, 0L, null, null, null);
        QaOverviewResponse legacy = service.overview(WS_ID, USER_ID, 30);
        assertNull(legacy.avgVerifyScore(), "存量行无快照时均值应为 null，让前端显示「—」而不是 0.00");
    }

    /** 比率/均值保留 4 位小数，避免响应体出现 0.3333333333333333 */
    @Test
    void overview_roundsToFourDecimals() {
        stubOverview(3L, 1L, 1L, 0L, 0L, 3L, 0.33333333, 0.66666666, null);

        QaOverviewResponse r = service.overview(WS_ID, USER_ID, 30);

        assertEquals(0.5, r.dislikeRate(), 1e-9);
        assertEquals(0.3333, r.avgVerifyScore(), 1e-9);
        assertEquals(0.6667, r.avgFaithfulness(), 1e-9);
    }

    /** 没有知识库的空间必须短路，绝不能把空集合带进 in 子句 */
    @Test
    void overview_workspaceWithoutKb_shortCircuitsWithoutQuery() {
        when(kbService.list(WS_ID, USER_ID)).thenReturn(List.of());

        QaOverviewResponse r = service.overview(WS_ID, USER_ID, 30);

        assertEquals(0L, r.answerCount());
        assertEquals(0.0, r.dislikeRate(), 1e-9);
        assertTrue(r.reasonBreakdown().isEmpty());
        verify(messageRepository, never()).aggregateFeedbackOverview(anyCollection(), any());
        verify(messageRepository, never()).aggregateDislikeReasons(anyCollection(), any());
    }

    /** 时间窗必须被夹到 1~365：days=0/99999 不能变成"全表扫"或非法区间 */
    @Test
    void overview_daysClamped() {
        stubOverview(0L, 0L, 0L, 0L, 0L, 0L, null, null, null);

        assertEquals(1, service.overview(WS_ID, USER_ID, 0).days());
        assertEquals(365, service.overview(WS_ID, USER_ID, 99999).days());

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        service.overview(WS_ID, USER_ID, 99999);
        verify(messageRepository, times(3)).aggregateFeedbackOverview(anyCollection(), from.capture());
        LocalDateTime last = from.getAllValues().get(from.getAllValues().size() - 1);
        assertFalse(last.isAfter(LocalDateTime.now().minusDays(364)),
                "days=99999 应被夹到 365 天窗口，实际起点 " + last);
    }

    /** 传给聚合查询的 kbId 集合就是当前空间的知识库集合（租户边界） */
    @Test
    void overview_scopedToWorkspaceKbs() {
        stubOverview(1L, 0L, 0L, 0L, 0L, 0L, null, null, null);
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<Long>> kbIds = ArgumentCaptor.forClass(Collection.class);

        service.overview(WS_ID, USER_ID, 30);

        verify(messageRepository).aggregateFeedbackOverview(kbIds.capture(), any());
        assertEquals(List.of(KB_ID), List.copyOf(kbIds.getValue()));
    }

    /** 原因分布里未填原因的那一桶（DB 值为 null）要带可读 label，而不是显示空字符串 */
    @Test
    void overview_unfilledReasonBucketLabeled() {
        stubOverview(10L, 0L, 4L, 0L, 0L, 0L, null, null, null);
        when(messageRepository.aggregateDislikeReasons(anyCollection(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{null, 3L}, new Object[]{"OUTDATED", 1L}));

        QaOverviewResponse r = service.overview(WS_ID, USER_ID, 30);

        assertEquals(2, r.reasonBreakdown().size());
        assertEquals("未填写原因", r.reasonBreakdown().get(0).label());
        assertNull(r.reasonBreakdown().get(0).code());
        assertEquals("信息过时", r.reasonBreakdown().get(1).label());
        assertEquals(1L, r.reasonBreakdown().get(1).count());
    }

    // ===== 明细 =====

    @Test
    void dislikes_totalZero_skipsPageQuery() {
        when(messageRepository.countDislikedAnswers(anyCollection(), any())).thenReturn(0L);

        QaDislikePageResponse page = service.dislikes(WS_ID, USER_ID, 30, 0, 20);

        assertTrue(page.items().isEmpty());
        assertEquals(0L, page.total());
        verify(messageRepository, never()).findDislikedAnswers(anyCollection(), any(), any());
    }

    @Test
    void dislikes_noKb_shortCircuits() {
        when(kbService.list(WS_ID, USER_ID)).thenReturn(List.of());

        QaDislikePageResponse page = service.dislikes(WS_ID, USER_ID, 30, 0, 20);

        assertEquals(0L, page.total());
        verify(messageRepository, never()).countDislikedAnswers(anyCollection(), any());
    }

    /** 明细映射：答案摘要截断、原问题批量取回、null 快照保持 null */
    @Test
    void dislikes_mapsRowsAndAnswersSnapshot() {
        ChatMessage answer = new ChatMessage();
        answer.setId(101L);
        answer.setSessionId(7L);
        answer.setRole(MessageRole.ASSISTANT.value());
        answer.setContent("y".repeat(300));
        answer.setFeedback("DOWN");
        answer.setFeedbackReason("WRONG_CITATION");
        answer.setFeedbackNote("引错了");
        answer.setVerifyScore(0.4);
        answer.setFaithfulnessScore(null);
        answer.setRetryCount(2);
        answer.setMissingInfo("缺少安装步骤");
        answer.setCreatedAt(LocalDateTime.now());
        answer.setFeedbackAt(LocalDateTime.now());
        answer.setInterrupted(Boolean.FALSE);

        when(messageRepository.countDislikedAnswers(anyCollection(), any())).thenReturn(1L);
        when(messageRepository.findDislikedAnswers(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{answer, "会话 A"}));
        when(messageRepository.findQuestionByAnswerIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{101L, "怎么安装？"}));

        QaDislikePageResponse page = service.dislikes(WS_ID, USER_ID, 30, 0, 20);

        assertEquals(1, page.items().size());
        DislikeItemResponse item = page.items().get(0);
        assertEquals(101L, item.messageId());
        assertEquals("会话 A", item.sessionTitle());
        assertEquals("怎么安装？", item.question());
        assertEquals(200, item.answerExcerpt().length(), "答案摘要按 200 字截断");
        assertEquals("引用不对", item.reasonLabel());
        assertEquals("引错了", item.note());
        assertEquals(0.4, item.verifyScore());
        assertNull(item.faithfulnessScore(), "未跑阶段二时一致性快照为 null，不能读成 0");
        assertEquals(2, item.retryCount());
        assertEquals("缺少安装步骤", item.missingInfo());
    }

    /** 原问题必须一次批量查完（防每行一次的 N+1） */
    @Test
    void dislikes_fetchesQuestionsInSingleBatch() {
        ChatMessage a = answer(1L);
        ChatMessage b = answer(2L);
        when(messageRepository.countDislikedAnswers(anyCollection(), any())).thenReturn(2L);
        when(messageRepository.findDislikedAnswers(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{a, "S1"}, new Object[]{b, "S2"}));
        when(messageRepository.findQuestionByAnswerIds(anyCollection())).thenReturn(List.of());

        QaDislikePageResponse page = service.dislikes(WS_ID, USER_ID, 30, 0, 20);

        assertEquals(2, page.items().size());
        verify(messageRepository, times(1)).findQuestionByAnswerIds(anyCollection());
    }

    /** 答案行没有前一条 USER 消息（异常数据）时问题留空，不能抛异常拖垮整页 */
    @Test
    void dislikes_missingQuestionRow_leavesQuestionNull() {
        when(messageRepository.countDislikedAnswers(anyCollection(), any())).thenReturn(1L);
        when(messageRepository.findDislikedAnswers(anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{answer(5L), "S1"}));
        when(messageRepository.findQuestionByAnswerIds(anyCollection())).thenReturn(List.of());

        QaDislikePageResponse page = service.dislikes(WS_ID, USER_ID, 30, 0, 20);

        assertNull(page.items().get(0).question());
    }

    /** 页大小/页码要收敛：size=0 或负数会生成 limit 0 / offset 负数 */
    @Test
    void dislikes_clampsPaging() {
        when(messageRepository.countDislikedAnswers(anyCollection(), any())).thenReturn(9L);
        when(messageRepository.findDislikedAnswers(anyCollection(), any(), any())).thenReturn(List.of());

        QaDislikePageResponse zeroSize = service.dislikes(WS_ID, USER_ID, 30, -5, 0);
        assertEquals(1, zeroSize.size(), "size=0 会生成 limit 0，必须夹到 1 起");
        assertEquals(0, zeroSize.page(), "负页码归零");
        assertEquals(9L, zeroSize.total());

        QaDislikePageResponse huge = service.dislikes(WS_ID, USER_ID, 30, 0, 10000);
        assertEquals(100, huge.size(), "页大小上限 100，防一次拉穿明细");
    }

    private static ChatMessage answer(Long id) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(7L);
        m.setRole(MessageRole.ASSISTANT.value());
        m.setContent("答案 " + id);
        m.setFeedback("DOWN");
        return m;
    }
}
