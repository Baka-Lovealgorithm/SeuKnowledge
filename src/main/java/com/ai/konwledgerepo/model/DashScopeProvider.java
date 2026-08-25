package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 阿里云 DashScope（通义千问）供应商实现。
 */
@Component
public class DashScopeProvider implements ModelProvider {

    @Override
    public String providerName() {
        return "DASHSCOPE";
    }

    @Override
    public ChatModel createChatModel(ModelConfig cfg) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(ModelKeyResolver.resolve(cfg))
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
                .build();
        DashScopeEmbeddingModel.Builder builder = DashScopeEmbeddingModel.builder().dashScopeApi(api);
        if (cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            builder.defaultOptions(DashScopeEmbeddingOptions.builder().model(cfg.getModelName()).build());
        }
        return builder.build();
    }

    @Override
    public boolean testConnection(ModelConfig cfg) {
        try {
            if (ModelType.EMBEDDING.is(cfg.getModelType())) {
                createEmbeddingModel(cfg).embed("ping");
            } else {
                createChatModel(cfg).call(new Prompt("ping"));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
