package com.ai.konwledgerepo.entity;

/**
 * 多源召回的证据来源类型（ES sourceType 字段值，保持与旧数据一致）。
 * null 语义：存量数据无 sourceType 字段视为 CHUNK（见 {@link #normalize}）。
 */
public enum SourceType {

    CHUNK,
    BUSINESS,
    QA;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static SourceType of(String v) {
        if (v == null) {
            return null;
        }
        for (SourceType s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }

    /** 归一化来源类型：null / 未知一律视为 CHUNK（兼容存量数据与外部入参） */
    public static String normalize(String v) {
        return v == null ? CHUNK.value() : v;
    }

    /** 证据去重键：sourceType:chunkId（null sourceType 按 CHUNK 计） */
    public static String dedupKey(String sourceType, Long chunkId) {
        return normalize(sourceType) + ":" + chunkId;
    }
}
