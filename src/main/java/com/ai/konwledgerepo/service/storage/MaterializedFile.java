package com.ai.konwledgerepo.service.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「物化文件」句柄：把对象存储里的对象落到本地临时文件，供只接受 {@link Path} 的解析器
 * （PDFBox / POI / Jsoup / LlamaParse 上传）使用。
 * <p>
 * 必须用 try-with-resources 持有，close 是唯一清理时机——正常返回、抛异常、提前 return
 * 三种路径都会走到，因此不会泄漏临时文件。
 * <p>
 * 本地后端返回 {@link #existing(Path)}（{@code deleteOnClose=false}），因为那是真实入库文件，
 * 绝不能因为 close 被删掉；只有 MinIO 物化出来的临时副本才用 {@link #temporary(Path)}。
 */
public final class MaterializedFile implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MaterializedFile.class);

    private final Path path;
    private final boolean deleteOnClose;

    private MaterializedFile(Path path, boolean deleteOnClose) {
        this.path = path;
        this.deleteOnClose = deleteOnClose;
    }

    /** 指向一个已存在的本机文件（本地后端）；close 不删除它。 */
    public static MaterializedFile existing(Path path) {
        return new MaterializedFile(path, false);
    }

    /** 指向一个临时副本（对象存储后端）；close 时删除。 */
    public static MaterializedFile temporary(Path path) {
        return new MaterializedFile(path, true);
    }

    public Path path() {
        return path;
    }

    public boolean isTemporary() {
        return deleteOnClose;
    }

    @Override
    public void close() {
        if (!deleteOnClose) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 清理失败不能影响主流程（操作系统最终会回收），但要留痕便于排查磁盘占用
            log.warn("清理物化临时文件失败 {}: {}", path, e.getMessage());
        }
    }
}
