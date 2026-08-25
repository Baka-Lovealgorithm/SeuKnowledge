package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 业务知识（专有名词解释、规则、实体关系、注意事项等）。
 *
 * 版本回退：同 historyGroupId 共享版本组，编辑生成新版本（旧版软删保留）。
 */
@Entity
@Table(name = "kb_business_knowledge")
public class BusinessKnowledge extends BaseEntity {

    /** 版本组 id（首次创建生成，编辑新增同组版本） */
    @Column(name = "history_group_id", length = 64)
    private String historyGroupId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 术语（专有名词） */
    @Column(nullable = false, length = 255)
    private String term;

    /** 别名 JSON 数组 */
    @Column(columnDefinition = "TEXT")
    private String aliases;

    @Column(columnDefinition = "TEXT")
    private String definition;

    /** 适用范围 */
    @Column(columnDefinition = "TEXT")
    private String scope;

    @Column(columnDefinition = "TEXT")
    private String example;

    /** 禁用规则 */
    @Column(name = "prohibited_rules", columnDefinition = "TEXT")
    private String prohibitedRules;

    @Column(name = "source_doc_id")
    private Long sourceDocId;

    @Column(name = "source_doc_name", length = 255)
    private String sourceDocName;

    /** DRAFT / APPROVED / REJECTED */
    @Column(nullable = false, length = 20)
    private String status = ReviewStatus.DRAFT.value();

    @Column(nullable = false)
    private Integer version = 1;

    /** 软删标记（版本历史保留旧版） */
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

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getAliases() {
        return aliases;
    }

    public void setAliases(String aliases) {
        this.aliases = aliases;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getExample() {
        return example;
    }

    public void setExample(String example) {
        this.example = example;
    }

    public String getProhibitedRules() {
        return prohibitedRules;
    }

    public void setProhibitedRules(String prohibitedRules) {
        this.prohibitedRules = prohibitedRules;
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
