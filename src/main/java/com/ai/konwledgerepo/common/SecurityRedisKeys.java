package com.ai.konwledgerepo.common;

/** 安全域 Redis 键常量 */
public final class SecurityRedisKeys {
    private SecurityRedisKeys() {}
    public static String token(String token) { return RedisKeys.PREFIX + "token:" + token; }
    public static String memberList(Long userId) { return RedisKeys.PREFIX + "member:list:" + userId; }
    public static String memberListPattern(Long userId) { return RedisKeys.PREFIX + "member:list:" + userId + "*"; }
    public static String loginFail(String username) { return RedisKeys.PREFIX + "login:fail:" + username; }
    public static String loginLock(String username) { return RedisKeys.PREFIX + "login:lock:" + username; }
    /** 用户启用状态缓存（"1"=enabled，"0"=禁用/不存在）：禁用用户在 TTL 内即失去访问 */
    public static String userEnabled(Long userId) { return RedisKeys.PREFIX + "user:enabled:" + userId; }
}