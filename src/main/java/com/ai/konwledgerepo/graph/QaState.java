package com.ai.konwledgerepo.graph;

/**
 * 问答链路状态图节点标识。
 */
public enum QaState {
    START,
    INTENT_ROUTE,
    QUERY_REWRITE,
    KNOWLEDGE_RECALL,
    RERANK,
    ANSWER_COMPOSE,
    ANSWER_VERIFY,
    RETRY_FALLBACK,
    /** 闲聊兜底终态 */
    CHAT_ONLY,
    /** 答案合并（多意图收口：业务答案 + 闲聊回复 + 注入拒答统一输出） */
    MERGE_ANSWER,
    /** 终结占位节点（条件边 mapping 目标必须是已注册节点；graph-core 限制的兼容层） */
    TERMINAL,
    END
}
