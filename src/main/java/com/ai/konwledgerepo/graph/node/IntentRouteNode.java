package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.Intent;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.JudgeOptions;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.service.chat.HistoryEntry;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图路由节点（多意图版）：将用户问题拆分为原子片段并逐片段定意图。
 * <p>业务片段聚合为 BUSINESS_QUESTION 供业务链路（改写/生成/自检）作为有效问题；
 * 闲聊片段写入 CHITCHAT_FRAGMENTS 供闲聊回复与合并节点使用；
 * 任一注入片段置 INJECTION=true（业务链路忽略指令 / 纯注入走固定拒答）。
 * <p>输出：有业务片段 → NEXT=QUERY_REWRITE；无业务片段（纯闲聊/纯注入）→ NEXT=CHAT_ONLY。
 * <p>路由输出为 JSON 约束 {@code {"fragments":[{"text":"…","intent":"…"}]}}（见 JudgeOptions.routerJson）；
 * 解析失败回退旧五标签 substring 解析（单片段）；仍无法解析 2 次后整句按 BUSINESS 失败反转（保持历史行为）。
 */
@Component
public class IntentRouteNode extends QaNodeSupport {

    private static final Logger log = LoggerFactory.getLogger(IntentRouteNode.class);
    private static final int ROUTER_MAX_ATTEMPTS = 2;
    private static final int ROUTER_RECENT_ROUNDS = 2;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final ModelFactory modelFactory;
    private final PromptCatalog promptCatalog;

