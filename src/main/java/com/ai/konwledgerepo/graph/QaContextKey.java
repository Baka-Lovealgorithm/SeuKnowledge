package com.ai.konwledgerepo.graph;

import java.util.List;

/**
 * OverAllState 状态键常量（单一来源）。
 */
public final class QaContextKey {

    public static final String RAW_QUESTION = "rawQuestion";
    public static final String KB_ID = "kbId";
    public static final String KB_NAME = "kbName";
    /** 当前工作空间 id（多工作空间隔离，模型解析按空间） */
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String SESSION_ID = "sessionId";
    public static final String INTENT = "intent";
    /** 提示注入检测标记（意图路由输出：用户问题是否包含注入指令，如要求忽略指令/输出系统提示词等） */
    public static final String INJECTION = "injection";
    public static final String QUERIES = "queries";
    public static final String CHUNKS = "chunks";
    /** 累计证据池：历轮被 rerank 选中、进入答案生成的知识（跨轮保留，重试轮不再参与 rerank） */
    public static final String ACCUMULATED_CHUNKS = "accumulatedChunks";
    public static final String ANSWER = "answer";
    public static final String REFS = "refs";
    public static final String VERIFY_SCORE = "verifyScore";
    /** 事实一致性分（SUPPORTED 断言占比，0~1；解析失败 fail-open 为 1.0） */
    public static final String FAITHFULNESS_SCORE = "faithfulnessScore";
    /** 无证据支撑的断言（List<String>，最多 5 条、每条截断 60 字） */
    public static final String UNSUPPORTED_CLAIMS = "unsupportedClaims";
    /** 与证据矛盾的断言（List<String>，同上） */
    public static final String CONTRADICTED_CLAIMS = "contradictedClaims";
    /** 答案自检输出的"缺失信息"反馈（重试轮供 QueryRewrite 定向改写；为空表示无需定向） */
    public static final String MISSING_INFO = "missingInfo";
    /** 上一轮 CHUNKS 的 dedupKey 集合（累计算法本轮的 delta 即新增证据） */
    public static final String PREV_CHUNK_IDS = "prevChunkIds";
    /** 上一轮重试的答案（供 verify 阶段一前后对比；首次评估无值） */
    public static final String PREV_ANSWER = "prevAnswer";
    /** 新增证据未改善（verify 判定本轮新增证据未明显帮助回答问题，提前终止重试） */
    public static final String NO_IMPROVEMENT = "noImprovement";
    public static final String RETRY_COUNT = "retryCount";
    public static final String MAX_RETRY = "maxRetry";
    public static final String NEXT = "next";
    public static final String HISTORY = "history";
    public static final String CHAT_ONLY_ANSWER = "chatOnlyAnswer";
    /** Agent 配置快照（AgentConfig 记录） */
    public static final String AGENT = "agent";
    /** 会话滚动摘要文本 */
    public static final String MEMORY_SUMMARY = "memorySummary";

    /** ReplaceStrategy 覆盖的键列表（QaGraphRunner 组装图时使用） */
    public static final List<String> REPLACE_KEYS = List.of(
            RAW_QUESTION, KB_ID, KB_NAME, WORKSPACE_ID, SESSION_ID, INTENT, INJECTION, QUERIES,
            CHUNKS, ACCUMULATED_CHUNKS, ANSWER, REFS, PREV_ANSWER,
            VERIFY_SCORE, FAITHFULNESS_SCORE, UNSUPPORTED_CLAIMS, CONTRADICTED_CLAIMS,
            MISSING_INFO, PREV_CHUNK_IDS, NO_IMPROVEMENT, RETRY_COUNT, MAX_RETRY,
            NEXT, HISTORY, CHAT_ONLY_ANSWER, MEMORY_SUMMARY, AGENT);

    private QaContextKey() {
    }
}
