package com.ai.konwledgerepo.common;

/**
 * 通用文本工具（收敛各服务内重复的 truncate / isBlank / str 实现）。
 */
public final class Texts {

    private Texts() {
    }

    /** 截断到最大长度；null 返回空串 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Object 转 String；null 返回 "" */
    public static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Object 转 String；null 返回 null（区别于 {@link #str}） */
    public static String strOrNull(Object o) {
        return o == null ? null : o.toString();
    }
}
