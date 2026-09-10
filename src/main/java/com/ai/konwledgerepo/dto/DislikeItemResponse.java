package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;

/**
 * 点踩明细一行：答案摘要 + 触发它的问题 + 当时的自检快照。
 * <p>
 * 快照字段对存量数据为 null（采集上线前的行没有快照），前端显示「—」而不是 0。
 *
 * @param messageId         答案消息 id
 * @param sessionId         所属会话 id（明细不跳转，仅供人工回查）
 * @param sessionTitle      会话标题
 * @param question          该答案对应的提问原文（同会话中 id 小于它最近一条 USER 消息）
 * @param answerExcerpt     答案摘要（截断 200 字）
 * @param reasonCode        原因码，null = 未填
 * @param reasonLabel       原因中文名，null = 未填
 * @param note              自由文本补充，可为空
 * @param verifyScore       自检完整性分快照
 * @param faithfulnessScore 事实一致性快照
 * @param retryCount        重试轮数快照
 * @param missingInfo       自检「缺失信息」快照
 * @param createdAt         答案落库时间
 * @param feedbackAt        点踩时间
 * @param interrupted       该答案是否被中途停止
 */
public record DislikeItemResponse(Long messageId,
                                  Long sessionId,
                                  String sessionTitle,
                                  String question,
                                  String answerExcerpt,
                                  String reasonCode,
                                  String reasonLabel,
                                  String note,
                                  Double verifyScore,
                                  Double faithfulnessScore,
                                  Integer retryCount,
                                  String missingInfo,
                                  LocalDateTime createdAt,
                                  LocalDateTime feedbackAt,
                                  Boolean interrupted) {
}
