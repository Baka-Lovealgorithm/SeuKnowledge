package com.ai.konwledgerepo.common;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 密码策略与随机生成（与 Dify 同款策略）：至少 8 位且必须同时包含字母和数字。
 * 管理员重置密码时生成 12 位随机串，保证同时含两类字符，且用户拿到后必然可通过策略校验。
 */
public final class Passwords {

    /** 随机密码长度（字母 + 数字，去除易混淆字符） */
    private static final int GENERATED_LENGTH = 12;

    /** 与长度语义一致的策略校验正则：至少 8 位、必须同时含字母与数字 */
    private static final String POLICY_PATTERN = "^(?=.*[a-zA-Z])(?=.*\\d).{8,}$";

    /** 生成字符集：去除 0/O/1/l/I 等易混淆字符 */
    private static final char[] GENERATE_CHARS =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private Passwords() {
    }

    /** 新密码策略校验：不合规抛业务异常（提示语面向最终用户） */
    public static void validate(String password) {
        if (password == null || !password.matches(POLICY_PATTERN)) {
            throw new BizException("新密码至少 8 位，且必须同时包含字母和数字");
        }
    }

    /** 生成 12 位随机密码（保证至少含一个字母和一个数字） */
    public static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        do {
            sb.setLength(0);
            for (int i = 0; i < GENERATED_LENGTH; i++) {
                sb.append(GENERATE_CHARS[random.nextInt(GENERATE_CHARS.length)]);
            }
        } while (!sb.toString().matches(POLICY_PATTERN));
        return sb.toString();
    }
}
