package com.ai.konwledgerepo.dto;

import java.util.List;

/**
 * 抽取任务结果预览（本次抽取出的 DRAFT 草稿）。
 */
public record ExtractTaskResultResponse(List<BusinessKnowledgeResponse> businessKnowledge,
                                        List<QaPairResponse> qaPairs) {
}
