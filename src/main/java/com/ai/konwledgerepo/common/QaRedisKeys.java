package com.ai.konwledgerepo.common;

/** 问答域 Redis 键常量 */
public final class QaRedisKeys {
    private QaRedisKeys() {}

    public static String askLock(Long sessionId) { return RedisKeys.PREFIX + "ask:lock:" + sessionId; }
    public static String history(Long sessionId) { return RedisKeys.PREFIX + "history:v2:" + sessionId; }
    public static String summary(Long sessionId) { return RedisKeys.PREFIX + "summary:v2:" + sessionId; }
    public static String summaryGen(Long sessionId) { return RedisKeys.PREFIX + "summaryGen:" + sessionId; }
    public static String messages(Long sessionId) { return RedisKeys.PREFIX + "msgs:" + sessionId; }
    public static String sessionList(Long userId, Long workspaceId) { return RedisKeys.PREFIX + "sessionlist:" + userId + ":" + workspaceId; }
    public static String titleGen(Long sessionId) { return RedisKeys.PREFIX + "titleGen:" + sessionId; }
    public static String rateLimit(Long userId) { return RedisKeys.PREFIX + "rl:" + userId; }
    public static String dup(Long userId, String hash) { return RedisKeys.PREFIX + "dup:" + userId + ":" + hash; }
}