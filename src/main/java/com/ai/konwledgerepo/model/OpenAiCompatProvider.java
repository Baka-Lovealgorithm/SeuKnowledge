package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容供应商（DeepSeek / ollama / one-api 等），通过 baseUrl 指定兼容端点。
 */
@Component
public class OpenAiCompatProvider implements ModelProvider {

    @Override
    public String providerName() {
        return "OPENAI_COMPAT";
    }

    @Override
    public ChatModel createChatModel(ModelConfig cfg) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(cfg.getBaseUrl()))
                .apiKey(ModelKeyResolver.resolve(cfg))
                .build();
        OpenAiChatModel.Builder builder = OpenAiChatModel.builder().openAiApi(api);
        if (cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            builder.defaultOptions(OpenAiChatOptions.builder().model(cfg.getModelName()).build());
        }
        return builder.build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(ModelConfig cfg) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(cfg.getBaseUrl()))
                .apiKey(ModelKeyResolver.resolve(cfg))
                .build();
        if (cfg.getModelName() != null && !cfg.getModelName().isBlank()) {
            return new OpenAiEmbeddingModel(api, MetadataMode.NONE,
                    OpenAiEmbeddingOptions.builder().model(cfg.getModelName()).build());
        }
        return new OpenAiEmbeddingModel(api);
    }

    /**
     * OpenAI 兼容 baseUrl 归一化：Spring AI 会在 baseUrl 之后追加 /v1/chat/completions（或 /v1/embeddings），
     * 若用户填了带 /v1 的地址会拼成 /v1/v1/... 导致 404「接口不存在」。这里剥离末尾的 /v1（连同尾部斜杠），
     * 使「https://host」「https://host/v1」「https://host/v1/」三种写法都解析到同一正确端点。
     * 注意：仅 Chat/Embedding 适用；RERANK 协议是 baseUrl + /rerank，其 /v1 是必需的，不走此归一化。
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        return url;
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
