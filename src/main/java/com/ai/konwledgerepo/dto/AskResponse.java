package com.ai.konwledgerepo.dto;

/**
 * 问答响应：答案 + 证据引用 + 意图。
 */
public record AskResponse(String answer, String refs, String intent) {
}
