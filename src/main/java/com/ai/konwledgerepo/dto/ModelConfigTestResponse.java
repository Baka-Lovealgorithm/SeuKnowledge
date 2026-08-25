package com.ai.konwledgerepo.dto;

/**
 * 模型连通性测试结果。
 */
public record ModelConfigTestResponse(Long id, boolean success, String message) {
}
