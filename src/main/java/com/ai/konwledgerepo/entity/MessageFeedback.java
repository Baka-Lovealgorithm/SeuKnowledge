package com.ai.konwledgerepo.entity;

/**
 * 用户对答案的反馈（DB 存 name() 字符串，与 {@link MessageRole} 同款做法）。
 * <p>
 * NONE 不入库，仅作为请求侧的「撤销评价」指令：收到时把 feedback/feedback_at/
 * feedback_reason/feedback_note 四列一并清空，回到「未评价」而不是留一条 NONE 行。
 */
public enum MessageFeedback {

    UP,
    DOWN;

    /** 撤销评价的请求值（不是枚举常量：库里不存在「评价=NONE」这种状态） */
    public static final String NONE_REQUEST = "NONE";

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    /** 请求值是否合法（含撤销值 NONE） */
    public static boolean validRequest(String v) {
        return NONE_REQUEST.equals(v) || of(v) != null;
    }

    public static MessageFeedback of(String v) {
        if (v == null) {
            return null;
        }
        for (MessageFeedback f : values()) {
            if (f.name().equals(v)) {
                return f;
            }
        }
        return null;
    }
}
