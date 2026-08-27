package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.tracing.LlmTrace;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 调用超时配置注入：启动时将 {@code seuknowledge.qa.llm-timeout-seconds}
 * 注入静态工具类 {@link LlmTrace}，使所有 LLM/Embedding 调用均受超时保护。
 */
@Configuration
public class LlmTimeoutConfig {

    public LlmTimeoutConfig(SeuQaProperties qaProps) {
        int seconds = Math.max(1, qaProps.llmTimeoutSeconds());
        LlmTrace.configure(Duration.ofSeconds(seconds));
    }
}