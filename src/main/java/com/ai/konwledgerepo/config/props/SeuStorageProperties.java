package com.ai.konwledgerepo.config.props;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * seuknowledge.storage.* 配置（文件物理存储后端）。
 * <p>
 * 语义：{@code type} 决定「写」走哪个后端——{@code minio}（默认）或 {@code local}；
 * 两者都由 {@code config/StorageConfig} 条件装配，不做运行时自动降级（MinIO 不可用即抛错）。
 * 「读」不看本配置，而是按 {@code kb_document.storage_type} 逐行路由，以兼容存量本地行。
 * <p>注意：record 必须保持单一 canonical 构造器（Spring Boot 对 @ConfigurationProperties
 * record 依赖 canonical 构造器做构造器绑定；额外构造器会使绑定退化为无参构造而失败）。
 */
@ConfigurationProperties(prefix = "seuknowledge.storage")
public record SeuStorageProperties(
        @DefaultValue("minio") String type,
        @DefaultValue("") String tempDir,
        Minio minio) {

    /**
     * seuknowledge.storage.minio.* 配置。凭证默认 minioadmin/minioadmin 仅服务本地开发，
     * 生产必须以环境变量覆盖。
     */
    public record Minio(
            @DefaultValue("http://localhost:9000") String endpoint,
            @DefaultValue("minioadmin") String accessKey,
            @DefaultValue("minioadmin") String secretKey,
            @DefaultValue("seu-knowledge") String bucket,
            @DefaultValue("true") boolean autoCreateBucket) {
    }
}
