package com.ai.konwledgerepo.dto;

import java.util.List;

/**
 * 问答反馈汇总（按当前工作空间 + 时间窗）。
 * <p>
 * 口径分两类：
 * <ul>
 * <li>点踩/健康度指标（answerCount / dislikeCount / dislikeRate / noEvidenceCount / interruptedCount）
 * 对**全部**回答统计，含本次采集上线前的存量行；</li>
 * <li>自检快照指标（avgVerifyScore / avgFaithfulness / avgVerifyScoreOfDisliked）只统计
 * verify_score 非空的行，并用 {@code snapshotCount} 自证口径——存量行与闲聊直答没有快照，
 * 把它们的 null 当 0 分参与均值会把数字压成假低。</li>
 * </ul>
 * 所有均值/比率在样本为 0 时返回 null 或 0.0，**绝不返回 NaN**（NaN 会让 JSON 序列化直接失败）。
 *
 * @param days                     统计窗口（天）
 * @param answerCount              窗口内 ASSISTANT 回答数
 * @param likeCount                点赞数
 * @param dislikeCount             点踩数
 * @param dislikeRate              踩率 = DOWN / (UP+DOWN)，无人评价时为 0.0
 * @param ratedCount               UP+DOWN 合计（踩率分母，0 表示尚无人评价）
 * @param noEvidenceCount          无证据回答数（refs 为空或 {@code []}）
 * @param interruptedCount         被中途停止的回答数
 * @param reasonBreakdown          点踩原因分布（按数量倒序，含未填原因的 NULL 桶）
 * @param snapshotCount            含自检快照的回答数（均值口径的分母）
 * @param avgVerifyScore           自检完整性分均值（仅快照行）
 * @param avgFaithfulness          事实一致性均值（仅快照行）
 * @param avgVerifyScoreOfDisliked 被踩答案的自检分均值（与 avgVerifyScore 对比即"踩的是不是低分答案"）
 */
public record QaOverviewResponse(int days,
                                 long answerCount,
                                 long likeCount,
                                 long dislikeCount,
                                 double dislikeRate,
                                 long ratedCount,
                                 long noEvidenceCount,
                                 long interruptedCount,
                                 List<ReasonCount> reasonBreakdown,
                                 long snapshotCount,
                                 Double avgVerifyScore,
                                 Double avgFaithfulness,
                                 Double avgVerifyScoreOfDisliked) {

    /**
     * 点踩原因分布一行。
     *
     * @param code  原因码（FeedbackReason 的 name；null 表示用户跳过了原因）
     * @param label 中文展示名（未填原因的桶显示「未填写原因」）
     * @param count 条数
     */
    public record ReasonCount(String code, String label, long count) {
    }
}
