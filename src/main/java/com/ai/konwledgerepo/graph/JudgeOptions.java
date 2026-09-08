package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.alibaba.cloud.ai.dashscope.api.DashScopeResponseFormat;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

import java.util.Map;

/**
 * judge 类 LLM 调用的选项构造（自检节点、标题生成器、意图路由等共用）：
 * 限制输出 token，按模型配置决定是否关闭 thinking，可选 API 级 JSON 输出约束。
 * <p>实测 deepseek 系模型（经 TokenRythm 中转）带隐藏 reasoning token：completion 被思考占满时
 * 「max_tokens 触顶即返回空 content」→ 输出不可解析、门控静默失效；且自然输出可达 1.2 万 token。
 * 配置 disableThinking=true（deepseek 系）时关闭思考：completion 全部用于内容输出，
 * 输出回归紧凑、可解析、耗时大降；参数模板由 thinkingParams 按模型品牌定制。
 * OpenAI 兼容模型传 extraBody；其它 provider（如 DashScope）仅限 maxTokens（不冒险传不认识参数）。
 * <p>jsonMode 开启时叠加 API 级 response_format JSON_OBJECT（DashScope / OpenAI 兼容双路）；
 * 无论 jsonMode 开关，温度统一 0 保证打分/判断确定性（judge 类调用通行做法），默认关闭仅指不启用
 * response_format，保持输出自由文本。
 */
public final class JudgeOptions {

    private static final Logger log = LoggerFactory.getLogger(JudgeOptions.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 意图路由输出上限：单标签响应，32 token 足够且防 reasoning 占满 */
    private static final int ROUTER_MAX_TOKENS = 32;
    /** 意图路由 JSON 输出下限（起始档）：短问题维持原 256 口径 */
    public static final int ROUTER_JSON_BASE_TOKENS = 256;
    /** 意图路由 JSON 输出硬顶：指数阶梯 {256,512,1024} 的上限（超出按截顶依赖节点兜底反转） */
    public static final int ROUTER_JSON_CEIL_TOKENS = 1024;

    private JudgeOptions() {
    }

    // ===== 原有接口（保持兼容，jsonMode=false）=====

    /**
     * 构造 judge 调用选项：maxTokens 必带（防输出失控）；
     * 模型配置 disableThinking=true 时附加关思考参数（OpenAI 兼容模型经 extraBody 传 thinkingParams）。
     */
    public static ChatOptions of(ChatModel model, ModelConfig cfg, int maxTokens) {
        return of(model, cfg, maxTokens, false);
    }

    // ===== JSON 模式扩展（jsonMode=true 时叠加 response_format JSON_OBJECT + 温度 0）=====

    /**
     * 构造 judge 调用选项（可选 JSON 模式）。
     *
     * @param jsonMode true 时叠加 response_format JSON_OBJECT + 温度 0，仅 DashScope/OpenAI 兼容 provider 生效
     */
    public static ChatOptions of(ChatModel model, ModelConfig cfg, int maxTokens, boolean jsonMode) {
        boolean disableThinking = cfg != null && Boolean.TRUE.equals(cfg.getDisableThinking());
        if (jsonMode && model != null) {
            if (model.getDefaultOptions() instanceof DashScopeChatOptions) {
                DashScopeChatOptions.DashScopeChatOptionsBuilder b = DashScopeChatOptions.builder();
                b.maxToken(maxTokens);
                b.temperature(0.0);
                b.responseFormat(DashScopeResponseFormat.builder()
                        .type(DashScopeResponseFormat.Type.JSON_OBJECT).build());
                if (disableThinking) {
                    b.enableThinking(false);
                }
                return b.build();
            }
            if (model.getDefaultOptions() instanceof OpenAiChatOptions) {
                OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                        .maxTokens(maxTokens).temperature(0.0)
                        .responseFormat(ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT).build());
                if (disableThinking) {
                    b.extraBody(parseThinkingParams(cfg.getThinkingParams()));
                }
                return b.build();
            }
            // 未知 provider：jsonMode 不生效，回落 maxTokens + 温度 0
            return ChatOptions.builder().maxTokens(maxTokens).temperature(0.0).build();
        }
        // jsonMode=false：judge 家族（自检打分/标题/摘要）统一温度 0 去随机性（打分稳定性优先于历史默认温度）；
        // maxTokens 必带防输出失控；关思考参数按 provider 对称处理（OpenAI 兼容走 extraBody，DashScope 走 enableThinking）
        if (!disableThinking) {
            return ChatOptions.builder().maxTokens(maxTokens).temperature(0.0).build();
        }
        Map<String, Object> params = parseThinkingParams(cfg.getThinkingParams());
        if (model != null && model.getDefaultOptions() instanceof OpenAiChatOptions) {
            return OpenAiChatOptions.builder().maxTokens(maxTokens).temperature(0.0).extraBody(params).build();
        }
        if (model != null && model.getDefaultOptions() instanceof DashScopeChatOptions) {
            // 补齐 DashScope 关思考：与 routerJson/jsonMode 分支对称（原非 jsonMode 分支漏掉 enableThinking(false)）
            return DashScopeChatOptions.builder().maxToken(maxTokens).temperature(0.0).enableThinking(false).build();
        }
        return ChatOptions.builder().maxTokens(maxTokens).temperature(0.0).build();
    }

