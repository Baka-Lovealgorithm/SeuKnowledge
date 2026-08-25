package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 问答对响应。
 */
public record QaPairResponse(Long id, Long kbId, String question, String normalizedQuestion,
                             List<String> synonyms, String answer, Long sourceDocId, String sourceDocName,
                             String status, Integer version, boolean deleted, LocalDateTime createdAt) {
}
