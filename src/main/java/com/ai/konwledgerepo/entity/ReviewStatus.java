package com.ai.konwledgerepo.entity;

/**
 * 业务知识 / 问答对的审核状态（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum ReviewStatus {

    DRAFT,
    APPROVED,
    REJECTED,
    DISABLED;

    /** DB 存储值（与枚举名一致） */
    public String value() {
        return name();
    }

    /** 判断实体字段当前值是否等于本状态；null 安全 */
    public boolean is(String v) {
        return name().equals(v);
    }

    /** 容错解析：未知/空返回 null */
    public static ReviewStatus of(String v) {
        if (v == null) {
            return null;
        }
        for (ReviewStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