    public IntentRouteNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog) {
        super(qaTracing);
        this.modelFactory = modelFactory;
        this.promptCatalog = promptCatalog;
    }

    @Override
    protected String spanName() {
        return "node/intent_route";
    }

    @Override
    protected Map<String, Object> applyInternal(OverAllState state, Span span) throws Exception {
        SseStreamContext.sendStage("INTENT_ROUTE", "意图分析");
        String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");
        String kbName = state.value(QaContextKey.KB_NAME).map(String::valueOf).orElse("本知识库");
        String agentPrompt = QaContext.agentPrompt(state);

        Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);
        ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.ROUTER.value(), workspaceId);
        ModelConfig cfg = modelFactory.resolveChatConfig(ModelUsage.ROUTER.value(), workspaceId);
        // JSON 约束：多意图片段结构输出，保证可解析（JudgeOptions.routerJson）
        ChatOptions opts = JudgeOptions.routerJson(chat, cfg);
        List<HistoryEntry> history = QaContext.history(state);
        String recentJson = QaContext.renderRecentJson(history, ROUTER_RECENT_ROUNDS);
        if (recentJson.isBlank() || "[]".equals(recentJson)) {
            recentJson = "（无）";
        }
        String prompt = promptCatalog.get("intent-route").formatted(kbName, agentPrompt, recentJson, question);

        List<RouteFragment> fragments = null;
        String response = null;
        for (int attempt = 1; attempt <= ROUTER_MAX_ATTEMPTS; attempt++) {
            response = LlmTrace.call(qaTracing, chat, prompt, opts);
            fragments = parseFragments(response, question);
            if (fragments != null) {
                break;
            }
            if (attempt < ROUTER_MAX_ATTEMPTS) {
                log.warn("意图路由输出无法解析（第 {} 次），重试: response={}", attempt,
                        response == null ? "<null>" : response.length() > 100 ? response.substring(0, 100) + "…" : response);
            }
        }
        if (fragments == null) {
            // 失败反转：整句按单 BUSINESS 片段（保持历史行为：默认走业务链路）
            fragments = List.of(new RouteFragment(question, Intent.BUSINESS, false));
            span.setAttribute("router_fallback", true);
            log.warn("意图路由 {} 次输出均无法解析，失败反转默认 BUSINESS: response={}",
                    ROUTER_MAX_ATTEMPTS,
                    response == null ? "<null>" : response.length() > 100 ? response.substring(0, 100) + "…" : response);
        }

        // 逐片段聚合：注入标记独立置位；业务片段聚合为有效问题；闲聊片段保留列表
        // 注：injection=true 仅表示"该片段含注入"，纯注入片段（JSON INJECTION / 旧标签 INJECTION）以
        // CHITCHAT+injection 承载且不入业务/闲聊；而旧标签 BUSINESS INJECTED 是业务+注入，需计入业务。
        List<String> businessParts = new ArrayList<>();
        List<String> chitchatParts = new ArrayList<>();
        boolean injection = false;
        for (RouteFragment f : fragments) {
            if (f.injection()) {
                injection = true;
            }
            if (f.intent() == Intent.BUSINESS) {
                businessParts.add(f.text());
            } else if (!f.injection()) {
                chitchatParts.add(f.text());
            }
        }
        String businessQuestion = String.join(" ", businessParts).trim();
        boolean hasBusiness = !businessQuestion.isEmpty();

        String next = hasBusiness ? QaState.QUERY_REWRITE.name() : QaState.CHAT_ONLY.name();
        String intent = hasBusiness ? Intent.BUSINESS.value() : Intent.CHITCHAT.value();
        span.setAttribute("intent", intent);
        span.setAttribute("injection", injection);
        span.setAttribute("fragment_count", fragments.size());
        span.setAttribute("business_fragments", businessParts.size());
        span.setAttribute("chitchat_fragments", chitchatParts.size());
        if (fragments.size() > 1 || injection) {
            log.info("意图路由拆分：{} 个片段（业务 {} / 闲聊 {} / 注入 {}）→ 意图={} next={}",
                    fragments.size(), businessParts.size(), chitchatParts.size(), injection ? 1 : 0, intent, next);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(QaContextKey.INTENT, intent);
        result.put(QaContextKey.INJECTION, injection);
        if (hasBusiness) {
            result.put(QaContextKey.BUSINESS_QUESTION, businessQuestion);
        }
        if (!chitchatParts.isEmpty()) {
            result.put(QaContextKey.CHITCHAT_FRAGMENTS, chitchatParts);
        }
        result.put(QaContextKey.NEXT, next);
        return result;
    }

    /** 拆分后的原子片段：文本 + 意图 + 是否含注入指令 */
    record RouteFragment(String text, Intent intent, boolean injection) {
    }

    /**
     * 解析路由输出为片段列表。优先 JSON 约束输出（fragments 数组）；
     * 失败回退旧五标签 substring 解析（整句单片段，片段文本用原始问题，保证业务内容正确）；
     * 均无法解析返回 null（触发重试/失败反转）。
     */
    static List<RouteFragment> parseFragments(String response, String fallbackQuestion) {
        if (response == null || response.isBlank()) {
            return null;
        }
        List<RouteFragment> fromJson = parseJsonFragments(response);
        if (fromJson != null) {
            return fromJson;
        }
        return parseLegacyFragments(response, fallbackQuestion);
    }

    /** JSON 模式解析：容忍前后缀噪声，提取 {@code {"fragments":[...]}}；无有效片段返回 null */
    private static List<RouteFragment> parseJsonFragments(String response) {
        try {
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(response);
            if (!matcher.find()) {
                return null;
            }
            JsonNode root = MAPPER.readTree(matcher.group());
            JsonNode arr = root.get("fragments");
            if (arr == null || !arr.isArray()) {
                return null;
            }
            List<RouteFragment> out = new ArrayList<>();
            for (JsonNode item : arr) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                JsonNode textNode = item.get("text");
                JsonNode intentNode = item.get("intent");
                if (textNode == null || !textNode.isTextual() || intentNode == null || !intentNode.isTextual()) {
                    continue;
                }
                String text = textNode.asText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                String up = intentNode.asText().trim().toUpperCase();
                if (up.contains("INJECT")) {
                    // 纯注入片段：以 CHITCHAT + injection=true 承载，由闲聊出口执行固定拒答
                    out.add(new RouteFragment(text, Intent.CHITCHAT, true));
                } else if (up.contains(Intent.BUSINESS.value())) {
                    out.add(new RouteFragment(text, Intent.BUSINESS, false));
                } else if (up.contains(Intent.CHITCHAT.value())) {
                    out.add(new RouteFragment(text, Intent.CHITCHAT, false));
                }
                // 未知意图跳过
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 旧五标签 substring 解析兜底：整句按单个片段（片段文本=原始问题，兼容历史行为与测试） */
    private static List<RouteFragment> parseLegacyFragments(String response, String fallbackQuestion) {
        RouteResult rr = parseRoute(response);
        if (rr == null) {
            return null;
        }
        return List.of(new RouteFragment(fallbackQuestion, rr.intent(), rr.injection()));
    }

    /** 路由解析结果：意图 + 是否包含注入指令 */
    record RouteResult(Intent intent, boolean injection) {
    }

    /**
     * 解析路由输出：BUSINESS / CHITCHAT / BUSINESS INJECTED / CHITCHAT INJECTED / INJECTION。
     * 纯注入（INJECTION，无业务内容）以 CHITCHAT + injection=true 返回，由闲聊出口执行固定拒答；
     * 无法解析返回 null（触发重试/失败反转）。
     */
    static RouteResult parseRoute(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String up = response.trim().toUpperCase();
        boolean hasBusiness = up.contains(Intent.BUSINESS.value());
        boolean hasChitchat = up.contains(Intent.CHITCHAT.value());
        boolean hasInjection = up.contains("INJECTED") || up.contains("INJECTION");
        if (hasBusiness) {
            return new RouteResult(Intent.BUSINESS, hasInjection);
        }
        if (hasChitchat) {
            return new RouteResult(Intent.CHITCHAT, hasInjection);
        }
        if (hasInjection) {
            return new RouteResult(Intent.CHITCHAT, true);
        }
        return null;
    }

    /** 兼容包装：仅取意图（旧调用方/测试使用），注入信息见 {@link #parseRoute} */
    static Intent parseIntent(String response) {
        RouteResult route = parseRoute(response);
        return route == null ? null : route.intent();
    }
}
