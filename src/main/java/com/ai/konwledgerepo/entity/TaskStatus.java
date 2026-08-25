package com.ai.konwledgerepo.entity;

/**
 * 抽取任务状态（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_FAILED,
    FAILED;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static TaskStatus of(String v) {
        if (v == null) {
            return null;
        }
        for (TaskStatus s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
