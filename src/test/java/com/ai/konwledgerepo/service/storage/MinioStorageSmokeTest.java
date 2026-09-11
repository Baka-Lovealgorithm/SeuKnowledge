package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.config.props.SeuStorageProperties;
import com.ai.konwledgerepo.entity.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinIO 后端真机冒烟（默认跳过，需显式开启）：
 * <pre>
 * .\mvnw.cmd test -Dminio.smoke=true -Dtest=MinioStorageSmokeTest -DfailIfNoSpecifiedTests=false
 * </pre>
 * 前置：本机 9000 端口有 MinIO（{@code docker run -p 9000:9000 minio/minio server /data}），账号密码取
 * {@code KB_MINIO_ACCESS_KEY} / {@code KB_MINIO_SECRET_KEY}（开发默认 minioadmin）。
 * <p>
 * 之所以单独一个「需显式开启」的用例：{@link MinioFileStorage} 是默认后端，但正常单测走 local，
 * CI 环境也未必有 MinIO——写成常跑用例会变成随机红灯。这里覆盖的是「写 → 读 → 物化 → 覆盖 → 删除」
 * 这条在单测里完全没被执行过的真实链路。
 */
@EnabledIfSystemProperty(named = "minio.smoke", matches = "true")
class MinioStorageSmokeTest {

    private static final String ENDPOINT = env("KB_MINIO_ENDPOINT", "http://localhost:9000");
    private static final String ACCESS_KEY = env("KB_MINIO_ACCESS_KEY", "minioadmin");
    private static final String SECRET_KEY = env("KB_MINIO_SECRET_KEY", "minioadmin");
    private static final String BUCKET = env("KB_MINIO_BUCKET", "seu-knowledge");

    private static SeuStorageProperties props() {
        return new SeuStorageProperties(FileStorage.MINIO, "",
                new SeuStorageProperties.Minio(ENDPOINT, ACCESS_KEY, SECRET_KEY, BUCKET, true));
    }

    /** 与 application.yml 同口径：环境变量优先，缺失回落开发默认值。 */
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Test
    void putGetMaterializeOverwriteDelete() throws Exception {
        MinioFileStorage storage = new MinioFileStorage(props());
        storage.ensureReady();

        String key = "__smoke__/" + System.nanoTime() + "/sample.txt";
        // 1) 写入 + exists
        byte[] payload = "hello minio".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(payload), payload.length, "text/plain");
        assertTrue(storage.exists(key), "put 后对象应存在");

        // 2) 读回内容一致
        try (var in = storage.get(key)) {
            assertEquals("hello minio", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        // 3) 物化：临时文件带原扩展名，close 后自动清理
        Path materialized;
        try (MaterializedFile mf = storage.materialize(key, "sample.txt")) {
            assertTrue(mf.isTemporary(), "对象存储物化出来的必须是临时副本");
            materialized = mf.path();
            assertTrue(Files.exists(materialized), "物化期间临时文件应存在");
            assertEquals("hello minio", Files.readString(materialized));
        }
        assertFalse(Files.exists(materialized), "close 后临时文件应被删除");

        // 4) 覆盖同一 key = 最新版（md 语义），且对象存储不产生版本行
        storage.putString(key, "v2-latest");
        try (var in = storage.get(key)) {
            assertEquals("v2-latest", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        // 5) 删除幂等
        storage.delete(key);
        assertFalse(storage.exists(key), "delete 后对象应不存在");
        storage.delete(key); // 再删一次不应抛异常

        assertNull(storage.localPathOrNull(key), "对象存储后端不应回吐本地路径");
    }

    @Test
    void blobService_routesReadByRow() throws Exception {
        FileStorageRouter router = new FileStorageRouter(
                List.of(new MinioFileStorage(props())), props());
        router.active().ensureReady();
        DocumentBlobService blobService = new DocumentBlobService(router);

        // 模拟一次上传：写按配置（minio），落库拿到 storageType=minio / objectKey / filePath=null
        long kbId = 999_999_001L;
        String storedName = "__smoke__-" + System.nanoTime() + ".txt";
        DocumentBlobService.StoredOriginal stored;
        try (var in = new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))) {
            stored = blobService.putOriginal(kbId, storedName, "text/plain", in, 7L);
        }
        assertEquals(FileStorage.MINIO, stored.storageType());
        assertEquals(kbId + "/" + storedName, stored.objectKey());
        assertNull(stored.localPath(), "minio 行 file_path 应为 null");

        // 模拟解析：读走行上的 storage_type，物化出临时文件供 PDFBox/POI 类的解析器使用
        Document doc = new Document();
        doc.setId(999_999_001L);
        doc.setKbId(kbId);
        doc.setFileName(storedName);
        doc.setStorageType(stored.storageType());
        doc.setObjectKey(stored.objectKey());
        doc.setFilePath(stored.localPath());
        try (MaterializedFile mf = blobService.materializeOriginal(doc)) {
            assertEquals("payload", Files.readString(mf.path()));
        }

        // md 镜像：同一 docId 重复写 → 覆盖同一对象
        blobService.putMdStrict(doc.getId(), "# v1");
        blobService.putMdStrict(doc.getId(), "# v2 最新");
        try (var in = router.forRow(FileStorage.MINIO)
                .get(DocumentBlobService.mdKey(doc.getId()))) {
            assertEquals("# v2 最新", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        // 收尾：删除原始对象 + md 对象，不留垃圾
        blobService.deleteOriginal(doc);
        blobService.deleteMd(doc);
        assertFalse(router.forRow(FileStorage.MINIO).exists(stored.objectKey()));
        assertFalse(router.forRow(FileStorage.MINIO).exists(DocumentBlobService.mdKey(doc.getId())));
    }
}
