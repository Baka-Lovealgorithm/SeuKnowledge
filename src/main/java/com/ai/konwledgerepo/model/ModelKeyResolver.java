package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;

/**
 * 模型 apiKey 解析：支持环境变量引用，避免真实 key 落库/落日志。
 * 约定：apiKey 以 "env:" 开头时，从对应环境变量读取真实 key。
 */
public final class ModelKeyResolver {

    public static String resolve(ModelConfig cfg) {
        String key = cfg.getApiKey();
        if (key != null && key.startsWith("env:")) {
            return System.getenv(key.substring(4));
        }
        return key;
    }

    private ModelKeyResolver() {
    }
}
