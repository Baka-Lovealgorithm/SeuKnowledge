package com.ai.konwledgerepo.dto;

/**
 * 问答响应：答案 + 证据引用 + 意图 + 落库后的答案消息 id。
 * <p>
 * {@code messageId} 是给反馈接口用的定位键：答案落在 kb_chat_message 的 ASSISTANT 行上，
 * 前端拿不到 id 就没法点踩。流式路径由 SSE {@code done} 事件的 data 携带同值
 * （见 ChatStreamService），两条路径保持一致。
 * 仅落 USER 消息的路径（首 token 前停止）没有答案行，此时为 null。
 */
public record AskResponse(String answer, String refs, String intent, Long messageId) {
}
