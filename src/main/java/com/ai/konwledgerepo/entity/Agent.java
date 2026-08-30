package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 问答 Agent 配置。与知识库一一绑定（kbId 唯一），保存提示词与答案/记忆参数，
 * 问答状态图各节点在执行时读取本配置，实现"动态修改提示词、答案与记忆策略"。
 * 检索/重排条数与来源配额由全局配置（seuknowledge.recall.* / seuknowledge.rerank.*）控制。
 */
@Entity
@Table(name = "kb_agent")
public class Agent extends BaseEntity {

    /** 绑定知识库 id（一个知识库一个默认 Agent） */
    @Column(name = "kb_id", nullable = false, unique = true)
    private Long kbId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 500)
    private String description;

    /** 系统提示词：注入意图路由 / 问题改写 / 答案生成 / 自检等节点 */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    /** 答案自检置信度阈值（0~1），低于阈值触发重试 */
    @Column(name = "verify_threshold")
    private Double verifyThreshold = 0.7;

    /** 自检重试上限 */
    @Column(name = "max_retry")
    private Integer maxRetry = 2;

    /** 记忆策略：对话记忆窗口条数 */
    @Column(name = "memory_window")
    private Integer memoryWindow = 20;

    @Column(name = "created_by")
    private Long createdBy;

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getVerifyThreshold() {
        return verifyThreshold;
    }

    public void setVerifyThreshold(Double verifyThreshold) {
        this.verifyThreshold = verifyThreshold;
    }

    public Integer getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(Integer maxRetry) {
        this.maxRetry = maxRetry;
    }

    public Integer getMemoryWindow() {
        return memoryWindow;
    }

    public void setMemoryWindow(Integer memoryWindow) {
        this.memoryWindow = memoryWindow;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
