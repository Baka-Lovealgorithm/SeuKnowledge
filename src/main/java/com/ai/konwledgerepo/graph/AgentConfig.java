package com.ai.konwledgerepo.graph;

/**
 * 问答链路使用的 Agent 配置快照（不可变）。由 ChatService 加载后放入状态图初始状态，
 * 各节点（意图路由/改写/召回/重排/生成/自检/重试）读取本配置执行。
 */
public record AgentConfig(String name, String systemPrompt, int topK, double verifyThreshold,
                          int maxRetry, int memoryWindow) {
}
