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

    /** txt / md / html / pdf / docx / pptx / xlsx / xls（见 DocumentParserService 的分发 switch） */
    @Column(name = "file_type", nullable = false, length = 10)
    private String fileType;

    @Column(name = "file_path", length = 500)
    private String filePath;

    /**
     * 文件所在存储后端：{@code local} / {@code minio}。
     * <p>
     * 改造前入库的历史行为 NULL —— 一律按 {@code local} 并用 {@link #filePath} 解释，
     * 因此存量数据零迁移即可继续解析/删除。
     */
    @Column(name = "storage_type", length = 20)
    private String storageType;

    /**
     * 与后端无关的逻辑对象键：原始文件 {@code {wsId}/{kbId}/raw/{ext}/{uuid}_{原始名}.{ext}}。
     * MinIO 行靠它定位对象；local 行同时也会把真实路径写进 {@link #filePath}（兼容旧工具与旧读法）。
     * <p>
     * 落库后即权威：读取与删除都按此列路由，不再由 kbId 反推，因此 key 布局调整不影响存量行。
     * （LlamaParse 产物 md 的镜像键由 {@code DocumentBlobService.mdKey} 单独推导，没有对应列。）
     */
    @Column(name = "object_key", length = 512)
    private String objectKey;

    /** 存储后端常量（见 {@code service.storage.FileStorage}） */
    public static final String STORAGE_LOCAL = "local";
    public static final String STORAGE_MINIO = "minio";

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 解析状态（取值见 {@link DocStatus}，共 5 态）：
     * PENDING 待解析 / PARSING 解析中 / SUCCESS 解析成功（含向量化进行中）/
     * FAILED 解析失败（含 LlamaParse、识图、清空无有效块等）/ ERROR 向量化失败（解析成功但 embedding 未完成，可「重建向量」原地修复）。
     * 注意别按 4 态写判断——漏掉 ERROR 会让「向量失败后重建」的复位逻辑失效。
     */
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
     * 是否走人工初洗门（上传时勾选"解析后人工确认分段"，持久化）。
     * true：解析分块完成后停在初洗（curateStatus=PREVIEWING），人工决断后才向量化；
     * false：照旧自动分块+向量化。retry 后仍按此值走门。
     */
    @Column(name = "curate_required")
    private Boolean curateRequired = false;

    /**
     * 初洗/精修流转状态（仅在 curateRequired=true 时有意义，其余为 null）：
     * PREVIEWING 初洗中（分块完成，chunk 只读，可编辑 md 重分块/接受）；
     * ACCEPTED 精修中（后悔通道关闭，chunk 可编辑/合并/保留，确认后统一向量化）。
     */
    @Column(name = "curate_status", length = 20)
    private String curateStatus;

    /** 初洗/精修状态常量 */
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

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
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

    /** 是否处于初洗/精修流程中（PREVIEWING 或 ACCEPTED） */
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
