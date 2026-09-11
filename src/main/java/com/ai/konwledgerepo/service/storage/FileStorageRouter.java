package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuStorageProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储后端路由：把「写」与「读」分开决策。
 * <ul>
 *   <li><b>写</b>（{@link #active()}）：只看配置 {@code seuknowledge.storage.type}。配置指向的后端
 *       未装配则直接报错——<b>不做自动降级</b>，避免数据在两种后端之间分裂。</li>
 *   <li><b>读</b>（{@link #forRow(String)}）：看文档行自己的 {@code kb_document.storage_type}。
 *       {@code null}/空白一律按 {@code local} 解释（存量行兼容），因此 MinIO 部署下依然能读旧文件；
 *       反过来本地部署下遇到 minio 行会明确报「后端未启用」，而不是静默失败。</li>
 * </ul>
 */
@Component
public class FileStorageRouter {

    private final Map<String, FileStorage> byType;
    private final String activeType;

    public FileStorageRouter(List<FileStorage> storages, SeuStorageProperties props) {
        Map<String, FileStorage> map = new LinkedHashMap<>();
        for (FileStorage storage : storages) {
            map.put(storage.type(), storage);
        }
        this.byType = Map.copyOf(map);
        String configured = props.type();
        this.activeType = (configured == null || configured.isBlank())
                ? FileStorage.MINIO
                : configured.trim().toLowerCase();
    }

    /** 写入路由：由配置决定的目标后端。 */
    public FileStorage active() {
        FileStorage storage = byType.get(activeType);
        if (storage == null) {
            throw new BizException("存储后端未启用: " + activeType
                    + "（当前已装配: " + byType.keySet() + "；请检查 seuknowledge.storage.type）");
        }
        return storage;
    }

    /** 读取路由：由文档行记录的 storage_type 决定；{@code null}/空白视作 local（存量行）。 */
    public FileStorage forRow(String storageType) {
        String type = (storageType == null || storageType.isBlank())
                ? FileStorage.LOCAL
                : storageType.trim().toLowerCase();
        FileStorage storage = byType.get(type);
        if (storage == null) {
            throw new BizException("文档所用存储后端未启用: " + type
                    + "（当前已装配: " + byType.keySet() + "；请在对应后端下操作该文档）");
        }
        return storage;
    }
}
