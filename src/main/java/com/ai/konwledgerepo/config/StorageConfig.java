package com.ai.konwledgerepo.config;

import com.ai.konwledgerepo.config.props.SeuFileProperties;
import com.ai.konwledgerepo.config.props.SeuStorageProperties;
import com.ai.konwledgerepo.service.storage.FileStorage;
import com.ai.konwledgerepo.service.storage.LocalFileStorage;
import com.ai.konwledgerepo.service.storage.MinioFileStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储后端装配。
 * <p>
 * {@code LocalFileStorage} <b>恒定装配</b>：即便主后端是 MinIO，也仍然需要它来读/删
 * 存量本地行（{@code kb_document.storage_type} 为 NULL 或 {@code local} 的历史数据）。
 * <p>
 * {@code MinioFileStorage} 仅在 {@code seuknowledge.storage.type=minio}（或缺省）时装配——
 * 于是本地开发（{@code type=local}）不会因为本机没起 MinIO 而启动失败，
 * 而 MinIO 部署下写入后端由 {@code FileStorageRouter.active()} 按配置选到它。
 * <p>
 * 装配结果是一个 {@code List<FileStorage>}，路由逻辑集中在
 * {@link com.ai.konwledgerepo.service.storage.FileStorageRouter}，本类不含任何 if/else 选择逻辑。
 */
@Configuration
public class StorageConfig {

    @Bean
    public FileStorage localFileStorage(SeuFileProperties props) {
        return new LocalFileStorage(props);
    }

    @Bean
    @ConditionalOnProperty(value = "seuknowledge.storage.type", havingValue = "minio", matchIfMissing = true)
    public FileStorage minioFileStorage(SeuStorageProperties props) {
        return new MinioFileStorage(props);
    }
}
