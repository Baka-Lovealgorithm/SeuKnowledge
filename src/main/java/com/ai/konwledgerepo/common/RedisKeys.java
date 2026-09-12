package com.ai.konwledgerepo.common;

/**
 * Redis key 统一命名规范：seuknowledge:{域}:{标识}。
 * 所有缓存键必须带 TTL（见 RedisCacheService.set），防止陈旧数据与内存膨胀。
 */
public final class RedisKeys {

    public static final String PREFIX = "seuknowledge:";

    public static String token(String token) { return SecurityRedisKeys.token(token); }
    public static String memberList(Long userId) { return SecurityRedisKeys.memberList(userId); }
    public static String memberListPattern(Long userId) { return SecurityRedisKeys.memberListPattern(userId); }
    public static String modelResolve(Long workspaceId, String modelType, String usage) { return ModelRedisKeys.resolve(workspaceId, modelType, usage); }
    public static String modelDefault(Long workspaceId, String modelType) { return ModelRedisKeys.defaultConfig(workspaceId, modelType); }
    public static String modelConfig(Long id) { return ModelRedisKeys.config(id); }
    public static String agent(Long kbId) { return AgentRedisKeys.agent(kbId); }
    public static String promptTemplate(String key) { return PromptRedisKeys.template(key); }
    public static String kb(Long id) { return KbRedisKeys.kb(id); }
    public static String kbList(Long workspaceId) { return KbRedisKeys.kbList(workspaceId); }
    public static String kbCount(Long kbId) { return KbRedisKeys.kbCount(kbId); }
    public static String sessionList(Long userId, Long workspaceId) { return QaRedisKeys.sessionList(userId, workspaceId); }
    public static String messages(Long sessionId) { return QaRedisKeys.messages(sessionId); }
    public static String history(Long sessionId) { return QaRedisKeys.history(sessionId); }
    public static String summary(Long sessionId) { return QaRedisKeys.summary(sessionId); }
    public static String summaryGen(Long sessionId) { return QaRedisKeys.summaryGen(sessionId); }
    public static String task(Long taskId) { return TaskRedisKeys.task(taskId); }
    public static String rateLimit(Long userId) { return QaRedisKeys.rateLimit(userId); }
    public static String dup(Long userId, String hash) { return QaRedisKeys.dup(userId, hash); }
    public static String titleGen(Long sessionId) { return QaRedisKeys.titleGen(sessionId); }
    public static String docParse(Long docId) { return DocRedisKeys.docParse(docId); }
    public static String taskRun(Long taskId) { return TaskRedisKeys.taskRun(taskId); }
    public static String taskExtract(Long docId) { return TaskRedisKeys.taskExtract(docId); }
    public static String loginFail(String username) { return SecurityRedisKeys.loginFail(username); }
    public static String loginLock(String username) { return SecurityRedisKeys.loginLock(username); }
    public static String userEnabled(Long userId) { return SecurityRedisKeys.userEnabled(userId); }
    public static String askLock(Long sessionId) { return QaRedisKeys.askLock(sessionId); }

    private RedisKeys() {
    }
}
