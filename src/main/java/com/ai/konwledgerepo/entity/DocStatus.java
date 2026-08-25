package com.ai.konwledgerepo.entity;

/**
 * 文档解析状态（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum DocStatus {

    PENDING,
    PARSING,
    SUCCESS,
    FAILED,
    ERROR;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static DocStatus of(String v) {
        if (v == null) {
            return null;
        }
        for (DocStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
