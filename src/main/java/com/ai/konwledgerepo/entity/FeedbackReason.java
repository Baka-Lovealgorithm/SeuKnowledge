package com.ai.konwledgerepo.entity;

/**
 * 点踩原因码（DB 存 name()，前端展示用 label）。
 * <p>
 * 允许为空：用户可跳过原因直接提交点踩，采集率优先于字段完整度。
 */
public enum FeedbackReason {

    /** 没答到点上 */
    NOT_ON_TARGET("没答到点上"),
    /** 信息过时 */
    OUTDATED("信息过时"),
    /** 引用不对或没引用 */
    WRONG_CITATION("引用不对"),
    /** 太啰嗦 */
    TOO_VERBOSE("太啰嗦"),
    /** 其它（可配自由文本） */
    OTHER("其它");

    private final String label;

    FeedbackReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static FeedbackReason of(String v) {
        if (v == null) {
            return null;
        }
        for (FeedbackReason r : values()) {
            if (r.name().equals(v)) {
                return r;
            }
        }
        return null;
    }
}
