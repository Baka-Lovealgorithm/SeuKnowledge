package com.ai.konwledgerepo.entity;

/**
 * AI 抽取任务类型（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum ExtractType {

    BUSINESS,
    QA,
    BOTH;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static ExtractType of(String v) {
        if (v == null) {
            return null;
        }
        for (ExtractType s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
