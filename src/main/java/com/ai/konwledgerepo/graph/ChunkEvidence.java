package com.ai.konwledgerepo.graph;

/**
 * 检索证据：一条召回结果，携带引用元数据（来源类型/文档名/页码/chunkId/标题）。
 * sourceType：CHUNK（文档分块）/ BUSINESS（业务知识）/ QA（问答对）。
 * title：业务知识的术语或问答对的问题，用于引用展示。
 */
public record ChunkEvidence(Long chunkId, Long docId, Long kbId, String docName, Integer pageNum,
                            String sourceType, String title, String content, double score) {
}
