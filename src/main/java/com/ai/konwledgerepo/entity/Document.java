package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

/**
 * 文档。文件实体落盘，chunk 向量在 ES，元数据在 MySQL。
 */
@Entity
@Table(name = "kb_document")
public class Document extends BaseEntity {

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** txt / md / pdf */
    @Column(name = "file_type", nullable = false, length = 10)
    private String fileType;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "file_size")
    private Long fileSize;

    /** 解析状态：PENDING / PARSING / SUCCESS / FAILED */
    @Column(name = "parse_status", nullable = false, length = 20)
    private String parseStatus = DocStatus.PENDING.value();

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /** 文档版本号，支持版本管理（一期基础） */
    @Column
    @Version
    private Integer version = 1;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * 是否走人工策展门（上传时勾选"解析后人工确认分段"，持久化）。
     * true：解析分块完成后停在展示门（curateStatus=PREVIEWING），人工决断后才向量化；
     * false：照旧自动分块+向量化。retry 后仍按此值走门。
     */
    @Column(name = "curate_required")
    private Boolean curateRequired = false;

    /**
     * 策展流转状态（仅在 curateRequired=true 时有意义，其余为 null）：
     * PREVIEWING 展示门（分块完成，chunk 只读，可编辑 md 重分块/接受）；
     * ACCEPTED 已接受（后悔通道关闭，chunk 可编辑，确认后统一向量化）。
     */
    @Column(name = "curate_status", length = 20)
    private String curateStatus;

    /** 文档策展状态常量 */
    public static final String CURATE_PREVIEWING = "PREVIEWING";
    public static final String CURATE_ACCEPTED = "ACCEPTED";

    /**
     * 本次上传是否复用解析缓存（用户选择，仅本次解析链路生效，不落库）。
     * 由 DocumentService 上传时经 parseAsync 显式设置；startParse 重查实体后由执行器回填。
     */
    @Transient
    private boolean reuseCache;

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
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

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(String parseStatus) {
        this.parseStatus = parseStatus;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Boolean getCurateRequired() {
        return curateRequired;
    }

    public void setCurateRequired(Boolean curateRequired) {
        this.curateRequired = curateRequired;
    }

    public String getCurateStatus() {
        return curateStatus;
    }

    public void setCurateStatus(String curateStatus) {
        this.curateStatus = curateStatus;
    }

    /** 是否处于策展流程中（展示门或已接受待确认） */
    public boolean isCurating() {
        return curateStatus != null && (CURATE_PREVIEWING.equals(curateStatus) || CURATE_ACCEPTED.equals(curateStatus));
    }

    public boolean isReuseCache() {
        return reuseCache;
    }

    public void setReuseCache(boolean reuseCache) {
        this.reuseCache = reuseCache;
    }
}
