package com.ai.konwledgerepo.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置响应（apiKey 脱敏不回传）。
 */
public record ModelConfigResponse(Long id, String name, String provider, String modelType, String usage,
                                  String modelName, String baseUrl, BigDecimal temperature, Integer maxTokens,
                                  Boolean isDefault, Boolean enabled, LocalDateTime createdAt,
                                  Boolean disableThinking, String thinkingParams) {
}
