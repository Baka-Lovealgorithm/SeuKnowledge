package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.service.extract.ExtractJsonParser;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 答案自检节点测试（两阶段）：
 * 阶段一：解析严格 JSON 对象 {"score":…,"missing":…}；「无」/空串规范化为空；空输出回退 0 分；
 * 旧「分数 + 缺失：…」两行文本格式仍可回退解析（容错）。
 * 阶段二（事实一致性）：SUPPORTED 占比计 faithfulness，VERIFY_SCORE=min(阶段一, faithfulness)；
 * 无支撑/矛盾断言并入 MISSING_INFO；解析失败/空答案 fail-open；无召回证据时整体短路（零 LLM 调用）；
 * 两阶段调用均带输出 token 上限（1000 / 2000）。
 */
class AnswerVerifyNodeTest {

    private ModelFactory modelFactory;
    private ChatModel chat;
    private QaTracing qaTracing;
    private AnswerVerifyNode node;
    /** 计数型同线程 executor 的派发次数：测试里区分并行/串行分支的唯一可观测信号（并行=2，串行=0） */
    private final AtomicInteger executorCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        chat = mock(ChatModel.class);
        qaTracing = QaTracing.disabled();
        when(modelFactory.getChatModelByUsage(anyString(), any())).thenReturn(chat);
        // 真实 ExtractJsonParser（ObjectMapper）：验证 LLM 输出 JSON 数组的容错解析
        node = new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, false, false, false, 0.4, false, 60, 200)); // parallel=false 走串行，earlyAbort=false 默认关闭
    }

    /** 依次返回各阶段 LLM 输出（第一次=阶段一自评，后续=阶段二 faithfulness） */
    private void stubLlm(String... texts) {
        ChatResponse[] responses = new ChatResponse[texts.length];
        for (int i = 0; i < texts.length; i++) {
            responses[i] = new ChatResponse(List.of(new Generation(new AssistantMessage(texts[i]))));
        }
        when(chat.call(any(Prompt.class)))
                .thenReturn(responses[0], java.util.Arrays.copyOfRange(responses, 1, responses.length));
    }

    /**
     * 构造节点，只切换 parallel（其余配置与 {@link #setUp()} 一致）。
     * <p>executor 刻意用「计数型同线程 executor」，一举两得：
     * <ul>
     *   <li>{@code CompletableFuture.supplyAsync(body, sameThread)} 在调用线程内联执行，阶段一必定先于
     *       阶段二完成，于是按调用顺序打桩的 {@link #stubLlm} 依然成立，不必改成按 prompt 内容打桩
     *       （真并发下那种打桩有竞态）；本用例测的是并行分支的聚合语义，不是线程调度。</li>
     *   <li>{@link #executorCalls} 记录经 executor 派发的次数：并行分支必为 2（两阶段各一次），
     *       串行分支恒为 0。没有它，「parallel 失效退化成串行」这种回归不会被任何断言发现
     *       —— 两条路径的预期结果本来就相同。</li>
     * </ul>
     */
    private AnswerVerifyNode nodeWithParallel(boolean parallel) {
        return new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                command -> {
                    executorCalls.incrementAndGet();
                    command.run();
                },
                new SeuQaProperties(20, 2, 32, 30, parallel, false, false, 0.4, false, 60, 200));
    }

    private OverAllState state(String answer, List<ChunkEvidence> chunks) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.ANSWER, answer);
        data.put(QaContextKey.CHUNKS, chunks);
        return new OverAllState(data);
    }

    private static ChunkEvidence ev(long chunkId, String content) {
        return new ChunkEvidence(chunkId, 100L + chunkId, 1L, "报销制度.pdf", 3, "CHUNK",
                "报销流程", content, 0.9);
    }

    private OverAllState stateWithPrevAnswer(String answer, List<ChunkEvidence> chunks, String prevAnswer) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.ANSWER, answer);
        data.put(QaContextKey.CHUNKS, chunks);
        data.put(QaContextKey.PREV_ANSWER, prevAnswer);
        return new OverAllState(data);
    }

    /** 携带注入标记的状态（注入防护：阶段一规则附加注入检查） */
    private OverAllState stateWithInjection(String answer, List<ChunkEvidence> chunks, boolean injection) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.ANSWER, answer);
        data.put(QaContextKey.CHUNKS, chunks);
        data.put(QaContextKey.INJECTION, injection);
        return new OverAllState(data);
    }

    // ===== 阶段一（原有行为保持不变） =====

    @Test
    void scoreAndMissingInfo_parsedFromLlmOutput() throws Exception {
        // 带证据（空证据已短路跳过）→ 阶段二全 SUPPORTED（faithfulness=1.0），阶段一 JSON 对象格式
        stubLlm("{\"score\": 85, \"missing\": \"缺少步骤数据\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals("缺少步骤数据", out.get(QaContextKey.MISSING_INFO));
        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), "阶段二全 SUPPORTED → faithfulness 1.0");
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void noMissingInfo_normalizedToEmpty() throws Exception {
        // JSON 中 missing 为「无」也应归一为空串
        stubLlm("{\"score\": 90, \"missing\": \"无\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(0.9, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals("", out.get(QaContextKey.MISSING_INFO));
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void legacyTextFormat_fallbackStillParsed() throws Exception {
        // 模型不听话输出旧两行文本格式 → 回退解析，保证兼容
        stubLlm("85\n缺失：缺少步骤数据",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals("缺少步骤数据", out.get(QaContextKey.MISSING_INFO));
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void blankLlmOutput_scoresZeroWithNoMissingInfo() throws Exception {
        stubLlm("");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(0.0, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals("", out.get(QaContextKey.MISSING_INFO));
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void noEvidence_skipsLlmAndShortCircuits() throws Exception {
        // 无召回证据 → 零 LLM 调用，直接 0 分短路（下游 RetryOrFallback 按 chunks.isEmpty() 兜底，分数不被消费）
        Map<String, Object> out = node.apply(state("抱歉，知识库中没有找到与该问题相关的资料。", List.of()));

        assertEquals(0.0, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals("", out.get(QaContextKey.MISSING_INFO));
        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), "无证据短路时 faithfulness 恒 1.0");
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
        verify(chat, never()).call(any(Prompt.class));
        verify(modelFactory, never()).getChatModelByUsage(anyString(), any());
    }

    // ===== 阶段二（事实一致性） =====

    @Test
    void faithfulness_allSupported_combinedTakesMin() throws Exception {
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1},"
                        + "{\"claim\":\"需附发票原件\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表，附发票原件。"))));

        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), "全部 SUPPORTED → faithfulness=1.0");
        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE), "VERIFY_SCORE=min(0.85, 1.0)=0.85");
        assertEquals("", out.get(QaContextKey.MISSING_INFO), "无缺失且无矛盾/无支撑断言 → 缺失为空");
        verify(chat, times(2)).call(any(Prompt.class));
    }

    @Test
    void faithfulness_withUnsupportedAndContradicted_lowersScoreAndMergesMissing() throws Exception {
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1},"
                        + "{\"claim\":\"报销需 CEO 审批\",\"verdict\":\"CONTRADICTED\",\"evidence\":1},"
                        + "{\"claim\":\"报销需年假抵扣\",\"verdict\":\"UNSUPPORTED\",\"evidence\":null}]");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表，附发票原件。"))));

        double faithfulness = (Double) out.get(QaContextKey.FAITHFULNESS_SCORE);
        assertEquals(1.0 / 3.0, faithfulness, 0.001, "1 SUPPORTED / 3 断言 = 1/3");
        assertEquals(1.0 / 3.0, (Double) out.get(QaContextKey.VERIFY_SCORE), 0.001,
                "VERIFY_SCORE=min(0.85, 1/3)=1/3，低于阈值 0.7 应触发重试语义");
        List<String> unsupported = (List<String>) out.get(QaContextKey.UNSUPPORTED_CLAIMS);
        List<String> contradicted = (List<String>) out.get(QaContextKey.CONTRADICTED_CLAIMS);
        assertEquals(1, unsupported.size());
        assertEquals(1, contradicted.size());
        assertTrue(unsupported.get(0).contains("年假抵扣"));
        assertTrue(contradicted.get(0).contains("CEO 审批"));
        String missing = (String) out.get(QaContextKey.MISSING_INFO);
        assertTrue(missing.contains("与证据矛盾"), "矛盾断言应并入 MISSING_INFO: " + missing);
        assertTrue(missing.contains("无证据支撑"), "无支撑断言应并入 MISSING_INFO: " + missing);
    }

    /**
     * 并行路径（生产默认 {@code KB_QA_PARALLEL=true}，即线上真正跑的那条）的断言聚合结果，
     * 必须与上面的串行用例逐项一致 —— 两条路径共用 {@code AnswerVerifyNode.aggregate}。
     */
    @Test
    void faithfulness_parallelPath_resultsIdenticalToSerial() throws Exception {
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1},"
                        + "{\"claim\":\"报销需 CEO 审批\",\"verdict\":\"CONTRADICTED\",\"evidence\":1},"
                        + "{\"claim\":\"报销需年假抵扣\",\"verdict\":\"UNSUPPORTED\",\"evidence\":null}]");
        Map<String, Object> out = nodeWithParallel(true).apply(
                state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表，附发票原件。"))));

        assertEquals(1.0 / 3.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), 0.001,
                "并行路径同样 1 SUPPORTED / 3 断言 = 1/3");
        assertEquals(1.0 / 3.0, (Double) out.get(QaContextKey.VERIFY_SCORE), 0.001,
                "VERIFY_SCORE=min(0.85, 1/3)=1/3，与串行一致");
        List<String> unsupported = (List<String>) out.get(QaContextKey.UNSUPPORTED_CLAIMS);
        List<String> contradicted = (List<String>) out.get(QaContextKey.CONTRADICTED_CLAIMS);
        assertEquals(1, unsupported.size());
        assertEquals(1, contradicted.size());
        assertTrue(unsupported.get(0).contains("年假抵扣"));
        assertTrue(contradicted.get(0).contains("CEO 审批"));
        String missing = (String) out.get(QaContextKey.MISSING_INFO);
        assertTrue(missing.contains("与证据矛盾") && missing.contains("无证据支撑"),
                "缺失信息融合与串行一致: " + missing);
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
        verify(chat, times(2)).call(any(Prompt.class));
        assertEquals(2, executorCalls.get(), "两阶段都应由并行分支经 executor 派发（=0 说明退化成了串行，本用例将失去意义）");
    }

    /** 并行路径下阶段一异常必须拆包后向上传播（不能被阶段二的 fail-open 顺手吞掉） */
    @Test
    void faithfulness_parallelPhase1Throws_propagatesToCaller() {
        when(chat.call(any(Prompt.class))).thenThrow(new RuntimeException("VERIFY 模型不可用"));

        assertThrows(RuntimeException.class, () -> nodeWithParallel(true).apply(
                state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。")))));
        assertEquals(2, executorCalls.get(), "两阶段都已派发（=0 说明退化成了串行）；阶段一异常仍须向上传播");
    }

    @Test
    void faithfulness_contradictedWithoutEvidence_rejectedAsInvalid() throws Exception {
        // CONTRADICTED 必须带 evidence 引用；无引用（null）的判定视为不可信，剔除不计入矛盾、不参与分母
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1},"
                        + "{\"claim\":\"报销需 CEO 审批\",\"verdict\":\"CONTRADICTED\",\"evidence\":null}]");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表，附发票原件。"))));

        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE),
                "无引用的矛盾断言应被剔除，剩余 1 条 SUPPORTED → faithfulness=1.0");
        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE), "VERIFY_SCORE=min(0.85, 1.0)=0.85");
        List<String> contradicted = (List<String>) out.get(QaContextKey.CONTRADICTED_CLAIMS);
        assertEquals(0, contradicted.size(), "无引用矛盾断言不应计入矛盾集合");
        assertEquals("", out.get(QaContextKey.MISSING_INFO), "无有效矛盾/无支撑断言 → 缺失不追加");
    }

    @Test
    void faithfulness_invalidJson_failOpenToOne() throws Exception {
        // 阶段二输出非 JSON（无数组）→ parseArray 返回空 → fail-open faithfulness=1.0，不阻断链路
        stubLlm("{\"score\": 85, \"missing\": \"\"}", "抱歉，我无法完成评估。");
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), "解析失败应 fail-open 为 1.0");
        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void faithfulness_llmThrows_failOpenToOne() throws Exception {
        // 阶段一正常，阶段二调用抛异常 → catch 后返回空 → fail-open 1.0
        when(chat.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"score\": 85, \"missing\": \"\"}")))))
                .thenThrow(new RuntimeException("VERIFY 模型不可用"));
        Map<String, Object> out = node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), "阶段二异常应 fail-open 为 1.0");
        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals(QaState.RETRY_FALLBACK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void faithfulness_emptyAnswer_skipsSecondPhase() throws Exception {
        // 答案为空 → 阶段二跳过（不额外调用 LLM）
        stubLlm("{\"score\": 0, \"missing\": \"\"}");
        Map<String, Object> out = node.apply(state("", List.of(ev(1, "报销需填写申请表。"))));

        assertEquals(1.0, (Double) out.get(QaContextKey.FAITHFULNESS_SCORE), "空答案不执行阶段二，fail-open 1.0");
        verify(chat, times(1)).call(any(Prompt.class));
    }

    @Test
    void maxTokens_passedForBothVerifyPhases() throws Exception {
        // 两阶段调用都应携带输出 token 上限（阶段一 1000 / 阶段二 2000），防输出失控
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        List<Prompt> prompts = captor.getAllValues();
        assertEquals(1000, prompts.get(0).getOptions().getMaxTokens(), "阶段一 answer-verify 输出上限 1000");
        assertEquals(4000, prompts.get(1).getOptions().getMaxTokens(), "阶段二 answer-faithfulness 输出上限 4000");
    }

    @Test
    void disableThinking_appliesOpenAiExtraBody() throws Exception {
        // 模型配置 disableThinking=true + OpenAI 兼容模型 → judge 调用传 maxTokens + 关思考 extraBody
        ModelConfig cfg = new ModelConfig();
        cfg.setDisableThinking(true);
        when(modelFactory.resolveChatConfig(anyString(), any())).thenReturn(cfg);
        when(chat.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("deepseek-v4-flash").build());
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        List<Prompt> prompts = captor.getAllValues();
        assertEquals(1000, ((OpenAiChatOptions) prompts.get(0).getOptions()).getMaxTokens(), "阶段一 1000");
        assertEquals(4000, ((OpenAiChatOptions) prompts.get(1).getOptions()).getMaxTokens(), "阶段二 4000");
        for (Prompt p : prompts) {
            assertTrue(p.getOptions() instanceof OpenAiChatOptions, "关闭思考时应构造 OpenAiChatOptions");
            assertEquals(Map.of("thinking", Map.of("type", "disabled")),
                    ((OpenAiChatOptions) p.getOptions()).getExtraBody(), "默认关思考参数模板");
        }
    }

    @Test
    void disableThinking_customThinkingParams_usedAsExtraBody() throws Exception {
        // 自定义 thinkingParams（如 qwen 系 enable_thinking:false）应原样传入 extraBody
        ModelConfig cfg = new ModelConfig();
        cfg.setDisableThinking(true);
        cfg.setThinkingParams("{\"enable_thinking\": false}");
        when(modelFactory.resolveChatConfig(anyString(), any())).thenReturn(cfg);
        when(chat.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("qwen3").build());
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        node.apply(state("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。"))));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        OpenAiChatOptions opts = (OpenAiChatOptions) captor.getAllValues().get(1).getOptions();
        assertEquals(Map.of("enable_thinking", false), opts.getExtraBody(), "自定义参数模板应原样透传");
    }

    // ===== 提前终止（earlyAbort） =====

    private OverAllState stateWithRetry(String answer, List<ChunkEvidence> chunks, int retry, List<String> prevChunkIds) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.ANSWER, answer);
        data.put(QaContextKey.CHUNKS, chunks);
        data.put(QaContextKey.RETRY_COUNT, retry);
        data.put(QaContextKey.PREV_CHUNK_IDS, prevChunkIds);
        data.put(QaContextKey.MISSING_INFO, "缺少步骤数据");
        return new OverAllState(data);
    }

    @Test
    void earlyAbort_disabled_noNoImprovementFlag() throws Exception {
        // earlyAbort=false 时即使 delta 为空也不触发
        stubLlm("{\"score\": 85, \"missing\": \"\"}");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表。"));
        Map<String, Object> out = node.apply(stateWithRetry("报销需填写申请表。", evs, 1,
                List.of("CHUNK:1"))); // prevKeys == currentKeys → delta empty

        assertEquals(false, out.get(QaContextKey.NO_IMPROVEMENT), "earlyAbort=false 时不输出 noImprovement");
    }

    @Test
    void earlyAbort_noNewEvidence_deterministicNoImprovement() throws Exception {
        // 构造一个 earlyAbort=true 的节点
        AnswerVerifyNode earlyNode = new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, false, true, false, 0.4, false, 60, 200)); // parallel=false, earlyAbort=true
        stubLlm("{\"score\": 40, \"missing\": \"缺少步骤数据\"}");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表。"));
        Map<String, Object> out = earlyNode.apply(stateWithRetry("报销需填写申请表。", evs, 1,
                List.of("CHUNK:1"))); // delta 空

        assertEquals(true, out.get(QaContextKey.NO_IMPROVEMENT), "delta 空 → 确定性 noImprovement=true");
        assertEquals(0.4, (Double) out.get(QaContextKey.VERIFY_SCORE));
        // PREV_CHUNK_IDS 已写回本轮 keys
        List<String> prevKeys = (List<String>) out.get(QaContextKey.PREV_CHUNK_IDS);
        assertEquals(1, prevKeys.size());
        assertTrue(prevKeys.get(0).contains("CHUNK:1"));
    }

    @Test
    void earlyAbort_modelSaysNoImprovement_flagSet() throws Exception {
        AnswerVerifyNode earlyNode = new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, false, true, false, 0.4, false, 60, 200));
        // delta 非空（prevKeys 与当前不同），模型输出 noImprovement:true
        stubLlm("{\"score\": 40, \"missing\": \"缺少步骤数据\", \"noImprovement\": true}");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表。"), ev(2, "附发票原件。"));
        Map<String, Object> out = earlyNode.apply(stateWithRetry("报销需填写申请表。", evs, 1,
                List.of("CHUNK:1"))); // delta = {CHUNK:2}

        assertEquals(true, out.get(QaContextKey.NO_IMPROVEMENT), "模型判定 noImprovement=true");
    }

    @Test
    void earlyAbort_modelOmitsField_failOpenFalse() throws Exception {
        AnswerVerifyNode earlyNode = new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, false, true, false, 0.4, false, 60, 200));
        // 模型没输出 noImprovement 字段 → 缺省 false
        stubLlm("{\"score\": 40, \"missing\": \"缺少步骤数据\"}");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表。"), ev(2, "附发票原件。"));
        Map<String, Object> out = earlyNode.apply(stateWithRetry("报销需填写申请表。", evs, 1,
                List.of("CHUNK:1")));

        assertEquals(false, out.get(QaContextKey.NO_IMPROVEMENT), "缺省 noImprovement 字段 → false");
    }

    @Test
    void earlyAbort_retryZero_notTriggered() throws Exception {
        AnswerVerifyNode earlyNode = new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, false, true, false, 0.4, false, 60, 200));
        // retry=0，即使 delta 空也不触发
        stubLlm("{\"score\": 85, \"missing\": \"\"}");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表。"));
        Map<String, Object> out = earlyNode.apply(stateWithRetry("报销需填写申请表。", evs, 0,
                List.of("CHUNK:1")));

        assertEquals(false, out.get(QaContextKey.NO_IMPROVEMENT), "retry=0 时不触发提早终止");
    }

    @Test
    void jsonModeEnabled_node_verifySucceeds() throws Exception {
        // jsonMode=true 不影响解析容错，verify 流程应正常完成
        AnswerVerifyNode jsonNode = new AnswerVerifyNode(modelFactory, qaTracing, new PromptCatalog(),
                new ExtractJsonParser(new ObjectMapper()),
                Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, false, false, false, 0.4, true, 60, 200));
        stubLlm("{\"score\": 85, \"missing\": \"\", \"noImprovement\": false}");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表。"));
        Map<String, Object> out = jsonNode.apply(stateWithRetry("报销需填写申请表。", evs, 0, List.of()));
        assertEquals(0.85, (Double) out.get(QaContextKey.VERIFY_SCORE));
        assertEquals("", out.get(QaContextKey.MISSING_INFO));
    }

    @Test
    void bothPhases_useSystemAndUserMessageSplitWithJsonEvidence() throws Exception {
        // 消息角色化：阶段一/阶段二均为 SystemMessage(规则) + UserMessage(数据)，证据 JSON 渲染
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表，附发票原件。"));
        node.apply(state("报销需填写申请表。", evs));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        List<Prompt> prompts = captor.getAllValues();
        for (int i = 0; i < 2; i++) {
            List<org.springframework.ai.chat.messages.Message> messages = prompts.get(i).getInstructions();
            assertEquals(2, messages.size(), "阶段应拆分为系统+用户两条消息");
            assertTrue(messages.get(0) instanceof org.springframework.ai.chat.messages.SystemMessage,
                    "第一条应为系统规则消息");
            assertTrue(messages.get(1) instanceof org.springframework.ai.chat.messages.UserMessage,
                    "第二条应为用户数据消息");
        }
        String phase1User = prompts.get(0).getInstructions().get(1).getText();
        assertTrue(phase1User.contains("\"index\":1"), "阶段一证据应为 JSON 数组: " + phase1User);
        assertTrue(phase1User.contains("上一轮自检结果"), "阶段一数据区应含上一轮自检结果: " + phase1User);
        assertTrue(phase1User.contains("上一轮回答"), "阶段一数据区应含上一轮回答: " + phase1User);
        String phase2User = prompts.get(1).getInstructions().get(1).getText();
        assertTrue(phase2User.contains("\"index\":1"), "阶段二证据应为 JSON 数组: " + phase2User);
        assertTrue(phase2User.contains("回答："), "阶段二数据区应含回答: " + phase2User);
    }

    @Test
    void prevAnswer_includedInPhase1Prompt() throws Exception {
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表，附发票原件。"));
        node.apply(stateWithPrevAnswer("报销需填写申请表。", evs, "上一轮答案内容XYZ"));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1User = captor.getAllValues().get(0).getInstructions().get(1).getText();
        assertTrue(phase1User.contains("上一轮答案内容XYZ"),
                "阶段一数据区应包含 PREV_ANSWER 内容: " + phase1User);
    }

    @Test
    void prevAnswer_absent_rendersNoFirstEval() throws Exception {
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表，附发票原件。"));
        node.apply(state("报销需填写申请表。", evs));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1User = captor.getAllValues().get(0).getInstructions().get(1).getText();
        assertTrue(phase1User.contains("（无，首次评估）"),
                "无 PREV_ANSWER 时应渲染默认占位符: " + phase1User);
    }

    @Test
    void injectionTrue_phase1RulesContainInjectionCheck() throws Exception {
        // 注入标记=true：阶段一系统规则必须追加注入检查（答案遵循注入指令 → 判 0 分）
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        List<ChunkEvidence> evs = List.of(ev(1, "报销需填写申请表，附发票原件。"));
        node.apply(stateWithInjection("报销需填写申请表。", evs, true));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1Rules = captor.getAllValues().get(0).getInstructions().get(0).getText();
        assertTrue(phase1Rules.contains("注入检查"),
                "注入场景阶段一规则应含注入检查: " + phase1Rules);
        assertTrue(phase1Rules.contains("score 必须判为 0"),
                "注入场景阶段一规则应声明遵循注入指令判 0 分: " + phase1Rules);
    }

    @Test
    void injectionFalse_phase1RulesWithoutInjectionCheck() throws Exception {
        // 注入标记=false：阶段一规则不含注入检查（回归原有行为）
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        node.apply(stateWithInjection("报销需填写申请表。", List.of(ev(1, "报销需填写申请表。")), false));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1Rules = captor.getAllValues().get(0).getInstructions().get(0).getText();
        assertFalse(phase1Rules.contains("注入检查"),
                "非注入场景阶段一规则不应含注入检查: " + phase1Rules);
    }

    @Test
    void resolvedQuestion_mainQuestionResolved_originalQuestionIncluded() throws Exception {
        // P0：消歧问题为评分基准（主），原始指代句为对照（辅）→ 阶段一数据区应同时包含两者
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "它怎么申请？");
        data.put(QaContextKey.RESOLVED_QUESTION, "国家奖学金的申请条件是什么");
        data.put(QaContextKey.ANSWER, "报销需填写申请表。");
        data.put(QaContextKey.CHUNKS, List.of(ev(1, "报销需填写申请表。")));
        node.apply(new OverAllState(data));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1User = captor.getAllValues().get(0).getInstructions().get(1).getText();
        assertTrue(phase1User.contains("问题：国家奖学金的申请条件是什么"),
                "主问题应为消歧后问题: " + phase1User);
        assertTrue(phase1User.contains("原问题（消歧前；与问题一致时为空）：它怎么申请？"),
                "原问题对照应保留原始指代句: " + phase1User);
    }

    @Test
    void multiIntent_businessQuestionPrimary_originalLineEmpty() throws Exception {
        // 多意图：主问题=BUSINESS_QUESTION，对照问题与之相同 → 原问题行留空，闲聊片段不进入自检
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "你好呀 报销流程是什么？");
        data.put(QaContextKey.BUSINESS_QUESTION, "报销流程是什么？");
        data.put(QaContextKey.ANSWER, "报销需填写申请表。");
        data.put(QaContextKey.CHUNKS, List.of(ev(1, "报销需填写申请表。")));
        node.apply(new OverAllState(data));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1User = captor.getAllValues().get(0).getInstructions().get(1).getText();
        assertTrue(phase1User.contains("问题：报销流程是什么？"), "主问题应为业务片段聚合: " + phase1User);
        assertFalse(phase1User.contains("你好呀"), "闲聊片段不应进入自检数据区: " + phase1User);
    }

    @Test
    void multiIntent_resolvedPrimary_businessAsOriginal() throws Exception {
        // 优先级调换后：主问题=消歧后问题，对照=消歧前业务聚合 → 原问题行恢复非空（防丢子问题）
        stubLlm("{\"score\": 85, \"missing\": \"\"}",
                "[{\"claim\":\"报销需填写申请表\",\"verdict\":\"SUPPORTED\",\"evidence\":1}]");
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "你好呀 它怎么申请？");
        data.put(QaContextKey.BUSINESS_QUESTION, "它怎么申请？");
        data.put(QaContextKey.RESOLVED_QUESTION, "国家奖学金的申请条件是什么");
        data.put(QaContextKey.ANSWER, "国家奖学金需在规定期限内提交申请材料。");
        data.put(QaContextKey.CHUNKS, List.of(ev(1, "报销需填写申请表。")));
        node.apply(new OverAllState(data));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(captor.capture());
        String phase1User = captor.getAllValues().get(0).getInstructions().get(1).getText();
        assertTrue(phase1User.contains("问题：国家奖学金的申请条件是什么"),
                "自检主问题应为消歧后问题: " + phase1User);
        assertTrue(phase1User.contains("它怎么申请？"),
                "对照应保留消歧前业务聚合（触发覆盖子问题提醒）: " + phase1User);
        assertFalse(phase1User.contains("你好呀"), "闲聊片段不应进入自检数据区: " + phase1User);
    }
}
