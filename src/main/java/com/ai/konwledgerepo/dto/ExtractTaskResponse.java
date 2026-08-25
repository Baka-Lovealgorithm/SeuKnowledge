package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽取任务响应。
 */
public record ExtractTaskResponse(Long id, Long kbId, List<Long> docIds, String extractType, String status,
                                  Integer totalDocs, Integer processedDocs, Integer progress,
                                  String resultSummary, String errorLog, List<Long> failedDocIds,
                                  Long durationMs, Long tokenInput, Long tokenOutput, Long tokenTotal,
                                  LocalDateTime createdAt, LocalDateTime finishedAt) {
}
