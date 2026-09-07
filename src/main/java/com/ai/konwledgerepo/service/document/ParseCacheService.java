package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.entity.ParseCache;
import com.ai.konwledgerepo.repository.ParseCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文件级 LlamaParse 结果缓存服务：文件 SHA-256 内容寻址，缓存逐页 markdown。
 * <p>
 * 仅覆盖 needScreenshots=false 的解析路径（HTML/PDF/DOCX）：缓存页面序列不包含截图，
 * 可无损持久化与还原。写入失败不阻断解析（仅告警）；读取异常按 miss 处理（回退全量解析）。
 */
@Service
public class ParseCacheService {

    private static final Logger log = LoggerFactory.getLogger(ParseCacheService.class);

    private static final String ALGORITHM = "SHA-256";

    /** 缓存页面序列的 JSON 结构（与 LlamaParseService.PageMarkdown 的页码+md 子集对应） */
    private static final TypeReference<List<CachedPage>> PAGE_LIST_TYPE = new TypeReference<>() {
    };

    private final ParseCacheRepository parseCacheRepository;
    private final ObjectMapper objectMapper;

    public ParseCacheService(ParseCacheRepository parseCacheRepository, ObjectMapper objectMapper) {
        this.parseCacheRepository = parseCacheRepository;
        this.objectMapper = objectMapper;
    }

    /** 计算文件 SHA-256（十六进制小写） */
    public String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("计算文件哈希失败: " + file, e);
        }
    }

    /** 按文件哈希读取缓存页面序列；未命中或解析失败返回 empty（回退全量解析） */
    public Optional<List<LlamaParseService.PageMarkdown>> get(String fileHash) {
        if (fileHash == null || fileHash.isBlank()) {
            return Optional.empty();
        }
        try {
            ParseCache cache = parseCacheRepository.findByFileHash(fileHash).orElse(null);
            if (cache == null || cache.getPagesJson() == null || cache.getPagesJson().isBlank()) {
                return Optional.empty();
            }
            List<CachedPage> pages = objectMapper.readValue(cache.getPagesJson(), PAGE_LIST_TYPE);
            List<LlamaParseService.PageMarkdown> result = new ArrayList<>(pages.size());
            for (CachedPage p : pages) {
                result.add(new LlamaParseService.PageMarkdown(p.pageNumber, p.markdown == null ? "" : p.markdown));
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("解析缓存读取失败（按 miss 处理，回退全量解析）hash={}: {}", shortHash(fileHash), e.getMessage());
            return Optional.empty();
        }
    }

    /** 写入/覆盖缓存（幂等：同 hash 覆盖）。失败仅告警，不阻断解析链路 */
    public void put(String fileHash, String fileName, String fileType, List<LlamaParseService.PageMarkdown> pages) {
        if (fileHash == null || fileHash.isBlank() || pages == null || pages.isEmpty()) {
            return;
        }
        try {
            List<CachedPage> cached = new ArrayList<>(pages.size());
            for (LlamaParseService.PageMarkdown p : pages) {
                if (p == null) {
                    continue;
                }
                cached.add(new CachedPage(p.pageNumber(), p.markdown() == null ? "" : p.markdown()));
            }
            if (cached.isEmpty()) {
                return;
            }
            String json = objectMapper.writeValueAsString(cached);
            ParseCache existing = parseCacheRepository.findByFileHash(fileHash).orElse(null);
            if (existing == null) {
                ParseCache cache = new ParseCache();
                cache.setFileHash(fileHash);
                cache.setFileName(fileName);
                cache.setFileType(fileType);
                cache.setPageCount(cached.size());
                cache.setPagesJson(json);
                parseCacheRepository.save(cache);
            } else {
                existing.setFileName(fileName);
                existing.setFileType(fileType);
                existing.setPageCount(cached.size());
                existing.setPagesJson(json);
                parseCacheRepository.save(existing);
            }
            log.info("解析缓存已写入 hash={} file={} pages={}", shortHash(fileHash), fileName, cached.size());
        } catch (Exception e) {
            log.warn("解析缓存写入失败（不影响解析）hash={}: {}", shortHash(fileHash), e.getMessage());
        }
    }

    /** 缓存页面 JSON 结构（页码 + markdown） */
    private record CachedPage(int pageNumber, String markdown) {
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 日志展示用的短哈希（前 12 位） */
    private static String shortHash(String hash) {
        if (hash == null) {
            return "";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
