package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 文档分块元数据（chunk 内容/页码存 MySQL，向量存 ES，esId 关联 ES 文档）。
 */
@Entity
@Table(name = "kb_chunk")
public class Chunk extends BaseEntity {

    /** ES 文档 _id（esId 为空表示尚未写入 ES） */
    @Column(name = "es_id", length = 64)
    private String esId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 块序号 */
    @Column
    private Integer seq;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** PDF 页码；txt/md 为 0 */
    @Column(name = "page_num")
    private Integer pageNum = 0;

    /** 所属小节标题（标题感知分块产物；无标题文档为空） */
    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "token_count")
    private Integer tokenCount;

    /** 状态：EMBEDDING / INDEXED / FAILED / FILTERED（清洗丢弃） */
    @Column(nullable = false, length = 20)
    private String status = ChunkStatus.EMBEDDING.value();

    /** 清洗状态（P1 规则清洗）：null=未清洗 / KEEP=保留 / SUSPECT=可疑待人工（照常入库）/ FILTERED=丢弃 */
    @Column(name = "clean_status", length = 20)
    private String cleanStatus;

    /** 清洗原因（规则 ID + 说明，如 "C2 近重复（3-gram Jaccard≥0.9）"） */
    @Column(name = "clean_reason", length = 500)
    private String cleanReason;

    public String getEsId() {
        return esId;
    }

    public void setEsId(String esId) {
        this.esId = esId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCleanStatus() {
        return cleanStatus;
    }

    public void setCleanStatus(String cleanStatus) {
        this.cleanStatus = cleanStatus;
    }

    public String getCleanReason() {
        return cleanReason;
    }

    public void setCleanReason(String cleanReason) {
        this.cleanReason = cleanReason;
    }
}
