package com.ai.konwledgerepo.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 哈希工具（收敛 ChatService / RateLimitService 内重复的 SHA-256 实现）。
 */
public final class Hashing {

    private Hashing() {
    }

    /** SHA-256 十六进制摘要（UTF-8） */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
