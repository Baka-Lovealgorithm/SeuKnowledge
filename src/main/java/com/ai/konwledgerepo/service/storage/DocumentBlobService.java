package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.entity.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 文档二进制的统一门面：业务侧（上传、解析、删除、md 镜像）只依赖本类，
 * 不直接接触 {@link FileStorage} 与 key 拼装规则。
 * <p>
 * 与 {@link FileStorageRouter} 的分工：<b>写走配置、读走行</b>。
 * 具体地——上传与 md 镜像写入 {@code router.active()}；读取与删除按 {@code doc.storageType} 路由，
 * 以便向后兼容存量本地行。
 * <p>
 * 路径口径：本地行以 {@code kb_document.file_path} 为准（绝对路径或相对工作目录的旧路径），
 * MinIO 行以 {@code kb_document.object_key} 为准。
 */
@Service
public class DocumentBlobService {

    private static final Logger log = LoggerFactory.getLogger(DocumentBlobService.class);

    private final FileStorageRouter router;

    public DocumentBlobService(FileStorageRouter router) {
        this.router = router;
    }

    /** 原始文件的 objectKey：{@code {kbId}/{storedName}}。沿用改造前的目录布局，本地落盘位置不变。 */
    public static String originalKey(long kbId, String storedName) {
        return kbId + "/" + storedName;
    }

    /** LlamaParse 产物 md 的 objectKey：{@code md/{docId}.md}。docId 恒定 → 覆盖即最新版。 */
    public static String mdKey(long docId) {
        return "md/" + docId + ".md";
    }

    /** 原始文件的落库结果。 */
    public record StoredOriginal(String storageType, String objectKey, String localPath) {
    }

    /**
     * 写入原始上传文件。写入目标由配置决定；失败直接抛出（不降级）。
     *
     * @param localPath 仅 local 后端非空，供回填 {@code kb_document.file_path}
     */
    public StoredOriginal putOriginal(long kbId, String storedName, String contentType,
                                      InputStream in, long size) {
        FileStorage storage = router.active();
        String objectKey = originalKey(kbId, storedName);
        storage.put(objectKey, in, size, contentType);
        return new StoredOriginal(storage.type(), objectKey, storage.localPathOrNull(objectKey));
    }

    /** 按行路由把原始文件物化成本机文件；返回值必须 try-with-resources 关闭。 */
    public MaterializedFile materializeOriginal(Document doc) {
        String type = doc.getStorageType();
        if (isLocal(type)) {
            String key = firstNonBlank(doc.getFilePath(), doc.getObjectKey());
            if (key == null) {
                throw new BizException("文档缺少本地文件路径: docId=" + doc.getId());
            }
            return router.forRow(FileStorage.LOCAL).materialize(key, doc.getFileName());
        }
        String objectKey = doc.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            throw new BizException("文档缺少对象键 object_key: docId=" + doc.getId());
        }
        return router.forRow(type).materialize(objectKey, doc.getFileName());
    }

    /** 删除原始文件；key 缺失时静默跳过（例如历史异常数据）。 */
    public void deleteOriginal(Document doc) {
        String type = doc.getStorageType();
        if (isLocal(type)) {
            String key = firstNonBlank(doc.getFilePath(), doc.getObjectKey());
            if (key != null) {
                router.forRow(FileStorage.LOCAL).delete(key);
            }
            return;
        }
        if (doc.getObjectKey() != null && !doc.getObjectKey().isBlank()) {
            router.forRow(type).delete(doc.getObjectKey());
        }
    }

    /**
     * 删除该文档的 md 对象。按行路由（md 与原始文件由同一后端写入，行上的 storage_type 即其归属）；
     * md 可能本就不存在（txt/md/xlsx 等无 md 产物，或改造前的存量行），删除实现是幂等的。
     */
    public void deleteMd(Document doc) {
        router.forRow(doc.getStorageType()).delete(mdKey(doc.getId()));
    }

    /**
     * 写入/覆盖 md 对象——<b>严格模式</b>：失败即抛出，用于用户主动保存 md 的场景
     * （保存失败必须让用户看见并回滚，而不是静默把对象存储留成旧版本）。
     */
    public void putMdStrict(Long docId, String mdText) {
        if (mdText == null || mdText.isBlank()) {
            return;
        }
        router.active().putString(mdKey(docId), mdText);
    }

    /**
     * 写入/覆盖 md 对象——<b>宽松模式</b>：失败仅 WARN，用于解析链路。
     * 理由：{@code kb_document_curate} 才是 md 的权威副本，对象存储只是镜像；
     * MinIO 抖动不该把整篇文档判成解析失败。
     */
    public void putMdQuietly(Long docId, String mdText) {
        try {
            putMdStrict(docId, mdText);
        } catch (RuntimeException e) {
            log.warn("md 对象镜像写入失败（不影响主流程，DB 仍为权威）docId={}: {}", docId, e.getMessage());
        }
    }

    /**
     * 尽力删除「刚写入但业务未落库」的对象（孤儿清理）。
     * <p>
     * 只用于清理本次写入 {@code router.active()} 的产物，因此按写路由定位；任何失败仅 WARN——
     * 清理由失败绝不能掩盖原始异常（例如 DB 字段超长才是真正要抛给用户的原因）。
     */
    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            router.active().delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("清理孤儿对象失败 {}: {}", objectKey, e.getMessage());
        }
    }

    private static boolean isLocal(String storageType) {
        return storageType == null || storageType.isBlank()
                || FileStorage.LOCAL.equalsIgnoreCase(storageType.trim());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
