package com.ai.konwledgerepo.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ai.konwledgerepo.common.GenerationCancelledException;

/**
 * 带链路追踪的 LLM / 向量 / 识图调用封装。
 * <p>
 * 每次调用创建一个 GENERATION span（挂在当前节点 span 下），统计输入/输出/总 token
 * （gen_ai.usage.* 属性），并按模型类别（text / vision / embedding）累加到任务级汇总
 * （见 {@link TokenAccumulator}）。
 * <p>
 * 同时以独立 logger（{@code com.ai.konwledgerepo.llm}，logback-spring.xml 中独立到
 * logs/llm.log，DEBUG 级）记录每次调用的 prompt / 原始输出 / 耗时 / token，
 * 供 AI 开发调试复现「模型为什么这么答」；注意 prompt 与输出含知识库内容，属敏感数据。
 * <p>
 * <b>内部结构</b>：文本类调用（{@code call} / {@code stream} / {@code vision}）共用
 * {@link #traced} 骨架（span 生命周期 + 异常分类 + I/O 记录），方法体只保留各自差异
 * （构造 Prompt、解析响应、取 usage）。向量调用（{@code embed} / {@code embedAll}）
 * 因 span 类型、I/O 语义与分片逻辑不同而独立实现，详见 {@link #traced} 的说明。
 * <p>
 * <b>静态状态说明</b>：本类为静态工具（非 Spring bean），仅持有两项静态状态——
 * {@link #llmTimeout}（启动时经 {@link #configure} 注入后不再变更）与
 * {@link #LLM_EXECUTOR}（进程级虚拟线程执行器）。调用方以参数传入
 * {@link QaTracing} / {@code ChatModel}，故无需实例化。
 */
public final class LlmTrace {

    /** LLM I/O 调试日志专用 logger（字符串名，供 logback 精确匹配独立文件） */
    private static final Logger LLM_LOG = LoggerFactory.getLogger("com.ai.konwledgerepo.llm");

    /** 单次 LLM/Embedding 调用超时（可由 {@link #configure(Duration)} 在启动时覆盖） */
    private static volatile Duration llmTimeout = Duration.ofSeconds(60);

    /**
     * generation span 上 prompt / completion 属性最大长度（字符）。
     * 超长截断并加省略号，防 OTLP 报文过大被 Langfuse 拒收；完整内容仍见 logs/llm.log（DEBUG 级）。
     */
    private static final int MAX_IO_ATTR_LEN = 16000;

    /** 批量向量化单请求上限（DashScope text-embedding-v3/v4 为 10 条，通用 OpenAI 兼容端点均可覆盖） */
    private static final int EMBED_BATCH_MAX = 10;

