package com.ai.konwledgerepo.common;

/**
 * 默认文案 / 兜底常量（收敛各处硬编码的业务文案）。
 */
public final class Defaults {

    private Defaults() {
    }

    /** 会话默认标题 */
    public static final String DEFAULT_SESSION_TITLE = "新会话";

    /** 默认 Agent 名称 */
    public static final String DEFAULT_AGENT_NAME = "默认 Agent";

    /** 空证据兜底（知识库无相关内容） */
    public static final String NO_EVIDENCE_ANSWER = "抱歉，知识库中没有找到与该问题相关的资料。";

    /** 证据不足诚实兜底 */
    public static final String INSUFFICIENT_EVIDENCE_ANSWER = "抱歉，知识库中的信息不足以确定地回答该问题，"
            + "建议咨询相关负责人或提供更多细节。";

    /** 显式拒答（重试耗尽仍低于自检阈值），提示附候选证据 */
    public static final String INSUFFICIENT_EVIDENCE_REFUSAL = "抱歉，经过多轮检索与自检，"
            + "知识库中的现有资料仍不足以确定地回答该问题。以下为检索到的候选证据，供您自查：";

    /** 部分回答前置声明（低分但无矛盾断言、分数达下限时，合成答案 + 缺漏声明 + 候选证据一并给出） */
    public static final String PARTIAL_ANSWER_PREFIX = "抱歉，检索到的知识库资料未能完全覆盖该问题。"
            + "以下内容基于现有资料整理，请结合候选证据谨慎参考：\n";

    /** 部分回答缺漏声明模板（%s = 缺失信息，来自 verify 阶段一/阶段二） */
    public static final String PARTIAL_ANSWER_MISSING_SUFFIX = "\n\n其中未能得到证据支撑或知识库未提及的内容：%s";

    /** 问答链路异常时的通用兜底 */
    public static final String QA_FALLBACK_ANSWER = "抱歉，暂时无法回答该问题。";

    /** 空引用列表 */
    public static final String EMPTY_REFS = "[]";

    /** 未加入任何工作空间的提示 */
    public static final String NO_WORKSPACE_MESSAGE = "当前账号未加入任何工作空间，请联系管理员创建或邀请";
}
