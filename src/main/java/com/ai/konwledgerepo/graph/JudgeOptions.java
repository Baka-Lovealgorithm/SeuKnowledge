package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

/**
 * judge 类 LLM 调用的选项构造（自检节点、标题生成器等共用）：
 * 限制输出 token，并按模型配置决定是否关闭 thinking。
 * <p>实测 deepseek 系模型（经 TokenRythm 中转）带隐藏 reasoning token：completion 被思考占满时
 * 「max_tokens 触顶即返回空 content」→ 输出不可解析、门控静默失效；且自然输出可达 1.2 万 token。
 * 配置 disableThinking=true（deepseek 系）时关闭思考：completion 全部用于内容输出，
 * 输出回归紧凑、可解析、耗时大降；参数模板由 thinkingParams 按模型品牌定制。
 * OpenAI 兼容模型传 extraBody；其它 provider（如 DashScope）仅限 maxTokens（不冒险传不认识参数）。
 */
public final class JudgeOptions {

    private static final Logger log = LoggerFactory.getLogger(JudgeOptions.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JudgeOptions() {
    }

    /**
     * 构造 judge 调用选项：maxTokens 必带（防输出失控）；
     * 模型配置 disableThinking=true 时附加关思考参数（OpenAI 兼容模型经 extraBody 传 thinkingParams）。
     */
    public static ChatOptions of(ChatModel model, ModelConfig cfg, int maxTokens) {
        if (cfg == null || !Boolean.TRUE.equals(cfg.getDisableThinking())) {
            return ChatOptions.builder().maxTokens(maxTokens).build();
        }
        Map<String, Object> params = parseThinkingParams(cfg.getThinkingParams());
        if (model != null && model.getDefaultOptions() instanceof OpenAiChatOptions) {
            return OpenAiChatOptions.builder().maxTokens(maxTokens).extraBody(params).build();
        }
        return ChatOptions.builder().maxTokens(maxTokens).build();
    }

    /** 解析 thinkingParams JSON 模板；空/非法回退默认 deepseek 格式 {"thinking":{"type":"disabled"}} */
    private static Map<String, Object> parseThinkingParams(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("JudgeOptions thinkingParams 解析失败，回退默认关思考参数: {}", e.getMessage());
            }
        }
        return Map.of("thinking", Map.of("type", "disabled"));
    }
}
