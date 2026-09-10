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

    /**
     * 截断到最大长度，<b>null 保持 null</b>（区别于 {@link #truncate(String, int)} 的 null→""）。
     * 用于「有值才留痕」的场景：审计日志的前后内容字段，null 表示无值，不该被写成空串。
     */
    public static String truncateOrNull(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 哈希短化：保留前 n 位用于日志可读（不用于业务判定）；null 返回空串。
     * 收敛 DocumentParserService 与 ParseCacheService 的同名实现。
     */
    public static String shortHash(String hash) {
        return shortHash(hash, 12);
    }

    /** 哈希短化：保留前 max 位；null 返回空串 */
    public static String shortHash(String hash, int max) {
        if (hash == null) {
            return "";
        }
        return hash.length() <= max ? hash : hash.substring(0, max);
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

    /**
     * 错误消息友好化：底层英文网络异常（超时/连接中断等）映射为中文提示；
     * 已有中文语义的消息（含"超时/繁忙/稍后重试/请检查"等）原样保留。
     */
    public static String friendlyError(String msg) {
        if (msg == null || msg.isBlank()) {
            return "问答失败，请稍后重试";
        }
        // 类别名中文化（LlmTrace 抛出的 LlmTimeoutException 消息含类别名）
        msg = msg.replace("text 调用", "文本生成 调用")
                 .replace("stream 调用", "文本生成(流式) 调用")
                 .replace("vision 调用", "识图 调用")
                 .replace("embedding 调用", "向量化 调用");
        if (msg.contains("超时") || msg.contains("繁忙") || msg.contains("稍后重试") || msg.contains("请检查")) {
            return msg;
        }
        if (msg.matches("(?i).*(timed out|timeout|i/o error|request was interrupted|connection (reset|refused|closed)|read timed out|failed to fetch).*")) {
            return "模型服务调用超时或连接中断，请稍后重试";
        }
        return msg;
    }
}
