package com.ai.konwledgerepo.tracing;

import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次任务（trace）级 token 汇总累加器：按模型类别（文本 / 识图 / 向量）分组，
 * ThreadLocal 随执行线程，任务收尾时把结果写入根 span 并清理。
 * 供问答链路、文档解析、抽取任务共用（同一线程内多次 LLM/向量调用合并统计）。
 */
public final class TokenAccumulator {

    /** 模型类别常量：文本生成 / 识图（多模态）/ 向量 */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_VISION = "vision";
    public static final String TYPE_EMBEDDING = "embedding";

    /** 当前任务的 token 累加器：type -> [input, output, total]（ThreadLocal，随执行线程；ConcurrentHashMap 支持并行子线程并发累加同一任务 map） */
    private static final ThreadLocal<Map<String, long[]>> ACCUMULATOR = new ThreadLocal<>();

    private TokenAccumulator() {
    }

    /** 开始一次任务的 token 累加（问答/文档解析/抽取执行前调用） */
    public static void begin() {
        ACCUMULATOR.set(new ConcurrentHashMap<>());
    }

    /**
     * 快照当前累加器 map（共享引用，非副本），供并行子线程通过 {@link #restore(Map)} 恢复后
     * 并发累加写入同一任务 map（ConcurrentHashMap 保证线程安全）。
     * 返回 null 表示当前无累加器。
     */
    public static Map<String, long[]> snapshot() {
        return ACCUMULATOR.get();
    }

    /** 恢复累加器到当前线程（null 时移除），供并行子线程使用 */
    public static void restore(Map<String, long[]> map) {
        if (map == null) {
            ACCUMULATOR.remove();
        } else {
            ACCUMULATOR.set(map);
        }
    }

    /** 清理当前线程累加器（供并行子线程执行完毕后调用） */
    public static void clear() {
        ACCUMULATOR.remove();
    }

    /** 累加一次 LLM/向量调用的 token（按模型类别：text / vision / embedding） */
    public static void accumulate(String type, Usage usage) {
        Map<String, long[]> acc = ACCUMULATOR.get();
        if (acc == null || usage == null) {
            return;
        }
        String key = type == null || type.isBlank() ? "unknown" : type;
        long[] v = acc.computeIfAbsent(key, k -> new long[3]);
        v[0] += usage.getPromptTokens();
        v[1] += usage.getCompletionTokens();
        v[2] += usage.getTotalTokens();
    }

    /**
     * 读取当前任务累计的 token 总量（不清理累加器）。
     * 供任务执行器收尾落库使用：返回 [input, output, total]。
     */
    public static long[] totals() {
        Map<String, long[]> acc = ACCUMULATOR.get();
        long input = 0;
        long output = 0;
        long total = 0;
        if (acc != null) {
            for (long[] v : acc.values()) {
                input += v[0];
                output += v[1];
                total += v[2];
            }
        }
        return new long[]{input, output, total};
    }

    /**
     * 将累加结果写入根 span 并清理累加器（任务执行完毕后调用）。
     * 输出：按类别 qa.llm.tokens.{type}.{input,output,total} + 汇总 qa.llm.{input,output,total}_tokens 等。
     */
    public static void flushToSpan(Span root) {
        Map<String, long[]> acc = ACCUMULATOR.get();
        if (acc != null && root != null) {
            long input = 0;
            long output = 0;
            long total = 0;
            List<String> typesWithCost = new ArrayList<>();
            for (Map.Entry<String, long[]> e : acc.entrySet()) {
                long[] v = e.getValue();
                String key = safeKey(e.getKey());
                root.setAttribute("qa.llm.tokens." + key + ".input", v[0]);
                root.setAttribute("qa.llm.tokens." + key + ".output", v[1]);
                root.setAttribute("qa.llm.tokens." + key + ".total", v[2]);
                input += v[0];
                output += v[1];
                total += v[2];
                if (v[2] > 0) {
                    typesWithCost.add(e.getKey());
                }
            }
            root.setAttribute("qa.llm.input_tokens", input);
            root.setAttribute("qa.llm.output_tokens", output);
            root.setAttribute("qa.llm.total_tokens", total);
            root.setAttribute("qa.llm.types", String.join(",", typesWithCost));
        }
        ACCUMULATOR.remove();
    }

    /** OTel 属性键安全化（类型名中非常规字符替换为下划线） */
    private static String safeKey(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
