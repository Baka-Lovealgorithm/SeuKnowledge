package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DashScope（通义千问）供应商实现。
 */
@Component
public class DashScopeProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeProvider.class);

    @Override
    public String providerName() {
        return "DASHSCOPE";
    }

    @Override
    public ChatModel createChatModel(ModelConfig cfg) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(ModelKeyResolver.resolve(cfg))
                // connect/read 超时兜底挂死连接（此前 read 无限，逻辑超时 cancel 打不断阻塞 I/O）
                .restClientBuilder(LlmHttpClients.restClientBuilder())
                .build();
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder().dashScopeApi(api);
        if (cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            builder.defaultOptions(DashScopeChatOptions.builder().model(cfg.getModelName()).build());
        }
        return builder.build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelConfig cfg) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(ModelKeyResolver.resolve(cfg))
                .restClientBuilder(LlmHttpClients.restClientBuilder())
                .build();
        DashScopeEmbeddingModel.Builder builder = DashScopeEmbeddingModel.builder().dashScopeApi(api);
        if (cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            builder.defaultOptions(DashScopeEmbeddingOptions.builder().model(cfg.getModelName()).build());
        }
        return builder.build();
    }

    @Override
    public boolean testConnection(ModelConfig cfg) {
        long start = System.currentTimeMillis();
        try {
            if (ModelType.EMBEDDING.is(cfg.getModelType())) {
                LlmTrace.withTimeout("test-embedding", () -> {
                    createEmbeddingModel(cfg).embed("ping");
                    return null;
                });
            } else {
                LlmTrace.withTimeout("test-chat", () -> {
                    createChatModel(cfg).call(new Prompt("ping"));
                    return null;
                });
            }
            log.info("模型连通性测试成功 provider={} type={} model={} 耗时={}ms",
                    cfg.getProvider(), cfg.getModelType(), cfg.getModelName(), System.currentTimeMillis() - start);
            return true;
        } catch (Exception e) {
            log.warn("模型连通性测试失败 provider={} type={} model={} 耗时={}ms 原因={}",
                    cfg.getProvider(), cfg.getModelType(), cfg.getModelName(), System.currentTimeMillis() - start,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }
}
