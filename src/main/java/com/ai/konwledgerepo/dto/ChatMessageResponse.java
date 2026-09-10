package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.ChatMessage;

import java.time.LocalDateTime;

/**
 * 会话消息响应。
 * <p>
 * 本 record 同时是 Redis 消息列表缓存的序列化载体（{@code RedisKeys.messages}），
 * 所以加字段要考虑**旧缓存读新代码**：Jackson 对缺失属性传 null，
 * {@code RedisCacheService} 又整体 fail-open，因此无需清缓存即可兼容（回归见
 * {@code RedisCacheServiceTest#getList_oldCachePayloadWithoutNewFieldsStillDeserializes}）。
 *
 * @param intent          意图分析结果（BUSINESS / CHITCHAT 等）
 * @param feedback        用户评价 UP / DOWN，null = 未评价
 * @param feedbackReason  点踩原因码，null = 未填
 * @param feedbackNote    点踩补充说明（前端回显用，可为空）
 * @param verifyScore     答案自检完整性分快照（仅新数据有值）
 * @param faithfulnessScore 事实一致性快照（仅新数据有值）
 */
public record ChatMessageResponse(Long id, String role, String content, String refs, LocalDateTime createdAt,
                                   Boolean interrupted, String intent, String feedback, String feedbackReason,
                                   String feedbackNote, Double verifyScore, Double faithfulnessScore) {

    public static ChatMessageResponse from(ChatMessage m) {
        return new ChatMessageResponse(m.getId(), m.getRole(), m.getContent(), m.getRefs(), m.getCreatedAt(),
                m.getInterrupted(), m.getIntent(), m.getFeedback(), m.getFeedbackReason(), m.getFeedbackNote(),
                m.getVerifyScore(), m.getFaithfulnessScore());
    }
}
