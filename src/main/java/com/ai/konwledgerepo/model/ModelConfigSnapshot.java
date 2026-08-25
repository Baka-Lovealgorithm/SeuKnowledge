package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;

import java.math.BigDecimal;

/**
 * 模型配置快照（可序列化，用于 Redis 缓存，避免缓存 JPA 实体）。
 * apiKey 保持原样（支持 env: 引用形式，不缓存真实 key 明文）。
 */
public record ModelConfigSnapshot(Long id, String name, String provider, String modelType, String usage,
                                  String modelName, String apiKey, String baseUrl,
                                  BigDecimal temperature, Integer maxTokens,
                                  Boolean isDefault, Boolean enabled,
                                  Boolean disableThinking, String thinkingParams) {

    public static ModelConfigSnapshot from(ModelConfig cfg) {
        return new ModelConfigSnapshot(cfg.getId(), cfg.getName(), cfg.getProvider(), cfg.getModelType(),
                cfg.getUsage(), cfg.getModelName(), cfg.getApiKey(), cfg.getBaseUrl(),
                cfg.getTemperature(), cfg.getMaxTokens(), cfg.getIsDefault(), cfg.getEnabled(),
                cfg.getDisableThinking(), cfg.getThinkingParams());
    }

    /** 还原为实体（仅携带快照字段，供模型工厂创建实例用） */
    public ModelConfig toEntity() {
        ModelConfig cfg = new ModelConfig();
        cfg.setId(id);
        cfg.setName(name);
        cfg.setProvider(provider);
        cfg.setModelType(modelType);
        cfg.setUsage(usage);
        cfg.setModelName(modelName);
        cfg.setApiKey(apiKey);
        cfg.setBaseUrl(baseUrl);
        cfg.setTemperature(temperature);
        cfg.setMaxTokens(maxTokens);
        cfg.setIsDefault(isDefault);
        cfg.setEnabled(enabled);
        cfg.setDisableThinking(disableThinking);
        cfg.setThinkingParams(thinkingParams);
        return cfg;
    }
}
