package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 问答对（一问一答样本，作为 AI 回答样本）。
 */
@Entity
@Table(name = "kb_qa_pair")
public class QaPair extends BaseEntity {

    /** 版本组 id（编辑生成新版本，旧版软删保留） */
    @Column(name = "history_group_id", length = 64)
    private String historyGroupId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 问题归一化后的标准问法 */
    @Column(name = "normalized_question", columnDefinition = "TEXT")
    private String normalizedQuestion;

    /** 同义问法 JSON 数组 */
    @Column(columnDefinition = "TEXT")
    private String synonyms;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "source_doc_id")
    private Long sourceDocId;

    @Column(name = "source_doc_name", length = 255)
    private String sourceDocName;

    /** DRAFT / APPROVED / REJECTED / DISABLED */
    @Column(nullable = false, length = 20)
    private String status = ReviewStatus.DRAFT.value();

    @Column(nullable = false)
    private Integer version = 1;

    @Column(nullable = false)
    private Boolean deleted = false;

    public String getHistoryGroupId() {
        return historyGroupId;
    }

    public void setHistoryGroupId(String historyGroupId) {
        this.historyGroupId = historyGroupId;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getNormalizedQuestion() {
        return normalizedQuestion;
    }

    public void setNormalizedQuestion(String normalizedQuestion) {
        this.normalizedQuestion = normalizedQuestion;
    }

    public String getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(String synonyms) {
        this.synonyms = synonyms;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Long getSourceDocId() {
        return sourceDocId;
    }

    public void setSourceDocId(Long sourceDocId) {
        this.sourceDocId = sourceDocId;
    }

    public String getSourceDocName() {
        return sourceDocName;
    }

    public void setSourceDocName(String sourceDocName) {
        this.sourceDocName = sourceDocName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
