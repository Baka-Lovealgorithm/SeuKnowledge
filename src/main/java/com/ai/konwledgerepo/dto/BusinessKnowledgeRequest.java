package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 业务知识请求。
 */
public record BusinessKnowledgeRequest(
        @NotBlank(message = "术语不能为空")
        @Size(max = 255, message = "术语最长 255 字符")
        String term,

        List<String> aliases,
        String definition,
        String scope,
        String example,
        String prohibitedRules,
        Long sourceDocId) {
}
