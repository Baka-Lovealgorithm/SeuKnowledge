package com.ai.konwledgerepo.tracing;

/**
 * LLM / Embedding 调用超时异常，由 {@link LlmTrace} 在
 * {@link LlmTrace#configure(java.time.Duration) 配置的超时}到达时抛出。
 * 调用方现有 {@code catch (Exception)} 兜底路径自动兼容。
 */
public class LlmTimeoutException extends RuntimeException {

    public LlmTimeoutException(String category, long timeoutSeconds) {
        super(category + " 调用超时（" + timeoutSeconds + "s）");
    }
}