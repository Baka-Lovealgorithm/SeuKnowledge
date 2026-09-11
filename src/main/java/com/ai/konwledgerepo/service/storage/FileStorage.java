package com.ai.konwledgerepo.service.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件物理存储抽象。两个实现：
 * <ul>
 *   <li>{@link LocalFileStorage}（{@code local}）—— 落本地磁盘，复用 {@code seuknowledge.file.storage-path}</li>
 *   <li>{@link MinioFileStorage}（{@code minio}）—— 落 MinIO 对象存储，默认后端</li>
 * </ul>
 * <p>
 * <b>不降级约定</b>：任何方法失败都必须直接抛出 {@code BizException}，
 * 实现里严禁 catch 之后退化为落本地磁盘——否则同一批文档会一半在对象存储、一半在本地，
 * 且调用方无从察觉。后端选择只由配置决定（{@code seuknowledge.storage.type}）。
 * <p>
 * {@code objectKey} 是与后端无关的逻辑键，两端统一口径（由 {@link DocumentBlobService} 生成，业务侧不自己拼）：
 * 原始文件 {@code {wsId}/{kbId}/raw/{ext}/{uuid}_{原始名}.{ext}}，
 * 解析产物 md {@code {wsId}/{kbId}/derived/md/{docId}.md}。
 */
public interface FileStorage {

    String LOCAL = "local";
    String MINIO = "minio";

    /** 本实现对应的存储后端标识，取值 {@link #LOCAL} / {@link #MINIO}。 */
    String type();

    /**
     * 写入对象（不存在则创建，存在则覆盖）。
     *
     * @param contentLength 内容长度；已知时传实际字节数，未知传负数
     * @param contentType   MIME 类型，可空
     */
    void put(String objectKey, InputStream in, long contentLength, String contentType);

    /** 写入文本对象（UTF-8），md 镜像用。 */
    default void putString(String objectKey, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        put(objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/markdown; charset=utf-8");
    }

    /** 打开对象读取流，调用方负责关闭。 */
    InputStream get(String objectKey);

    boolean exists(String objectKey);

    /** 删除对象；对象不存在时静默成功（幂等）。 */
    void delete(String objectKey);

    /**
     * 物化成本机文件供解析器使用，返回值必须 try-with-resources 关闭。
     *
     * @param fileNameHint 原文件名，仅用于推导临时文件扩展名（部分解析器按内容嗅探，扩展名非必需）
     */
    MaterializedFile materialize(String objectKey, String fileNameHint);

    /**
     * 本后端下该 key 对应的本地绝对路径；对象存储后端恒返回 {@code null}。
     * 用于在 local 模式下继续把真实路径写进 {@code kb_document.file_path}（兼容旧工具与存量读法）。
     */
    default String localPathOrNull(String objectKey) {
        return null;
    }

    /**
     * 启动期自检钩子（默认什么都不做）。MinIO 实现用于校验/创建 bucket，
     * 由 {@code InfraStartupCheck} 调用；失败即抛出，交由现有 fail-fast 开关决定是否阻断启动。
     */
    default void ensureReady() {
    }
}
