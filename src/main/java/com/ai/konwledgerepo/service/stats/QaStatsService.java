package com.ai.konwledgerepo.service.stats;

import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.DislikeItemResponse;
import com.ai.konwledgerepo.dto.QaDislikePageResponse;
import com.ai.konwledgerepo.dto.QaOverviewResponse;
import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.FeedbackReason;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答反馈汇总（只读）。
 * <p>
 * 这是本系统**唯一**跨用户读取问答内容的地方：评价写入按会话属主收紧
 * （见 {@code MessageFeedbackService}），而统计必须看全空间才有意义。
 * 因此入口只在 {@code QaStatsController} 上加 {@code @AdminOrAbove} 暴露给
 * 空间 OWNER/ADMIN，明细接口留一条 INFO 日志作访问痕迹。
 * <p>
 * 口径分两类，混用会得出误导性数字：
 * <ul>
 * <li><b>全量口径</b>（回答数/点赞点踩/无证据/中断）：对窗口内所有 ASSISTANT 行统计，含采集上线前的存量行；</li>
 * <li><b>快照口径</b>（自检分、事实一致性、被踩答案自检分）：只统计 verify_score 非空的行，
 * 并同时返回 {@code snapshotCount} 自证分母——存量行与闲聊直答没有快照，
 * 把 null 当 0 参与均值会把分数压成假低。</li>
 * </ul>
 */
@Service
public class QaStatsService {

    private static final Logger log = LoggerFactory.getLogger(QaStatsService.class);

    /** 时间窗上下限：下限防"全表扫"，上限避免误传 99999 */
    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String UNFILLED_LABEL = "未填写原因";

    private final ChatMessageRepository messageRepository;
    private final KnowledgeBaseService kbService;

    public QaStatsService(ChatMessageRepository messageRepository, KnowledgeBaseService kbService) {
        this.messageRepository = messageRepository;
        this.kbService = kbService;
    }

    /**
     * 总览：一条聚合查询出全部标量指标 + 一条分组查询出原因分布。
     * 空间内没有知识库时直接返回零值，不下发 {@code in ()}（那会是非法 SQL）。
     */
    public QaOverviewResponse overview(Long workspaceId, Long userId, int days) {
        int window = clampDays(days);
        Collection<Long> kbIds = visibleKbIds(workspaceId, userId);
        if (kbIds.isEmpty()) {
            return new QaOverviewResponse(window, 0, 0, 0, 0.0, 0, 0, 0, List.of(), 0, null, null, null);
        }
        LocalDateTime from = LocalDateTime.now().minusDays(window);

        List<Object[]> rows = messageRepository.aggregateFeedbackOverview(kbIds, from);
        long answerCount = 0;
        long like = 0;
        long dislike = 0;
        long interrupted = 0;
        long noEvidence = 0;
        long snapshot = 0;
        Double avgVerify = null;
        Double avgFaith = null;
        Double avgVerifyOfDisliked = null;
        if (!rows.isEmpty() && rows.get(0) != null) {
            Object[] r = rows.get(0);
            // 下标语义与 ChatMessageRepository#aggregateFeedbackOverview 的注释一一对应
            answerCount = asLong(r[0]);
            like = asLong(r[1]);
            dislike = asLong(r[2]);
            interrupted = asLong(r[3]);
            noEvidence = asLong(r[4]);
            snapshot = asLong(r[5]);
            avgVerify = asDouble(r[6]);
            avgFaith = asDouble(r[7]);
            avgVerifyOfDisliked = asDouble(r[8]);
        }
        long rated = like + dislike;
        double rate = rated == 0 ? 0.0 : round((double) dislike / rated);

        List<QaOverviewResponse.ReasonCount> reasons = messageRepository
                .aggregateDislikeReasons(kbIds, from).stream()
                .map(pair -> new QaOverviewResponse.ReasonCount(
                        Texts.strOrNull(pair[0]),
                        labelOf(Texts.strOrNull(pair[0])),
                        asLong(pair[1])))
                .toList();

        return new QaOverviewResponse(window, answerCount, like, dislike, rate, rated,
                noEvidence, interrupted, reasons, snapshot, avgVerify, avgFaith, avgVerifyOfDisliked);
    }

