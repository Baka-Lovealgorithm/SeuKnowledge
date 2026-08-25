package com.ai.konwledgerepo.entity;

/**
 * 会话消息角色（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum MessageRole {

    USER,
    ASSISTANT;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static MessageRole of(String v) {
        if (v == null) {
            return null;
        }
        for (MessageRole s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
