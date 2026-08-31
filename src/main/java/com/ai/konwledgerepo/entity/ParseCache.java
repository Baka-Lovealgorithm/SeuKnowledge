package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * LlamaParse 解析结果缓存（文件级）。
 * <p>
 * 以文件 SHA-256 为键缓存云端解析产物（逐页 markdown），同内容文件重复上传且用户选择
 * "复用解析结果"时跳过 LlamaParse 网络调用，直接复用缓存页面序列走本地分块。
 * 仅覆盖 PDF/DOCX 路径（needScreenshots=false 无截图，页面序列可无损持久化）。
 */
@Entity
@Table(name = "kb_parse_cache")
public class ParseCache extends BaseEntity {

    /** 文件 SHA-256（内容寻址，全局唯一） */
    @Column(name = "file_hash", nullable = false, unique = true, length = 64)
    private String fileHash;

    /** 原始文件名（展示用） */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文件类型（pdf / docx） */
    @Column(name = "file_type", nullable = false, length = 10)
    private String fileType;

    /** 页面数 */
    @Column(name = "page_count")
    private Integer pageCount;

    /** 逐页 markdown 序列化：[{pageNumber, markdown}] */
    @Column(name = "pages_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pagesJson;

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public String getPagesJson() {
        return pagesJson;
    }

    public void setPagesJson(String pagesJson) {
        this.pagesJson = pagesJson;
    }
}
