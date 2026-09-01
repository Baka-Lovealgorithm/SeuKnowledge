package com.ai.konwledgerepo.tracing;

import io.opentelemetry.api.trace.Span;
import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 一次任务（trace）级 token 汇总累加器：按模型类别（文本 / 识图 / 向量）分组，
 * ThreadLocal 随执行线程，任务收尾时把结果写入根 span 并清理。
 * 供问答链路、文档解析、抽取任务共用（同一线程内多次 LLM/向量调用合并统计）。
 * <p>
 * 并发安全：累加容器为 {@link AtomicLongArray}（下标 0=输入/1=输出/2=总计），
 * {@link #accumulate} 用 {@code addAndGet} 原子累加；该 map 经 {@link #snapshot()} 被并行子线程
 * （多查询并行 / verify 两阶段并行 / knn-bm25 并行 / 识图并行）共享同一实例，
 * {@link ConcurrentHashMap} 保证 {@code computeIfAbsent} 原子、{@link AtomicLongArray} 保证元素累加原子，
 * 不丢失更新。{@link #totals} / {@link #flushToSpan} 在子线程 join 后调用，读取用 {@code get}。
 */
public final class TokenAccumulator {

    /** 模型类别常量：文本生成 / 识图（多模态）/ 向量 */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_VISION = "vision";
    public static final String TYPE_EMBEDDING = "embedding";

    /** 累加器下标：输入 / 输出 / 总计 */
    private static final int IDX_INPUT = 0;
    private static final int IDX_OUTPUT = 1;
    private static final int IDX_TOTAL = 2;
    private static final int ARITY = 3;

    /** 当前任务的 token 累加器：type -> AtomicLongArray[input,output,total]（ThreadLocal，随执行线程；ConcurrentHashMap 支持并行子线程并发累加同一任务 map） */
    private static final ThreadLocal<Map<String, AtomicLongArray>> ACCUMULATOR = new ThreadLocal<>();

    private TokenAccumulator() {
    }

    /** 开始一次任务的 token 累加（问答/文档解析/抽取执行前调用） */
    public static void begin() {
        ACCUMULATOR.set(new ConcurrentHashMap<>());
    }

    /**
     * 快照当前累加器 map（共享引用，非副本），供并行子线程通过 {@link #restore(Map)} 恢复后
     * 并发累加写入同一任务 map（ConcurrentHashMap + AtomicLongArray 保证线程安全）。
     * 返回 null 表示当前无累加器。
     */
    public static Map<String, AtomicLongArray> snapshot() {
        return ACCUMULATOR.get();
    }

    /** 恢复累加器到当前线程（null 时移除），供并行子线程使用 */
    public static void restore(Map<String, AtomicLongArray> map) {
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

    /** 累加一次 LLM/向量调用的 token（按模型类别：text / vision / embedding）；原子，无丢更新 */
    public static void accumulate(String type, Usage usage) {
        Map<String, AtomicLongArray> acc = ACCUMULATOR.get();
        if (acc == null || usage == null) {
            return;
        }
        String key = type == null || type.isBlank() ? "unknown" : type;
        AtomicLongArray v = acc.computeIfAbsent(key, k -> new AtomicLongArray(ARITY));
        v.addAndGet(IDX_INPUT, usage.getPromptTokens());
        v.addAndGet(IDX_OUTPUT, usage.getCompletionTokens());
        v.addAndGet(IDX_TOTAL, usage.getTotalTokens());
    }

    /**
     * 读取当前任务累计的 token 总量（不清理累加器）。
     * 供任务执行器收尾落库使用：返回 [input, output, total]。
     */
    public static long[] totals() {
        Map<String, AtomicLongArray> acc = ACCUMULATOR.get();
        long input = 0;
        long output = 0;
        long total = 0;
        if (acc != null) {
            for (AtomicLongArray v : acc.values()) {
                input += v.get(IDX_INPUT);
                output += v.get(IDX_OUTPUT);
                total += v.get(IDX_TOTAL);
            }
        }
        return new long[]{input, output, total};
    }

    /**
     * 将累加结果写入根 span 并清理累加器（任务执行完毕后调用）。
     * 输出：按类别 qa.llm.tokens.{type}.{input,output,total} + 汇总 qa.llm.{input,output,total}_tokens 等。
     */
    public static void flushToSpan(Span root) {
        Map<String, AtomicLongArray> acc = ACCUMULATOR.get();
        if (acc != null && root != null) {
            long input = 0;
            long output = 0;
            long total = 0;
            List<String> typesWithCost = new ArrayList<>();
            for (Map.Entry<String, AtomicLongArray> e : acc.entrySet()) {
                AtomicLongArray v = e.getValue();
                String key = safeKey(e.getKey());
                root.setAttribute("qa.llm.tokens." + key + ".input", v.get(IDX_INPUT));
                root.setAttribute("qa.llm.tokens." + key + ".output", v.get(IDX_OUTPUT));
                root.setAttribute("qa.llm.tokens." + key + ".total", v.get(IDX_TOTAL));
                input += v.get(IDX_INPUT);
                output += v.get(IDX_OUTPUT);
                total += v.get(IDX_TOTAL);
                if (v.get(IDX_TOTAL) > 0) {
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
