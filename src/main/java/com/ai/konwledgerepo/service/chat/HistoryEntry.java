package com.ai.konwledgerepo.service.chat;

/**
 * 会话记忆窗口条目快照（可序列化，用于 Redis 缓存，仅含链路所需字段）。
 */
public record HistoryEntry(String role, String content, Boolean interrupted) {
    public HistoryEntry(String role, String content) {
        this(role, content, false);
    }
}
