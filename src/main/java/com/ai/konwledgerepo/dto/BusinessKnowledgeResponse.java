package com.ai.konwledgerepo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务知识响应。
 */
public record BusinessKnowledgeResponse(Long id, Long kbId, String term, List<String> aliases,
                                        String definition, String scope, String example, String prohibitedRules,
                                        Long sourceDocId, String sourceDocName, String status, Integer version,
                                        boolean deleted, LocalDateTime createdAt) {
}
