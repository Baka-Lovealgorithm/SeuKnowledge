package com.ai.konwledgerepo.support;

import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.config.props.SeuStorageProperties;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import com.ai.konwledgerepo.service.storage.DocumentBlobService;
import com.ai.konwledgerepo.service.storage.FileStorage;
import com.ai.konwledgerepo.service.storage.FileStorageRouter;
import com.ai.konwledgerepo.service.storage.LocalFileStorage;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单测用的存储装配辅助。
 * <p>
 * 纯单元测试不启动 Spring，也就拿不到 {@code StorageConfig} 里条件装配的 bean；
 * 而 {@link DocumentBlobService} 是业务类的构造器依赖，所以这里手搓一个
 * 「只有 local 后端」的实例——这样测试既不必连 MinIO，也不会因为本机没起 MinIO 而失败。
 */
public final class StorageTestSupport {

    /** 单测里固定使用的工作空间 id：object key 的 {@code {wsId}} 段，测试不关心具体值。 */
    public static final long TEST_WORKSPACE_ID = 1L;

    private StorageTestSupport() {
    }

    /** 本地后端 + 默认测试目录（java.io.tmpdir/seuknowledge-test-files）。 */
    public static DocumentBlobService localBlobService() {
        return localBlobService(Path.of(System.getProperty("java.io.tmpdir"), "seuknowledge-test-files"));
    }

    /** 本地后端 + 指定存储根目录（通常传 {@code @TempDir}，保证测试之间互不干扰）。 */
    public static DocumentBlobService localBlobService(Path storageDir) {
        return new DocumentBlobService(localRouter(storageDir), fixedWorkspaceResolver());
    }

    public static FileStorageRouter localRouter(Path storageDir) {
        SeuFileProperties fileProps = new SeuFileProperties(storageDir.toString(), 20L * 1024 * 1024);
        return new FileStorageRouter(
                List.of(new LocalFileStorage(fileProps)),
                new SeuStorageProperties(FileStorage.LOCAL, "", null));
    }

    /**
     * object key 里的 {@code {wsId}} 段由 {@link WorkspaceIdResolver} 从知识库解析，而单测既不装配 Spring
     * 也没有知识库表，故固定返回常量。用 mock 而非手搓子类：前者不受解析器构造器变化影响。
     */
    public static WorkspaceIdResolver fixedWorkspaceResolver() {
        WorkspaceIdResolver resolver = mock(WorkspaceIdResolver.class);
        when(resolver.resolve(any())).thenReturn(TEST_WORKSPACE_ID);
        return resolver;
    }
}
