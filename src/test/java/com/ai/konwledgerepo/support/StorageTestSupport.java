package com.ai.konwledgerepo.support;

import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.config.props.SeuStorageProperties;
import com.ai.konwledgerepo.service.storage.DocumentBlobService;
import com.ai.konwledgerepo.service.storage.FileStorage;
import com.ai.konwledgerepo.service.storage.FileStorageRouter;
import com.ai.konwledgerepo.service.storage.LocalFileStorage;

import java.nio.file.Path;
import java.util.List;

/**
 * 单测用的存储装配辅助。
 * <p>
 * 纯单元测试不启动 Spring，也就拿不到 {@code StorageConfig} 里条件装配的 bean；
 * 而 {@link DocumentBlobService} 是业务类的构造器依赖，所以这里手搓一个
 * 「只有 local 后端」的实例——这样测试既不必连 MinIO，也不会因为本机没起 MinIO 而失败。
 */
public final class StorageTestSupport {

    private StorageTestSupport() {
    }

    /** 本地后端 + 默认测试目录（java.io.tmpdir/seuknowledge-test-files）。 */
    public static DocumentBlobService localBlobService() {
        return localBlobService(Path.of(System.getProperty("java.io.tmpdir"), "seuknowledge-test-files"));
    }

    /** 本地后端 + 指定存储根目录（通常传 {@code @TempDir}，保证测试之间互不干扰）。 */
    public static DocumentBlobService localBlobService(Path storageDir) {
        return new DocumentBlobService(localRouter(storageDir));
    }

    public static FileStorageRouter localRouter(Path storageDir) {
        SeuFileProperties fileProps = new SeuFileProperties(storageDir.toString(), 20L * 1024 * 1024);
        return new FileStorageRouter(
                List.of(new LocalFileStorage(fileProps)),
                new SeuStorageProperties(FileStorage.LOCAL, "", null));
    }
}