    /**
     * 点踩明细：答案摘要 + 触发它的问题 + 当时的自检快照。
     * 原问题用一条批量查询取回（同会话中 id 小于该答案的最近一条 USER 消息），
     * 不逐行查，避免翻页时 N+1。
     */
    public QaDislikePageResponse dislikes(Long workspaceId, Long userId, int days, int page, int size) {
        int window = clampDays(days);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Collection<Long> kbIds = visibleKbIds(workspaceId, userId);
        if (kbIds.isEmpty()) {
            return new QaDislikePageResponse(List.of(), 0, safePage, safeSize);
        }
        LocalDateTime from = LocalDateTime.now().minusDays(window);
        long total = messageRepository.countDislikedAnswers(kbIds, from);
        if (total == 0) {
            return new QaDislikePageResponse(List.of(), 0, safePage, safeSize);
        }
        List<Object[]> rows = messageRepository.findDislikedAnswers(kbIds, from, PageRequest.of(safePage, safeSize));
        List<ChatMessage> answers = rows.stream().map(r -> (ChatMessage) r[0]).toList();
        Map<Long, String> questions = questionsOf(answers);

        List<DislikeItemResponse> items = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            ChatMessage m = answers.get(i);
            String title = (String) rows.get(i)[1];
            String reason = m.getFeedbackReason();
            items.add(new DislikeItemResponse(m.getId(), m.getSessionId(), title,
                    questions.get(m.getId()), Texts.truncate(m.getContent(), 200),
                    reason, labelOf(reason), m.getFeedbackNote(),
                    m.getVerifyScore(), m.getFaithfulnessScore(), m.getRetryCount(), m.getMissingInfo(),
                    m.getCreatedAt(), m.getFeedbackAt(), m.getInterrupted()));
        }
        // 跨用户读取他人问答内容的唯一出口，留访问痕迹（requestId/userId 由 MDC 携带）
        log.info("QA 点踩明细查询 ws={} user={} days={} page={} size={} total={}",
                workspaceId, userId, window, safePage, safeSize, total);
        return new QaDislikePageResponse(items, total, safePage, safeSize);
    }

    /** 答案 id → 触发它的问题原文；取不到（如首条即答案的历史数据）时留空 */
    private Map<Long, String> questionsOf(List<ChatMessage> answers) {
        List<Long> ids = answers.stream().map(ChatMessage::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>();
        for (Object[] pair : messageRepository.findQuestionByAnswerIds(ids)) {
            if (pair[0] != null && pair[1] != null) {
                map.put(((Number) pair[0]).longValue(), Texts.truncate(pair[1].toString(), 500));
            }
        }
        return map;
    }

    /**
     * 当前空间的 kbId 集合。
     * 走 {@link KnowledgeBaseService#list}：调用方已被限制为 OWNER/ADMIN，
     * 其可见性过滤对管理员天然返回空间内**全部**知识库（含 RESTRICTED），
     * 因此统计数字不会被 ACL 裁成对不上的值；这里也不再叠一层 kbIds 空判之外的过滤。
     */
    private Collection<Long> visibleKbIds(Long workspaceId, Long userId) {
        return kbService.list(workspaceId, userId).stream()
                .map(com.ai.konwledgerepo.dto.KbResponse::id)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String labelOf(String reasonCode) {
        FeedbackReason reason = FeedbackReason.of(reasonCode);
        return reason == null ? UNFILLED_LABEL : reason.label();
    }

    private static int clampDays(int days) {
        return Math.min(Math.max(days, MIN_DAYS), MAX_DAYS);
    }

    /** 聚合列的 null 安全读数：sum/count 在零行时本身就是 null，不是 0 */
    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? round(n.doubleValue()) : null;
    }

    /** 比率/均值保留 4 位小数：JSON 里不该出现 0.3333333333333333 */
    private static Double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0.0;
        }
        return Math.round(v * 10000d) / 10000d;
    }
}
