package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.config.props.SeuFileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储后端（降级/可选后端）。
 * <p>
 * 落盘映射 {@code {storage-path}/{objectKey}}，因此新上传文件的布局与改造前
 * （{@code data/files/{kbId}/{uuid}.{ext}}）完全一致，无需迁移。
 * <p>
 * 读取时兼容存量行：{@code kb_document.file_path} 存的是当时的本地路径（绝对路径，或相对
 * 工作目录的 {@code ./data/files/...}），因此 {@link #resolve(String)} 优先按「原样路径」解释，
 * 只有该路径不存在时才按 {@code storage-path} 下的 key 解释。
 */
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path base;

    public LocalFileStorage(SeuFileProperties props) {
        this.base = Path.of(props.storagePath());
    }

    @Override
    public String type() {
        return LOCAL;
    }

    @Override
    public void put(String objectKey, InputStream in, long contentLength, String contentType) {
        Path target = base.resolve(objectKey);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("文件保存失败 [" + target + "]: " + e.getMessage());
        }
    }

    @Override
    public InputStream get(String objectKey) {
        Path path = resolve(objectKey);
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new BizException("文件读取失败 [" + path + "]: " + e.getMessage());
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            throw new BizException("文件删除失败 [" + objectKey + "]: " + e.getMessage());
        }
    }

    @Override
    public MaterializedFile materialize(String objectKey, String fileNameHint) {
        Path path = resolve(objectKey);
        if (!Files.exists(path)) {
            throw new BizException("文件不存在: " + path);
        }
        return MaterializedFile.existing(path);
    }

    @Override
    public String localPathOrNull(String objectKey) {
        return base.resolve(objectKey).toString();
    }

    @Override
    public void ensureReady() {
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new BizException("本地存储目录不可用 [" + base + "]: " + e.getMessage());
        }
        log.info("本地文件存储就绪: {}", base.toAbsolutePath());
    }

    /**
     * key → 本机路径。优先按「原样路径」解释以兼容存量 {@code file_path}
     * （绝对路径，或相对工作目录的 {@code ./data/files/...}），
     * 否则按 {@code {storage-path}/{key}} 解释（新口径）。
     */
    private Path resolve(String objectKey) {
        Path raw = Path.of(objectKey);
        if (raw.isAbsolute() || Files.exists(raw)) {
            return raw;
        }
        return base.resolve(objectKey);
    }
}