    /** 超时包装专用虚拟线程执行器（per-task，阻塞让出载体线程，无池化泄漏） */
    private static final ExecutorService LLM_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 运行时覆盖单次调用超时（由 {@link com.ai.konwledgerepo.config.LlmTimeoutConfig} 在启动时注入）。
     * 防御：null / ≤0 时忽略，保持默认 60s。
     */
    public static void configure(Duration timeout) {
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            llmTimeout = timeout;
        }
    }

    // ===== String 重载（保持兼容，委托给 List<Message> 版本） =====

    /** 同步文本调用：返回模型输出文本 */
    public static String call(QaTracing tracing, ChatModel chat, String prompt) {
        return call(tracing, chat, prompt, (ChatOptions) null);
    }

    /**
     * 同步文本调用（可选限制输出 token）：maxTokens 非空且 >0 时通过 Prompt 的 ChatOptions 传给模型，
     * 用于约束 judge 类调用（answer-verify / answer-faithfulness）的输出长度，避免模型输出失控拖长链路耗时。
     */
    public static String call(QaTracing tracing, ChatModel chat, String prompt, Integer maxTokens) {
        ChatOptions options = (maxTokens == null || maxTokens <= 0)
                ? null
                : ChatOptions.builder().maxTokens(maxTokens).build();
        return call(tracing, chat, prompt, options);
    }

    /**
     * 同步文本调用（完整选项控制）：options 非空时原样挂到 Prompt 上（可含 maxTokens、extraBody 等 provider 专属参数），
     * 供 judge 类调用按模型能力精细控制（如关闭 reasoning 以规避 max_tokens 被思考 token 占满）。
     */
    public static String call(QaTracing tracing, ChatModel chat, String prompt, ChatOptions options) {
        return call(tracing, chat, List.of(new UserMessage(prompt)), options);
    }

    /** 流式文本调用：逐 token 回调 onDelta，末尾统计 usage（取最后一条携带 usage 的响应）。 */
    public static String stream(QaTracing tracing, ChatModel chat, String prompt, Consumer<String> onDelta) {
        return stream(tracing, chat, List.of(new UserMessage(prompt)), onDelta, null);
    }

    /** 流式文本调用（可取消）：逐 token 回调 onDelta，cancelled 非空时每 token 检查取消标志。 */
    public static String stream(QaTracing tracing, ChatModel chat, String prompt, Consumer<String> onDelta,
                                BooleanSupplier cancelled) {
        return stream(tracing, chat, List.of(new UserMessage(prompt)), onDelta, cancelled);
    }

    // ===== List<Message> 重载（结构化传参：消息角色化） =====

    /** 同步调用（消息列表，无 options） */
    public static String call(QaTracing tracing, ChatModel chat, List<Message> messages) {
        return call(tracing, chat, messages, (ChatOptions) null);
    }

    /**
     * 同步调用（消息列表 + 完整 options）。
     * 消息角色化：SystemMessage 放指令，UserMessage 放数据，清晰分离注入面。
     */
    public static String call(QaTracing tracing, ChatModel chat, List<Message> messages, ChatOptions options) {
        return call(tracing, chat, messages, options, null);
    }

    /**
     * 同步调用（消息列表 + 完整 options + 可取消）。
     * cancelled 非空时，执行前检查取消标志，为 true 直接抛 {@link GenerationCancelledException}。
     */
    public static String call(QaTracing tracing, ChatModel chat, List<Message> messages, ChatOptions options,
                              BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new GenerationCancelledException("");
        }
        String prompt = render(messages);
        return traced(tracing, chat, "text", prompt, span -> {
            Prompt p = options == null ? new Prompt(messages) : new Prompt(messages, options);
            ChatResponse response = awaitWithTimeout("text", () -> {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new GenerationCancelledException("");
                }
                return chat.call(p);
            }, llmTimeout);
            Usage usage = usageOf(response);
            QaTracing.setUsage(span, usage);
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage);
            return new LlmOutcome(textOf(response), usage);
        });
    }

    /** 流式调用（消息列表）：逐 token 回调 onDelta */
    public static String stream(QaTracing tracing, ChatModel chat, List<Message> messages, Consumer<String> onDelta) {
        return stream(tracing, chat, messages, onDelta, null);
    }

    /** 流式调用（消息列表，可取消）：逐 token 回调 onDelta；cancelled 非空时每 token 检查。 */
    public static String stream(QaTracing tracing, ChatModel chat, List<Message> messages, Consumer<String> onDelta,
                                BooleanSupplier cancelled) {
        String prompt = render(messages);
        return traced(tracing, chat, "stream", prompt, span -> {
            Usage[] lastUsage = new Usage[1];
            StringBuilder sb = new StringBuilder();
            String text = awaitWithTimeout("stream", () -> {
                chat.stream(new Prompt(messages)).doOnNext(response -> {
                    Usage usage = usageOf(response);
                    if (usage != null) {
                        lastUsage[0] = usage;
                    }
                    String t = textOf(response);
                    if (t != null && !t.isEmpty()) {
                        sb.append(t);
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            throw new GenerationCancelledException(sb.toString());
                        }
                        if (onDelta != null) {
                            onDelta.accept(t);
                        }
                    }
                }).blockLast();
                return sb.toString();
            }, llmTimeout);
            QaTracing.setUsage(span, lastUsage[0]);
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, lastUsage[0]);
            return new LlmOutcome(text, lastUsage[0]);
        });
    }

    /** 识图（多模态）调用：图片 + 提示词 → 文本描述，统计为 vision 类 token（media 二进制不记录） */
    public static String vision(QaTracing tracing, ChatModel chat, String prompt, Media media) {
        return traced(tracing, chat, "vision", prompt, span -> {
            UserMessage message = UserMessage.builder().text(prompt).media(media).build();
            ChatResponse response = awaitWithTimeout("vision", () -> chat.call(new Prompt(message)), llmTimeout);
            Usage usage = usageOf(response);
            QaTracing.setUsage(span, usage);
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_VISION, usage);
            return new LlmOutcome(textOf(response), usage);
        });
    }

    /** 向量（Embedding）调用：返回 query 向量，统计为 embedding 类 token（输入截断 200 字符） */
    public static float[] embed(QaTracing tracing, EmbeddingModel model, String text) {
        Span span = tracing.beginEmbedding(model);
        long start = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            EmbeddingResponse response = awaitWithTimeout("embedding",
                    () -> model.embedForResponse(List.of(text)), llmTimeout);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            QaTracing.setUsage(span, usage);
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_EMBEDDING, usage);
            float[] vector = response.getResult().getOutput();
            // 向量输出不写 span（无可视化意义且巨大）；输入文本写入供排查「向量化的是什么」
            writeIoAttrs(span, text, null);
            if (LLM_LOG.isDebugEnabled()) {
                long in = usage == null ? -1 : usage.getPromptTokens();
                long total = usage == null ? -1 : usage.getTotalTokens();
                LLM_LOG.debug("LLM embedding call: model={} dim={} cost={}ms usage(in/total)={}/{}",
                        QaTracing.modelNameOf(model), vector == null ? 0 : vector.length,
                        System.currentTimeMillis() - start, in, total);
                LLM_LOG.debug("  input >>> {}", truncate(text, 200));
            }
            return vector;
        } catch (Exception e) {
            if (e instanceof LlmTimeoutException) {
                span.setAttribute("llm.timeout", true);
            }
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 批量向量化（Embedding）调用：请求一次携带多条文本（供应商批量接口，token 成本不变、
     * 网络往返降为 1 次），返回与输入顺序对齐的向量列表。与 {@link #embed} 同款
     * tracing/usage/超时语义；超过 {@link #EMBED_BATCH_MAX} 条自动分片多次调用。
     */
    public static List<float[]> embedAll(QaTracing tracing, EmbeddingModel model, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Span span = tracing.beginEmbedding(model);
        long start = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            List<float[]> vectors = new ArrayList<>(texts.size());
            long promptTokens = 0;
            long totalTokens = 0;
            for (int from = 0; from < texts.size(); from += EMBED_BATCH_MAX) {
                List<String> slice = texts.subList(from, Math.min(texts.size(), from + EMBED_BATCH_MAX));
                EmbeddingResponse response = awaitWithTimeout("embedding",
                        () -> model.embedForResponse(slice), llmTimeout);
                Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
                QaTracing.setUsage(span, usage);
                TokenAccumulator.accumulate(TokenAccumulator.TYPE_EMBEDDING, usage);
                if (usage != null) {
                    promptTokens += usage.getPromptTokens();
                    totalTokens += usage.getTotalTokens();
                }
                List<Embedding> results = response.getResults();
                if (results != null) {
                    for (Embedding embedding : results) {
                        vectors.add(embedding.getOutput());
                    }
                }
            }
            String joined = String.join(" | ", texts);
            writeIoAttrs(span, truncate(joined, 200), null);
            if (LLM_LOG.isDebugEnabled()) {
                int dim = vectors.isEmpty() || vectors.get(0) == null ? 0 : vectors.get(0).length;
                LLM_LOG.debug("LLM embedding batch call: model={} count={} dim={} cost={}ms usage(in/total)={}/{}",
                        QaTracing.modelNameOf(model), texts.size(), dim,
                        System.currentTimeMillis() - start, promptTokens, totalTokens);
                LLM_LOG.debug("  inputs >>> {}", truncate(joined, 200));
            }
            return vectors;
        } catch (Exception e) {
            if (e instanceof LlmTimeoutException) {
                span.setAttribute("llm.timeout", true);
            }
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 记录一次 LLM 调用的输入输出（专用 llm logger，供 LlmTrace 各方法与
     * 未走 LlmTrace 的调用方（如并行识图路径）共用，保证格式一致）。
     */
    public static void logDirect(String category, String model, String prompt, String output, long costMs, Usage usage) {
        if (!LLM_LOG.isDebugEnabled()) {
            return;
        }
        long in = usage == null ? -1 : usage.getPromptTokens();
        long out = usage == null ? -1 : usage.getCompletionTokens();
        long total = usage == null ? -1 : usage.getTotalTokens();
        LLM_LOG.debug("LLM {} call: model={} cost={}ms usage(in/out/total)={}/{}/{}",
                category, model, costMs, in, out, total);
        LLM_LOG.debug("  prompt >>> {}", prompt);
        LLM_LOG.debug("  output <<< {}", output);
    }

    /** 记录一次 LLM 调用失败（专用 llm logger） */
    public static void logDirectError(String category, String model, String prompt, Exception e, long costMs) {
        if (!LLM_LOG.isDebugEnabled()) {
            return;
        }
        LLM_LOG.debug("LLM {} call FAILED: model={} cost={}ms err={}",
                category, model, costMs, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        LLM_LOG.debug("  prompt >>> {}", prompt);
    }

    /**
     * 单次 LLM 调用的统一骨架：span 生命周期 + 异常分类 + I/O 记录。
     * <p>
     * 净收益是把此前在 {@code call} / {@code stream} / {@code vision} 各写一遍的
     * 「建 span → makeCurrent → 执行 → 写 prompt/completion → 记日志 → 分类异常 → end span」
     * 收敛到一处。三点必须保持的行为契约（已有 {@code LlmTraceBehaviorTest} 覆盖）：
     * <ul>
     *   <li>异常分类：{@link LlmTimeoutException} 落 {@code llm.timeout}、
     *       {@link GenerationCancelledException} 落 {@code llm.cancelled}（Langfuse 据此区分失败原因）；</li>
     *   <li>原始异常不被包装——调用方按具体类型 catch（如节点捕获取消以落库部分答案）；</li>
     *   <li>{@code span.end()} 在正常与异常路径都必须执行，否则 trace 泄漏。</li>
     * </ul>
     * 成功时由 {@code invoke} 返回 {@link LlmOutcome}（文本 + usage），span 写入与日志在此统一完成，
     * 调用方因此不必各自记得「写属性 + 打日志」这两步。
     * <p>
     * 注意：<b>向量调用（{@link #embed} / {@link #embedAll}）刻意不并入本骨架</b>——它们用
     * {@code beginEmbedding} 建 span、只写输入不写输出（向量无文本），且 {@code embedAll} 还要分片，
     * 强行合并反而要把差异塞进回调，降低可读性（违反重构目标 6）。
     *
     * @param category 日志与 span 语义上的调用类别（text / stream / vision）
     * @param prompt   已渲染的 prompt 文本，用于 span 属性与 I/O 日志
     * @param invoke   实际调用逻辑；返回文本与 usage，允许抛异常
     */
    private static String traced(QaTracing tracing, ChatModel chat, String category, String prompt,
                                 Function<Span, LlmOutcome> invoke) {
        Span span = tracing.beginGeneration(chat);
        long start = System.currentTimeMillis();
        try (Scope scope = span.makeCurrent()) {
            LlmOutcome outcome = invoke.apply(span);
            String text = outcome.text() == null ? "" : outcome.text();
            writeIoAttrs(span, prompt, text);
            logDirect(category, QaTracing.modelName(chat), prompt, text,
                    System.currentTimeMillis() - start, outcome.usage());
            return text;
        } catch (Exception e) {
            classifyFailure(span, e);
            logDirectError(category, QaTracing.modelName(chat), prompt, e, System.currentTimeMillis() - start);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 把失败原因标注到 span（Langfuse 据此区分「超时」「用户取消」「供应商报错」）。
     * 独立成方法是为了让 {@link #traced} 主流程保持线性可读。
     */
    private static void classifyFailure(Span span, Exception e) {
        if (e instanceof LlmTimeoutException) {
            span.setAttribute("llm.timeout", true);
        }
        if (e instanceof GenerationCancelledException) {
            span.setAttribute("llm.cancelled", true);
        }
        span.recordException(e);
    }

    /** 一次成功调用的产出：模型文本 + token 用量（供 {@link #traced} 统一记录） */
    private record LlmOutcome(String text, Usage usage) {
    }

    /** 取响应文本（响应/结果/输出任一为空都归一为空串，避免调用方各自判空） */
    private static String textOf(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
    }

    private static String render(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    if (m instanceof SystemMessage sm) {
                        return "SYSTEM: " + sm.getText();
                    } else if (m instanceof UserMessage um) {
                        return "USER: " + um.getText();
                    }
                    return "?: " + m;
                })
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * 把 LLM 调用输入（prompt）与输出（completion）写入 generation span，
     * 供 Langfuse 展示「这次调用了什么、模型回了什么」（OTel GenAI 语义属性）。
     * 两者均可为空（如向量调用无输出）；超长截断（{@link #MAX_IO_ATTR_LEN}）。
     */
    private static void writeIoAttrs(Span span, String prompt, String completion) {
        if (span == null) {
            return;
        }
        if (prompt != null) {
            span.setAttribute("gen_ai.prompt", truncate(prompt, MAX_IO_ATTR_LEN));
        }
        if (completion != null) {
            span.setAttribute("gen_ai.completion", truncate(completion, MAX_IO_ATTR_LEN));
        }
    }

    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    /**
     * 使用默认超时执行一次调用（供未走完整 LlmTrace 链路的调用方复用超时保护，
     * 如模型连通性测试 testConnection，避免裸调 chat.call() 无上限挂死）。
     * 超时抛 {@link LlmTimeoutException}。
     */
    public static <T> T withTimeout(String category, Callable<T> task) {
        return awaitWithTimeout(category, task, llmTimeout);
    }

    private LlmTrace() {
    }

    /**
     * 带超时的同步调用包装：将网络调用提交到虚拟线程执行，调用线程阻塞等待，
     * 超时后 best-effort 中断并抛 {@link LlmTimeoutException}。
     */
    private static <T> T awaitWithTimeout(String category, Callable<T> task, Duration timeout) {
        Future<T> future = LLM_EXECUTOR.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmTimeoutException(category, timeout.toSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new LlmTimeoutException(category, timeout.toSeconds());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }
}
