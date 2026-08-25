package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 问答 Agent 配置。与知识库一一绑定（kbId 唯一），保存提示词与检索/记忆/生成参数，
 * 问答状态图各节点在执行时读取本配置，实现"动态修改提示词、检索策略、记忆策略"。
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

    /** 检索策略：每个候选查询的召回条数 */
    @Column(name = "top_k")
    private Integer topK = 5;

    /** 检索策略：重排后作为答案证据的 Top-N */
    @Column(name = "top_n")
    private Integer topN = 5;

    /** 答案自检置信度阈值（0~1），低于阈值触发重试 */
    @Column(name = "verify_threshold")
    private Double verifyThreshold = 0.7;

    /** 自检重试上限 */
    @Column(name = "max_retry")
    private Integer maxRetry = 2;

    /** 记忆策略：对话记忆窗口条数 */
    @Column(name = "memory_window")
    private Integer memoryWindow = 20;

    /** 多源召回权重：文档 chunk */
    @Column(name = "chunk_weight")
    private Double chunkWeight = 1.0;

    /** 多源召回权重：业务知识 */
    @Column(name = "business_weight")
    private Double businessWeight = 1.2;

    /** 多源召回权重：问答对 */
    @Column(name = "qa_weight")
    private Double qaWeight = 1.2;

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

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Integer getTopN() {
        return topN;
    }

    public void setTopN(Integer topN) {
        this.topN = topN;
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

    public Double getChunkWeight() {
        return chunkWeight;
    }

    public void setChunkWeight(Double chunkWeight) {
        this.chunkWeight = chunkWeight;
    }

    public Double getBusinessWeight() {
        return businessWeight;
    }

    public void setBusinessWeight(Double businessWeight) {
        this.businessWeight = businessWeight;
    }

    public Double getQaWeight() {
        return qaWeight;
    }

    public void setQaWeight(Double qaWeight) {
        this.qaWeight = qaWeight;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
