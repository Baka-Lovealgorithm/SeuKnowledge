package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.JudgeOptions;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.common.ContextPropagator;
import com.ai.konwledgerepo.service.extract.ExtractJsonParser;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ai.konwledgerepo.entity.SourceType;

/**
 * 答案自检节点（两阶段）：
 * 阶段一：基于召回证据评估回答的相关性与充分性，打分 0~1，输出"缺失信息"反馈（MISSING_INFO），
 *         供重试轮 QueryRewrite 定向改写检索；参照证据打分可避免「答案正确却因未见证据而被判低分」的误判。
 * 阶段二（事实一致性校验，claim-level faithfulness）：用 VERIFY 模型把答案拆成原子断言，
 *         逐条对照证据判 SUPPORTED / CONTRADICTED / UNSUPPORTED，
 *         faithfulness = SUPPORTED 数 / 断言总数；最终 VERIFY_SCORE = min(阶段一, faithfulness)，
 *         矛盾/无支撑断言并入 MISSING_INFO 供重试定向改写。解析失败按 fail-open（faithfulness=1.0）不阻断链路。
 */
@Component
public class AnswerVerifyNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(AnswerVerifyNode.class);

    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d{1,3})");
    private static final int MAX_MISSING_LEN = 120;
    private static final int MAX_CLAIM_ITEMS = 5;
    private static final int MAX_CLAIM_LEN = 60;
    /** 阶段一（相关性/完整性）输出上限：仅 {"score":…,"missing":…} 一个 JSON 对象，1000 token 足够且防失控 */
    private static final int MAX_VERIFY_TOKENS = 1000;
    /** 阶段二（faithfulness）输出上限：断言 JSON 数组为任务固有量级（13 断言约 1400 token），4000 覆盖 20+ 断言场景且防失控 */
    private static final int MAX_FAITHFULNESS_TOKENS = 4000;

    /** 自检结果：分数 + 缺失信息反馈（空串表示无需定向改写）+ 新增证据未改善标记 */
    public record VerifyResult(double score, String missingInfo, boolean noImprovement) {
    }

    /** 断言判定结果 */
    public record ClaimVerdict(String claim, String verdict, Integer evidence) {
    }

    private final ModelFactory modelFactory;
    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;
    private final ExtractJsonParser jsonParser;
    private final Executor qaExecutor;
    private final boolean parallel;
    private final boolean earlyAbort;
    private final boolean jsonMode;

    public AnswerVerifyNode(ModelFactory modelFactory, QaTracing qaTracing, PromptCatalog promptCatalog,
                            ExtractJsonParser jsonParser,
                            @org.springframework.beans.factory.annotation.Qualifier("qaTaskExecutor") Executor qaExecutor,
                            com.ai.konwledgerepo.config.props.SeuQaProperties qaProps) {
        this.modelFactory = modelFactory;
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
        this.jsonParser = jsonParser;
        this.qaExecutor = qaExecutor;
        this.parallel = qaProps.parallel();
        this.earlyAbort = qaProps.earlyAbort();
        this.jsonMode = qaProps.verifyJsonMode();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        SseStreamContext.sendStage("ANSWER_VERIFY", "答案自检");
        Span span = qaTracing.begin("node/answer_verify");
        try {
            String question = state.value(QaContextKey.RAW_QUESTION).map(String::valueOf).orElse("");
            String answer = state.value(QaContextKey.ANSWER).map(String::valueOf).orElse("");
            List<ChunkEvidence> chunks = QaContext.chunks(state.value(QaContextKey.CHUNKS).orElse(List.of()));

            // ===== 提前终止判定：计算本轮新增证据（delta），供阶段一判断"新增证据是否明显帮助" =====
            int retry = QaContext.intValue(state, QaContextKey.RETRY_COUNT, 0);
            Set<String> prevKeys = new HashSet<>(QaContext.stringList(state, QaContextKey.PREV_CHUNK_IDS));
            List<String> currentKeys = new ArrayList<>();
            for (ChunkEvidence c : chunks) {
                currentKeys.add(SourceType.dedupKey(c.sourceType(), c.chunkId()));
            }
            Set<String> deltaKeys = new HashSet<>(currentKeys);
            deltaKeys.removeAll(prevKeys);
            boolean earlyAbortActive = earlyAbort && retry >= 1;
            String prevMissing = state.value(QaContextKey.MISSING_INFO).map(String::valueOf).orElse("");
            String prevContext = earlyAbortActive
                    ? ("缺失信息：" + (prevMissing.isBlank() ? "无" : prevMissing)
                        + (deltaKeys.isEmpty() ? "（本轮无新增证据）" : "（本轮新增证据已标注【本轮新增】）"))
                    : "（无，首次评估）";
            Set<String> markKeys = earlyAbortActive ? deltaKeys : Set.of();

            // 证据上下文：全部证据的标题 + 内容片段（让评估器知道覆盖了哪些主题，才能指出缺什么）
            String evidence = chunks.isEmpty() ? "（无召回证据）" : buildEvidence(chunks, markKeys);

            AgentConfig agent = QaContext.agent(state);
            String agentPrompt = (agent == null || agent.systemPrompt() == null || agent.systemPrompt().isBlank())
                    ? ""
                    : "Agent 场景：" + agent.systemPrompt() + "\n";

            Long workspaceId = QaContext.longValue(state, QaContextKey.WORKSPACE_ID, -1L);

            // ===== 阶段一：相关性 + 完整性自评（GENERATE 模型，保持原语义） =====
            // ===== 阶段二：事实一致性校验（VERIFY 模型，claim-level faithfulness） =====
            // 解析失败按 fail-open（faithfulness=1.0）不阻断链路
            boolean needPhase2 = !chunks.isEmpty() && answer != null && !answer.isBlank();
            VerifyResult result;
            double faithfulness;
            List<String> unsupported;
            List<String> contradicted;
            int supportedCount;
            int totalClaims;

            if (parallel && needPhase2) {
                // 两阶段并行：max(阶段一, 阶段二) 省一次 LLM 串行时长
                CompletableFuture<VerifyResult> phase1F = CompletableFuture.supplyAsync(
                        ContextPropagator.wrapSupplier(() -> {
                            ChatModel phase1Chat = modelFactory.getChatModelByUsage(ModelUsage.VERIFY.value(), workspaceId);
                            ModelConfig phase1Cfg = modelFactory.resolveChatConfig(ModelUsage.VERIFY.value(), workspaceId);
                            String phase1Prompt = promptCatalog.get("answer-verify").formatted(agentPrompt, prevContext, evidence, question, answer);
                            String phase1Resp = LlmTrace.call(qaTracing, phase1Chat, phase1Prompt,
                                    JudgeOptions.of(phase1Chat, phase1Cfg, MAX_VERIFY_TOKENS, jsonMode));
                            return parseResult(phase1Resp);
                        }), qaExecutor);
                CompletableFuture<List<ClaimVerdict>> phase2F = CompletableFuture.supplyAsync(
                        ContextPropagator.wrapSupplier(() ->
                                checkFaithfulness(agentPrompt, evidence, question, answer, workspaceId)), qaExecutor);

                // 阶段一结果（异常向上传播，保持原语义）
                try {
                    result = phase1F.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception ex) {
                        throw ex;
                    }
                    throw new RuntimeException(cause);
                }
                span.setAttribute("score", result.score());
                span.setAttribute("missing_info", result.missingInfo());

                // 阶段二结果（异常按 fail-open，不阻断链路）
                List<ClaimVerdict> verdicts;
                try {
                    verdicts = phase2F.get();
                } catch (ExecutionException e) {
                    log.warn("AnswerVerify 事实一致性校验失败，按 fail-open 处理: {}", e.getMessage());
                    verdicts = List.of();
                }
                if (verdicts.isEmpty()) {
                    log.warn("AnswerVerify 阶段二未产出有效断言（输出为空/解析失败/断言全被剔除），faithfulness fail-open=1.0");
                    faithfulness = 1.0;
                    unsupported = List.of();
                    contradicted = List.of();
                    supportedCount = 0;
                    totalClaims = 0;
                } else {
                    supportedCount = (int) verdicts.stream().filter(v -> "SUPPORTED".equals(v.verdict())).count();
                    faithfulness = (double) supportedCount / verdicts.size();
                    totalClaims = verdicts.size();
                    List<String> unsup = new ArrayList<>();
                    List<String> contr = new ArrayList<>();
                    for (ClaimVerdict v : verdicts) {
                        if ("UNSUPPORTED".equals(v.verdict())) {
                            unsup.add(truncate(v.claim()));
                        } else if ("CONTRADICTED".equals(v.verdict())) {
                            contr.add(truncate(v.claim()));
                        }
                    }
                    unsupported = unsup;
                    contradicted = contr;
                    span.setAttribute("faithfulness", faithfulness);
                    span.setAttribute("claims", totalClaims);
                    span.setAttribute("unsupported_count", unsupported.size());
                    span.setAttribute("contradicted_count", contradicted.size());
                    log.info("AnswerVerify 事实一致性: faithfulness={} 断言={} 无支撑={} 矛盾={}",
                            String.format("%.2f", faithfulness), totalClaims, unsupported.size(), contradicted.size());
                }
            } else {
                // 串行路径（parallel=false 或无需阶段二）
                ChatModel chat = modelFactory.getChatModelByUsage(ModelUsage.VERIFY.value(), workspaceId);
                ModelConfig cfg = modelFactory.resolveChatConfig(ModelUsage.VERIFY.value(), workspaceId);
                String prompt = promptCatalog.get("answer-verify").formatted(agentPrompt, prevContext, evidence, question, answer);
                String response = LlmTrace.call(qaTracing, chat, prompt, JudgeOptions.of(chat, cfg, MAX_VERIFY_TOKENS, jsonMode));
                result = parseResult(response);
                span.setAttribute("score", result.score());
                span.setAttribute("missing_info", result.missingInfo());

                faithfulness = 1.0;
                unsupported = new ArrayList<>();
                contradicted = new ArrayList<>();
                supportedCount = 0;
                totalClaims = 0;
                if (needPhase2) {
                    List<ClaimVerdict> verdicts = checkFaithfulness(agentPrompt, evidence, question, answer, workspaceId);
                    if (verdicts.isEmpty()) {
                        log.warn("AnswerVerify 阶段二未产出有效断言（输出为空/解析失败/断言全被剔除），faithfulness fail-open=1.0");
                    } else {
                        supportedCount = (int) verdicts.stream().filter(v -> "SUPPORTED".equals(v.verdict())).count();
                        faithfulness = (double) supportedCount / verdicts.size();
                        totalClaims = verdicts.size();
                        for (ClaimVerdict v : verdicts) {
                            if ("UNSUPPORTED".equals(v.verdict())) {
                                unsupported.add(truncate(v.claim()));
                            } else if ("CONTRADICTED".equals(v.verdict())) {
                                contradicted.add(truncate(v.claim()));
                            }
                        }
                        span.setAttribute("faithfulness", faithfulness);
                        span.setAttribute("claims", totalClaims);
                        span.setAttribute("unsupported_count", unsupported.size());
                        span.setAttribute("contradicted_count", contradicted.size());
                        log.info("AnswerVerify 事实一致性: faithfulness={} 断言={} 无支撑={} 矛盾={}",
                                String.format("%.2f", faithfulness), totalClaims, unsupported.size(), contradicted.size());
                    }
                }
            }

            // ===== 组合分 + MISSING_INFO 融合 + 提前终止标记 =====
            if (faithfulness < 1.0 || supportedCount > 0) {
                span.setAttribute("faithfulness", faithfulness);
                span.setAttribute("claims", totalClaims);
                span.setAttribute("unsupported_count", unsupported.size());
                span.setAttribute("contradicted_count", contradicted.size());
            }
            double combined = Math.min(result.score(), faithfulness);
            span.setAttribute("combined_score", combined);
            String missingInfo = mergeMissingInfo(result.missingInfo(), unsupported, contradicted);

            // 提前终止：delta 为空（确定性）或模型判定 noImprovement（仅 earlyAbortActive 时生效）
            boolean noImprovement = false;
            if (earlyAbortActive) {
                if (deltaKeys.isEmpty()) {
                    noImprovement = true;
                    log.info("AnswerVerify 本轮无新增证据（delta 为空），提前终止重试");
                } else if (result.noImprovement()) {
                    noImprovement = true;
                    log.info("AnswerVerify 模型判定新增证据未改善，提前终止重试");
                }
            }
            if (noImprovement) {
                span.setAttribute("no_improvement", true);
            }

            return Map.of(
                    QaContextKey.VERIFY_SCORE, combined,
                    QaContextKey.MISSING_INFO, missingInfo,
                    QaContextKey.FAITHFULNESS_SCORE, faithfulness,
                    QaContextKey.UNSUPPORTED_CLAIMS, unsupported,
                    QaContextKey.CONTRADICTED_CLAIMS, contradicted,
                    QaContextKey.NO_IMPROVEMENT, noImprovement,
                    QaContextKey.PREV_CHUNK_IDS, currentKeys,
                    QaContextKey.NEXT, QaState.RETRY_FALLBACK.name());
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** 阶段二：调 VERIFY 模型拆断言并逐条判定；解析失败返回空列表（调用方按 fail-open 处理） */
    private List<ClaimVerdict> checkFaithfulness(String agentPrompt, String evidence, String question,
                                                 String answer, Long workspaceId) {
        try {
            ChatModel verifyChat = modelFactory.getChatModelByUsage(ModelUsage.VERIFY.value(), workspaceId);
            ModelConfig verifyCfg = modelFactory.resolveChatConfig(ModelUsage.VERIFY.value(), workspaceId);
            String verifyPrompt = promptCatalog.get("answer-faithfulness")
                    .formatted(agentPrompt, evidence, question, answer);
            String verifyResponse = LlmTrace.call(qaTracing, verifyChat, verifyPrompt,
                    JudgeOptions.of(verifyChat, verifyCfg, MAX_FAITHFULNESS_TOKENS, jsonMode));
            List<Map<String, Object>> items = jsonParser.parseArray(verifyResponse);
            List<ClaimVerdict> verdicts = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Object claim = item.get("claim");
                Object verdict = item.get("verdict");
                if (claim == null || verdict == null) {
                    continue;
                }
                String v = String.valueOf(verdict).trim().toUpperCase();
                if (!"SUPPORTED".equals(v) && !"CONTRADICTED".equals(v) && !"UNSUPPORTED".equals(v)) {
                    continue;
                }
                Integer evidenceIdx = item.get("evidence") instanceof Number n ? n.intValue() : null;
                // CONTRADICTED 必须带引用（evidence 非 null 且 ≥1）：矛盾判定无对应证据视为不可信，剔除不计入
                if ("CONTRADICTED".equals(v) && (evidenceIdx == null || evidenceIdx < 1)) {
                    log.warn("AnswerVerify 矛盾断言未给出证据引用，判定无效剔除: {}", claim);
                    continue;
                }
                verdicts.add(new ClaimVerdict(String.valueOf(claim), v, evidenceIdx));
            }
            return verdicts;
        } catch (Exception e) {
            log.warn("AnswerVerify 事实一致性校验失败，按 fail-open 处理: {}", e.getMessage());
            return List.of();
        }
    }

    /** 缺失信息融合：原缺失 + 无支撑/矛盾断言（前 2 条，整体 ≤ MAX_MISSING_LEN） */
    private static String mergeMissingInfo(String original, List<String> unsupported, List<String> contradicted) {
        StringBuilder sb = new StringBuilder(original == null ? "" : original);
        List<String> flags = new ArrayList<>();
        for (String c : contradicted) {
            flags.add("与证据矛盾：" + c);
        }
        for (String c : unsupported) {
            flags.add("无证据支撑：" + c);
        }
        int count = 0;
        for (String f : flags) {
            if (count >= 2 || sb.length() + f.length() + 3 > MAX_MISSING_LEN) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(f);
            count++;
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > MAX_CLAIM_LEN ? t.substring(0, MAX_CLAIM_LEN) + "…" : t;
    }

    /** 全部证据：来源/文档/标题 + 内容片段。newKeys 非空时标注【本轮新增】供 earlyAbort 判断。 */
    private static String buildEvidence(List<ChunkEvidence> chunks, Set<String> newKeys) {
        boolean mark = newKeys != null && !newKeys.isEmpty();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkEvidence c = chunks.get(i);
            String title = c.title() == null || c.title().isBlank() ? c.docName() : c.title();
            String snippet = c.content() == null ? "" : c.content();
            sb.append("[").append(i + 1).append("] ");
            if (mark && newKeys.contains(SourceType.dedupKey(c.sourceType(), c.chunkId()))) {
                sb.append("【本轮新增】");
            }
            sb.append(c.sourceType())
                    .append("《").append(c.docName()).append("》 标题：").append(title)
                    .append("\n").append(snippet).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 解析阶段一输出：优先严格 JSON 对象 {"score":85,"missing":"…"}（模板指定格式）；
     * 解析不到 score 时回退旧「分数 + 缺失：…」两行文本格式（容错模型不听话的输出）。
     */
    private VerifyResult parseResult(String response) {
        if (response == null || response.isBlank()) {
            return new VerifyResult(0.0, "", false);
        }
        Map<String, Object> obj = jsonParser.parseObject(response);
        if (!obj.isEmpty() && obj.get("score") instanceof Number scoreNum) {
            double score = Math.min(100, Math.max(0, scoreNum.doubleValue())) / 100.0;
            String missing = obj.get("missing") == null ? "" : String.valueOf(obj.get("missing")).trim();
            boolean noImp = obj.get("noImprovement") instanceof Boolean b && b;
            return new VerifyResult(score, normalizeMissing(missing), noImp);
        }
        String missing = "";
        String scoreText = response;
        int idx = response.indexOf("缺失：");
        if (idx >= 0) {
            scoreText = response.substring(0, idx);
            missing = response.substring(idx + 3).trim();
            int nl = missing.indexOf('\n');
            if (nl >= 0) {
                missing = missing.substring(0, nl);
            }
            missing = normalizeMissing(missing);
        }
        Matcher matcher = SCORE_PATTERN.matcher(scoreText);
        double score = 0.0;
        if (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            score = Math.min(100, Math.max(0, value)) / 100.0;
        }
        return new VerifyResult(score, missing, false);
    }

    /** 缺失信息规范化：「无」与空串归一为空；去尾部标点；超长截断 */
    private static String normalizeMissing(String missing) {
        String m = missing == null ? "" : missing.trim();
        m = m.replaceAll("[。；;]+$", "").trim();
        if (m.isEmpty() || "无".equals(m)) {
            return "";
        }
        return m.length() > MAX_MISSING_LEN ? m.substring(0, MAX_MISSING_LEN) : m;
    }
}
