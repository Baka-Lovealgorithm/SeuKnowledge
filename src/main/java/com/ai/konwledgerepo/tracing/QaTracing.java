package com.ai.konwledgerepo.tracing;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 问答链路 OpenTelemetry 追踪（Langfuse 可视化）：span 的创建与语义属性。
 * <p>
 * 每次问答创建根 span（chat/ask），各状态图节点创建子 span（node/xxx），
 * 记录节点耗时与关键属性（意图、召回统计、自检得分等），通过 OTLP HTTP 上报 Langfuse。
 * OTel SDK 与 Tracer 的构建见 {@link com.ai.konwledgerepo.config.OtelConfig}（未配置时自动降级 no-op）；
 * 任务级 token 汇总见 {@link TokenAccumulator}。
 */
public class QaTracing {

    private final Tracer tracer;
    private final boolean enabled;

    public QaTracing(Tracer tracer, boolean enabled) {
        this.tracer = tracer;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 追踪关闭的实例（测试用）：OTel no-op，无任何网络行为 */
    public static QaTracing disabled() {
        return new QaTracing(OpenTelemetry.noop().getTracer("seuknowledge-qa"), false);
    }

    /** 开始一个 span（根 span 或节点子 span），需配合 makeCurrent + end 使用 */
    public Span begin(String name) {
        return tracer.spanBuilder(name).startSpan();
    }

    /**
     * 开始一次 LLM 调用的 generation span（Langfuse 按 GENERATION 类型与 gen_ai.* 语义识别）。
     * 作为当前节点 span 的子 span，记录模型名与 token 用量。
     */
    public Span beginGeneration(ChatModel chat) {
        String model = modelName(chat);
        String spanName = "llm/" + (model == null || model.isBlank() ? "chat" : model);
        Span span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute("gen_ai.provider.name", providerName(chat));
        span.setAttribute("gen_ai.request.model", model == null ? "" : model);
        span.setAttribute("langfuse.span.type", "GENERATION");
        return span;
    }

    /** 将 token 用量写入 generation span（OpenTelemetry GenAI 语义约定） */
    public static void setUsage(Span span, Usage usage) {
        if (span == null || usage == null) {
            return;
        }
        span.setAttribute("gen_ai.usage.input_tokens", usage.getPromptTokens());
        span.setAttribute("gen_ai.usage.output_tokens", usage.getCompletionTokens());
        span.setAttribute("gen_ai.usage.total_tokens", usage.getTotalTokens());
    }

    /**
     * 开始一次向量（Embedding）调用的 span，作为当前节点 span 的子 span。
     * 复用 GENERATION 类型与 gen_ai.* 语义，便于区分文本生成/向量模型。
     */
    public Span beginEmbedding(EmbeddingModel model) {
        String m = modelNameOf(model);
        Span span = tracer.spanBuilder("llm/" + (m == null || m.isBlank() ? "embedding" : m)).startSpan();
        span.setAttribute("gen_ai.provider.name", providerNameOf(model));
        span.setAttribute("gen_ai.request.model", m == null ? "" : m);
        span.setAttribute("langfuse.span.type", "GENERATION");
        return span;
    }

    /** 模型名（文本模型），供 span 展示 */
    public static String modelName(ChatModel chat) {
        try {
            ChatOptions options = chat.getDefaultOptions();
            return options == null ? null : options.getModel();
        } catch (Exception e) {
            return null;
        }
    }

    /** 模型名（向量模型），供 span 展示（反射兼容各实现的 options 访问方法，取不到返回 null） */
    public static String modelNameOf(EmbeddingModel model) {
        Object options = embeddingOptions(model);
        if (options == null) {
            return null;
        }
        try {
            Object name = options.getClass().getMethod("getModel").invoke(options);
            return name == null ? null : name.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String providerName(ChatModel chat) {
        try {
            ChatOptions options = chat.getDefaultOptions();
            if (options instanceof DashScopeChatOptions) {
                return "dashscope";
            }
            if (options instanceof OpenAiChatOptions) {
                return "openai-compat";
            }
            return "custom";
        } catch (Exception e) {
            return "custom";
        }
    }

    private static String providerNameOf(EmbeddingModel model) {
        Object options = embeddingOptions(model);
        if (options instanceof com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions) {
            return "dashscope";
        }
        if (options instanceof org.springframework.ai.openai.OpenAiEmbeddingOptions) {
            return "openai-compat";
        }
        return "custom";
    }

    /** 兼容取 embedding 实现类的 options 对象（多种访问方法名，取到即返回） */
    private static Object embeddingOptions(EmbeddingModel model) {
        if (model == null) {
            return null;
        }
        for (String methodName : java.util.List.of(
                "getOptions", "defaultOptions", "getDefaultOptions", "getEmbeddingOptions")) {
            try {
                return model.getClass().getMethod(methodName).invoke(model);
            } catch (Exception ignored) {
                // 尝试下一个方法名
            }
        }
        return null;
    }
}
