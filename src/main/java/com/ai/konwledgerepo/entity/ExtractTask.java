package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * AI 抽取任务。
 */
@Entity
@Table(name = "kb_extract_task")
public class ExtractTask extends BaseEntity {

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 目标文档 id JSON 数组 */
    @Column(name = "doc_ids", columnDefinition = "TEXT")
    private String docIds;

    /** 抽取类型：BUSINESS / QA / BOTH */
    @Column(name = "extract_type", nullable = false, length = 20)
    private String extractType;

    /** PENDING / RUNNING / SUCCESS / PARTIAL_FAILED / FAILED */
    @Column(nullable = false, length = 20)
    private String status = TaskStatus.PENDING.value();

    @Column(name = "total_docs")
    private Integer totalDocs = 0;

    @Column(name = "processed_docs")
    private Integer processedDocs = 0;

    /** 进度 0-100 */
    @Column(nullable = false)
    private Integer progress = 0;

    /** 结果统计：新增业务知识 X 条 / 问答对 Y 条 */
    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    /** 失败日志 */
    @Column(name = "error_log", columnDefinition = "TEXT")
    private String errorLog;

    /** 本轮执行失败的文档 id JSON 数组（重试时仅重抽这些文档） */
    @Column(name = "failed_doc_ids", columnDefinition = "TEXT")
    private String failedDocIds;

    /** 执行耗时（毫秒） */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** LLM 输入 token 总量（抽取任务统计） */
    @Column(name = "token_input")
    private Long tokenInput;

    /** LLM 输出 token 总量（抽取任务统计） */
    @Column(name = "token_output")
    private Long tokenOutput;

    /** LLM 总 token（抽取任务统计） */
    @Column(name = "token_total")
    private Long tokenTotal;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getDocIds() {
        return docIds;
    }

    public void setDocIds(String docIds) {
        this.docIds = docIds;
    }

    public String getExtractType() {
        return extractType;
    }

    public void setExtractType(String extractType) {
        this.extractType = extractType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalDocs() {
        return totalDocs;
    }

    public void setTotalDocs(Integer totalDocs) {
        this.totalDocs = totalDocs;
    }

    public Integer getProcessedDocs() {
        return processedDocs;
    }

    public void setProcessedDocs(Integer processedDocs) {
        this.processedDocs = processedDocs;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public String getErrorLog() {
        return errorLog;
    }

    public void setErrorLog(String errorLog) {
        this.errorLog = errorLog;
    }

    public String getFailedDocIds() {
        return failedDocIds;
    }

    public void setFailedDocIds(String failedDocIds) {
        this.failedDocIds = failedDocIds;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Long getTokenInput() {
        return tokenInput;
    }

    public void setTokenInput(Long tokenInput) {
        this.tokenInput = tokenInput;
    }

    public Long getTokenOutput() {
        return tokenOutput;
    }

    public void setTokenOutput(Long tokenOutput) {
        this.tokenOutput = tokenOutput;
    }

    public Long getTokenTotal() {
        return tokenTotal;
    }

    public void setTokenTotal(Long tokenTotal) {
        this.tokenTotal = tokenTotal;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