    // ===== 意图路由输出 token 动态估算 =====

    /**
     * 粗略估算一段文本的 token 数（无分词器依赖，确定性）：
     * CJK 字符（汉字 + 全角标点）按 1 token/字，其余非空白字符按 ⌈n/4⌉。
     * 用于路由 JSON 输出动态上限——片段文本需回显问题，输出随问题 token 线性增长。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isCjk = (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一汉字基本区
                    || (c >= 0x3400 && c <= 0x4DBF)           // 扩展 A
                    || (c >= 0x3000 && c <= 0x303F)           // CJK 标点
                    || (c >= 0xFF00 && c <= 0xFFEF);          // 全角标点/字符
            if (isCjk) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    /**
     * 按问题长度选路由 JSON 输出 token 上限：估算问题 token → 需求（×1.35 回显冗余 + 40 信封）
     * → 取 ≥ 需求的最小 2 的幂档（指数阶梯 {256,512,1024}），封顶 {@link #ROUTER_JSON_CEIL_TOKENS}。
     */
    public static int routerJsonCap(String question) {
        int est = estimateTokens(question);
        int need = (int) Math.ceil(est * 1.35) + 40;
        int cap = ROUTER_JSON_BASE_TOKENS;
        while (cap < need && cap < ROUTER_JSON_CEIL_TOKENS) {
            cap <<= 1;
        }
        return Math.min(cap, ROUTER_JSON_CEIL_TOKENS);
    }

    /** 解析失败重试时指数升档：cap×2，封顶 {@link #ROUTER_JSON_CEIL_TOKENS} */
    public static int escalate(int cap) {
        return Math.min(cap << 1, ROUTER_JSON_CEIL_TOKENS);
    }

    // ===== 意图路由专用 =====

    /**
     * 意图路由调用选项：温度 0（确定性）+ maxTokens 32（防 reasoning 占满），
     * 不启用 responseFormat（路由输出是单标签，不需 JSON）。
     */
    public static ChatOptions router(ChatModel model, ModelConfig cfg) {
        boolean disableThinking = cfg != null && Boolean.TRUE.equals(cfg.getDisableThinking());
        if (model != null && model.getDefaultOptions() instanceof OpenAiChatOptions) {
            OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                    .temperature(0.0).maxTokens(ROUTER_MAX_TOKENS);
            if (disableThinking) {
                b.extraBody(parseThinkingParams(cfg.getThinkingParams()));
            }
            return b.build();
        }
        if (model != null && model.getDefaultOptions() instanceof DashScopeChatOptions) {
            DashScopeChatOptions.DashScopeChatOptionsBuilder b = DashScopeChatOptions.builder();
            b.temperature(0.0);
            b.maxToken(ROUTER_MAX_TOKENS);
            if (disableThinking) {
                b.enableThinking(false);
            }
            return b.build();
        }
        return ChatOptions.builder().temperature(0.0).maxTokens(ROUTER_MAX_TOKENS).build();
    }

    /**
     * 意图路由 JSON 模式调用选项：温度 0（确定性）+ response_format JSON_OBJECT（DashScope/OpenAI 兼容双路）
     * + maxTokens 动态传入（片段列表 JSON 需回显问题，由 {@link #routerJsonCap(String)} 估算、
     * 解析失败重试时由 {@link #escalate(int)} 指数升档，阶梯 {256,512,1024}）。
     * 仅 DashScope/OpenAI 兼容 provider 生效，未知 provider 回落温度 0 + maxTokens。
     */
    public static ChatOptions routerJson(ChatModel model, ModelConfig cfg, int maxTokens) {
        boolean disableThinking = cfg != null && Boolean.TRUE.equals(cfg.getDisableThinking());
        if (model != null && model.getDefaultOptions() instanceof OpenAiChatOptions) {
            OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .maxTokens(maxTokens)
                    .responseFormat(ResponseFormat.builder()
                            .type(ResponseFormat.Type.JSON_OBJECT).build());
            if (disableThinking) {
                b.extraBody(parseThinkingParams(cfg.getThinkingParams()));
            }
            return b.build();
        }
        if (model != null && model.getDefaultOptions() instanceof DashScopeChatOptions) {
            DashScopeChatOptions.DashScopeChatOptionsBuilder b = DashScopeChatOptions.builder();
            b.temperature(0.0);
            b.maxToken(maxTokens);
            b.responseFormat(DashScopeResponseFormat.builder()
                    .type(DashScopeResponseFormat.Type.JSON_OBJECT).build());
            if (disableThinking) {
                b.enableThinking(false);
            }
            return b.build();
        }
        return ChatOptions.builder().temperature(0.0).maxTokens(maxTokens).build();
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