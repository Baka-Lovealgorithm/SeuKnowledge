package com.ai.konwledgerepo.entity;

/**
 * 知识库状态（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum KbStatus {

    DRAFT,
    BUILDING,
    AVAILABLE,
    ERROR,
    DISABLED;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static KbStatus of(String v) {
        if (v == null) {
            return null;
        }
        for (KbStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }

    /** 判断给定字符串是否为合法状态（替代原 KnowledgeBaseService.VALID_STATUS 集合） */
    public static boolean isValid(String v) {
        return of(v) != null;
    }
}
