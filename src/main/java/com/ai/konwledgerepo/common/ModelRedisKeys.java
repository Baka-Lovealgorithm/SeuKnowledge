package com.ai.konwledgerepo.common;

/** 模型配置域 Redis 键常量 */
public final class ModelRedisKeys {
    private ModelRedisKeys() {}

    public static String resolve(Long workspaceId, String modelType, String usage) {
        return RedisKeys.PREFIX + "model:resolve:" + workspaceId + ":" + modelType + ":"
                + (usage == null || usage.isBlank() ? "none" : usage);
    }
    public static String defaultConfig(Long workspaceId, String modelType) { return RedisKeys.PREFIX + "model:default:" + workspaceId + ":" + modelType; }
    public static String config(Long id) { return RedisKeys.PREFIX + "model:cfg:" + id; }
}