package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;

/**
 * 文档二进制的统一门面：业务侧（上传、解析、删除、md 镜像）只依赖本类，
 * 不直接接触 {@link FileStorage} 与 key 拼装规则。
 * <p>
 * 与 {@link FileStorageRouter} 的分工：<b>写走配置、读走行</b>。
 * 具体地——上传与 md 镜像写入 {@code router.active()}；读取与删除按 {@code doc.storageType} 路由，
 * 以便向后兼容存量本地行。
 * <p>
 * 路径口径（本地与对象存储完全一致，只差根目录）：
 * <pre>
 *   {wsId}/{kbId}/raw/{fileType}/{uuid}_{原始主名}.{ext}   原始上传文件，按扩展名分目录
 *   {wsId}/{kbId}/derived/{docId}.md                      解析产物（LlamaParse md 镜像），同键覆盖即最新版
 * </pre>
 * 四条设计取舍：
 * <ul>
 *   <li><b>空间 + 知识库两级前缀</b>：知识库是一棵完整的树，删库/导出/配额都是一个前缀；
 *       wsId 只做归属可读性，不参与唯一性（kbId 本身全局唯一）。</li>
 *   <li><b>raw / derived 分离</b>：原始件与解析产物不再混在一层，避免「哪份是干净的原始件」说不清；
 *       derived 下按产物类型再分层，将来加图片、表格、切片预览不用挪动已有对象。</li>
 *   <li><b>raw 下按扩展名分层</b>：一个库里混投 pdf/docx/xlsx 时，控制台里一眼能看出构成。</li>
 *   <li><b>对象名带原始名</b>：控制台里可辨认。代价是重命名文档后 key 里的名字会过期——
 *       {@code rename} 只改 DB {@code file_name} 与 ES 索引、不搬对象，定位仍以 DB 为准。</li>
 * </ul>
 * 读取侧不做任何 key 推导：{@code object_key} 落库后即权威，因此布局调整不会影响存量行。
 */
@Service
public class DocumentBlobService {

    private static final Logger log = LoggerFactory.getLogger(DocumentBlobService.class);

    /** 目录段：原始上传文件。 */
    static final String SEG_RAW = "raw";
    /** 目录段：解析产物（当前只有 md 镜像）。 */
    static final String SEG_DERIVED = "derived";
    /** 目录段：LlamaParse 逐页 md 的镜像。 */
    static final String SEG_MD_MIRROR = "md";

    /**
     * 对象名里保留的原始主名长度上限（码点计）。
     * 双重约束：Windows 单文件路径 260 字符的 MAX_PATH（本地后端要真落盘），
     * 以及 {@code kb_document.object_key varchar(512)} 的列长。超长部分直接截断——
     * 唯一性由 uuid 前缀保证，截断只影响可读性。
     */
    static final int MAX_NAME_SEGMENT_CODE_POINTS = 60;

    /** 扩展名异常时的兜底目录段（不阻断上传，只是归类到 bin）。 */
    static final String TYPE_FALLBACK = "bin";

    private final FileStorageRouter router;
    private final WorkspaceIdResolver workspaceIdResolver;

    public DocumentBlobService(FileStorageRouter router, WorkspaceIdResolver workspaceIdResolver) {
        this.router = router;
        this.workspaceIdResolver = workspaceIdResolver;
    }

    /** 落点范围：{wsId}/{kbId}。两个 id 都非空才可能拼出合法 key，故用 long 而非 Long。 */
    public record Scope(long workspaceId, long kbId) {
    }

    /** 原始文件的 objectKey：{@code {wsId}/{kbId}/raw/{fileType}/{storedName}}。 */
    public static String originalKey(long workspaceId, long kbId, String fileType, String storedName) {
        return workspaceId + "/" + kbId + "/" + SEG_RAW + "/" + fileType + "/" + storedName;
    }

    /** md 镜像的 objectKey：{@code {wsId}/{kbId}/derived/md/{docId}.md}。docId 恒定 → 覆盖即最新版。 */
    public static String mdKey(long workspaceId, long kbId, long docId) {
        return workspaceId + "/" + kbId + "/" + SEG_DERIVED + "/" + SEG_MD_MIRROR + "/" + docId + ".md";
    }

    /** 原始文件的落库结果。 */
    public record StoredOriginal(String storageType, String objectKey, String localPath) {
    }

    // ==================== 原始文件 ====================

