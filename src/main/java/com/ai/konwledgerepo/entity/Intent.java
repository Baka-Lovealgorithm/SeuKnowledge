package com.ai.konwledgerepo.entity;

/**
 * 问答意图路由结果（LLM 输出归一化后使用）。
 */
public enum Intent {

    BUSINESS,
    CHITCHAT;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }
}
