package com.ai.konwledgerepo.common;

/** 知识库域 Redis 键常量 */
final class KbRedisKeys {
    private KbRedisKeys() {}
    static String kb(Long id) { return RedisKeys.PREFIX + "kb:" + id; }
    static String kbList(Long workspaceId) { return RedisKeys.PREFIX + "kb:list:" + workspaceId; }
    static String kbCount(Long kbId) { return RedisKeys.PREFIX + "kb:count:" + kbId; }
}

/** 任务/抽取域 Redis 键常量 */
final class TaskRedisKeys {
    private TaskRedisKeys() {}
    static String task(Long taskId) { return RedisKeys.PREFIX + "task:" + taskId; }
    static String taskRun(Long taskId) { return RedisKeys.PREFIX + "task:run:" + taskId; }
    static String taskExtract(Long docId) { return RedisKeys.PREFIX + "task:extract:" + docId; }
}

/** 文档解析域 Redis 键常量 */
final class DocRedisKeys {
    private DocRedisKeys() {}
    static String docParse(Long docId) { return RedisKeys.PREFIX + "doc:parse:" + docId; }
}

/** Agent 配置域 Redis 键常量 */
final class AgentRedisKeys {
    private AgentRedisKeys() {}
    static String agent(Long kbId) { return RedisKeys.PREFIX + "agent:" + kbId; }
}

/** 提示词模板域 Redis 键常量 */
final class PromptRedisKeys {
    private PromptRedisKeys() {}
    static String template(String key) { return RedisKeys.PREFIX + "prompt:" + key; }
}