    /**
     * 写入原始上传文件：内部解析落点范围、按「uuid + 安全化的原始主名」生成对象名，再交给
     * {@code router.active()}。失败直接抛出（不降级），并尽力清掉可能写了一半的对象。
     *
     * @param originalFileName 浏览器上传的原始文件名，用于对象名可读性与扩展名归类
     * @param localPath        仅 local 后端非空，供回填 {@code kb_document.file_path}
     */
    public StoredOriginal putOriginal(Long kbId, String originalFileName, String fileType, String contentType,
                                      InputStream in, long size) {
        Scope scope = scopeOrThrow(kbId);
        String type = normalizeFileType(fileType);
        String storedName = storedName(originalFileName, type);
        String objectKey = originalKey(scope.workspaceId(), scope.kbId(), type, storedName);
        FileStorage storage = router.active();
        try {
            storage.put(objectKey, in, size, contentType);
        } catch (RuntimeException e) {
            // 未知长度的分片上传中断会留下分片：尽力清一次再原样抛出，不让半截对象堆积
            deleteQuietly(objectKey);
            throw e;
        }
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

    // ==================== md 镜像 ====================

    /**
     * 删除该文档的 md 对象。按行路由（md 与原始文件由同一后端写入，行上的 storage_type 即其归属）；
     * md 可能本就不存在（txt/md/xlsx 等无 md 产物，或改造前的存量行），删除实现是幂等的。
     * <p>
     * 范围解析失败（知识库已不存在等）时只 WARN 并跳过：md 是镜像、DB 才是权威，
     * 这个分支只可能留下一份孤儿对象，不值得让删文档失败。
     */
    public void deleteMd(Document doc) {
        Scope scope = scopeOrNull(doc.getKbId());
        if (scope == null) {
            log.warn("跳过删除 md 镜像：无法确定文档 {} 的存储路径（kbId={}）", doc.getId(), doc.getKbId());
            return;
        }
        router.forRow(doc.getStorageType()).delete(mdKey(scope.workspaceId(), scope.kbId(), doc.getId()));
    }

    /**
     * 写入/覆盖 md 对象——<b>严格模式</b>：失败即抛出，用于用户主动保存 md 的场景
     * （保存失败必须让用户看见并回滚，而不是静默把对象存储留成旧版本）。
     */
    public void putMdStrict(Long kbId, Long docId, String mdText) {
        if (docId == null || mdText == null || mdText.isBlank()) {
            return;
        }
        Scope scope = scopeOrThrow(kbId);
        router.active().putString(mdKey(scope.workspaceId(), scope.kbId(), docId), mdText);
    }

    /**
     * 写入/覆盖 md 对象——<b>宽松模式</b>：失败仅 WARN，用于解析链路。
     * 理由：{@code kb_document_curate} 才是 md 的权威副本，对象存储只是镜像；
     * MinIO 抖动不该把整篇文档判成解析失败。
     */
    public void putMdQuietly(Long kbId, Long docId, String mdText) {
        try {
            putMdStrict(kbId, docId, mdText);
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

    // ==================== key 生成 ====================

    /** {uuid}_{安全化的原始主名}.{ext}；原始名不可用时退化为纯 uuid。 */
    static String storedName(String originalFileName, String fileType) {
        String base = safeBaseName(originalFileName);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return base == null ? uuid + "." + fileType : uuid + "_" + base + "." + fileType;
    }

    /**
     * 原始文件名 → 可安全入 key 的主名：去目录、去扩展名、换掉危险字符、限长。
     * <p>
     * 只做可移植性处理、不做转义——S3 key 与 NTFS 都是 Unicode，中文原样保留（可读性优先）。
     * 返回 {@code null} 表示没有可用主名（空、纯符号、全被截掉），调用方回退成纯 uuid。
     */
    static String safeBaseName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return null;
        }
        // 去目录部分：文件名来自浏览器，可能被构造成 ../../etc/passwd 或 C:\x 前缀
        String name = originalFileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 去最后一个扩展名：真实扩展名由 fileType 决定，否则会出现 report.pdf.pdf
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        // 以点开头的名字（.pdf / ...x）去掉扩展名后只剩点，属隐藏文件或纯符号，视作没有可用主名
        if (name.startsWith(".")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            sb.appendCodePoint(isUnsafeInName(cp) ? '_' : cp);
        }
        // Windows 不允许组件以空格或点结尾；本地后端要真落盘，所以两侧一律削掉
        String cleaned = trimDotsAndSpaces(sb.toString());
        if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '_')) {
            return null;
        }
        return truncateByCodePoints(cleaned, MAX_NAME_SEGMENT_CODE_POINTS);
    }

    /** 目录分隔符、Windows 保留字符与控制字符换掉；其余（含中文、空格、'-'、'('）原样保留。 */
    private static boolean isUnsafeInName(int cp) {
        if (cp < 0x20 || cp == 0x7F) {
            return true;
        }
        return switch (cp) {
            case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> true;
            default -> false;
        };
    }

    private static String trimDotsAndSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '.' || value.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) {
            end--;
        }
        return value.substring(start, end);
    }

    /** 按码点截断，避免把代理对（emoji 等）劈成半个字符。 */
    private static String truncateByCodePoints(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /** 扩展名 → 目录段：只允许 [a-z0-9] 且不超过 16 字符，异常一律归入 bin（不阻断上传）。 */
    static String normalizeFileType(String fileType) {
        if (fileType == null) {
            return TYPE_FALLBACK;
        }
        String type = fileType.trim().toLowerCase();
        if (type.isEmpty() || type.length() > 16) {
            return TYPE_FALLBACK;
        }
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return TYPE_FALLBACK;
            }
        }
        return type;
    }

    // ==================== 范围解析 ====================

    /** 上传主路径用：拿不到落点范围就抛，绝不让文件写到一个说不清的位置。 */
    private Scope scopeOrThrow(Long kbId) {
        if (kbId == null) {
            throw new BizException("文档缺少知识库归属，无法确定存储路径");
        }
        Long workspaceId = workspaceIdResolver.resolve(kbId);
        if (workspaceId == null) {
            throw new BizException("知识库 " + kbId + " 未归属任何工作空间，无法确定存储路径");
        }
        return new Scope(workspaceId, kbId);
    }

    /** md 镜像/删除用：解析失败返回 null，由调用方按「镜像可丢」处理。 */
    private Scope scopeOrNull(Long kbId) {
        try {
            return scopeOrThrow(kbId);
        } catch (RuntimeException e) {
            log.warn("解析存储落点失败 kbId={}: {}", kbId, e.getMessage());
            return null;
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
