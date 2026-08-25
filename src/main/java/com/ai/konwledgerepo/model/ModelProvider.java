package com.ai.konwledgerepo.model;

import com.ai.konwledgerepo.entity.ModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 模型供应商抽象：按 ModelConfig 动态创建 ChatModel / EmbeddingModel，并支持连通性测试。
 */
public interface ModelProvider {

    /** 供应商标识：DASHSCOPE / OPENAI_COMPAT */
    String providerName();

    ChatModel createChatModel(ModelConfig cfg);

    EmbeddingModel createEmbeddingModel(ModelConfig cfg);

    /** 连通性测试：发送最短请求验证 key/端点可用 */
    boolean testConnection(ModelConfig cfg);
}
