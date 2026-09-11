package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * MinIO 对象存储后端（默认后端）。
 * <p>
 * 只负责「读写对象」；bucket 的校验/创建放在 {@link #ensureReady()}，由
 * {@code InfraStartupCheck} 在启动期统一调用，从而复用项目已有的
 * {@code seuknowledge.infra-check.enabled} / {@code fail-fast} 开关，而不是各自为政地
 * 在构造期探测。
 * <p>
 * <b>明确不启用 bucket 版本控制</b>：md 的历史版本由 {@code kb_document_curate} 的
 * append-only 版本行承载，对象存储里同 key 覆盖即最新版。
 */
public class MinioFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorage.class);

    /** 长度未知时的分片大小（MinIO 要求 ≥ 5MiB）。 */
    private static final long UNKNOWN_LENGTH_PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient client;
    private final String bucket;
    private final boolean autoCreateBucket;
    private final Path tempRoot;

    public MinioFileStorage(SeuStorageProperties props) {
        SeuStorageProperties.Minio cfg = props.minio();
        if (cfg == null) {
            throw new BizException("缺少 MinIO 配置（seuknowledge.storage.minio.*）");
        }
        if (cfg.endpoint() == null || cfg.endpoint().isBlank()) {
            throw new BizException("缺少 MinIO endpoint（seuknowledge.storage.minio.endpoint）");
        }
        this.client = MinioClient.builder()
                .endpoint(cfg.endpoint())
                .credentials(cfg.accessKey(), cfg.secretKey())
                .build();
        this.bucket = cfg.bucket();
        this.autoCreateBucket = cfg.autoCreateBucket();
        String tempDir = props.tempDir();
        this.tempRoot = (tempDir == null || tempDir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "seuknowledge")
                : Path.of(tempDir);
    }

    @Override
    public String type() {
        return MINIO;
    }

    @Override
    public void put(String objectKey, InputStream in, long contentLength, String contentType) {
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, contentLength, contentLength < 0 ? UNKNOWN_LENGTH_PART_SIZE : -1);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            client.putObject(builder.build());
        } catch (Exception e) {
            throw new BizException("对象存储写入失败 [" + bucket + "/" + objectKey + "]: " + e.getMessage());
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return response;
        } catch (Exception e) {
            throw new BizException("对象存储读取失败 [" + bucket + "/" + objectKey + "]: " + e.getMessage());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (ErrorResponseException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw new BizException("对象存储状态查询失败 [" + bucket + "/" + objectKey + "]: " + e.getMessage());
        } catch (Exception e) {
            throw new BizException("对象存储状态查询失败 [" + bucket + "/" + objectKey + "]: " + e.getMessage());
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new BizException("对象存储删除失败 [" + bucket + "/" + objectKey + "]: " + e.getMessage());
        }
    }

    @Override
    public MaterializedFile materialize(String objectKey, String fileNameHint) {
        Path temp = null;
        try {
            Files.createDirectories(tempRoot);
            temp = Files.createTempFile(tempRoot, "kb-", suffix(fileNameHint));
            try (InputStream in = get(objectKey)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return MaterializedFile.temporary(temp);
        } catch (BizException e) {
            cleanupQuietly(temp);
            throw e;
        } catch (IOException e) {
            cleanupQuietly(temp);
            throw new BizException("对象存储文件物化失败 [" + bucket + "/" + objectKey + "]: " + e.getMessage());
        }
    }

    @Override
    public void ensureReady() {
        try {
            if (client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                log.info("MinIO 就绪: bucket={} 已存在", bucket);
                return;
            }
            if (!autoCreateBucket) {
                throw new BizException("MinIO bucket 不存在且未开启自动创建: " + bucket
                        + "（seuknowledge.storage.minio.auto-create-bucket=false）");
            }
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("MinIO bucket 已自动创建: {}（不启用版本控制）", bucket);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("MinIO 不可用或 bucket 校验失败 [" + bucket + "]: " + e.getMessage());
        }
    }

    private static boolean isNotFound(ErrorResponseException e) {
        return e.errorResponse() != null && "NoSuchKey".equals(e.errorResponse().code());
    }

    /** 临时文件后缀：保留原扩展名便于解析器判断，非法则退化为 .tmp。 */
    private static String suffix(String fileNameHint) {
        if (fileNameHint == null) {
            return ".tmp";
        }
        int dot = fileNameHint.lastIndexOf('.');
        if (dot < 0 || dot == fileNameHint.length() - 1) {
            return ".tmp";
        }
        String ext = fileNameHint.substring(dot + 1).replaceAll("[^a-zA-Z0-9]", "");
        return ext.isEmpty() ? ".tmp" : "." + ext.toLowerCase();
    }

    private static void cleanupQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理物化失败的临时文件失败 {}: {}", path, e.getMessage());
        }
    }
}
