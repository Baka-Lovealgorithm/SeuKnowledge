package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.entity.ParseCache;
import com.ai.konwledgerepo.repository.ParseCacheRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ParseCacheService 单元测试：文件哈希、缓存读写往返、miss 语义、异常兜底。
 */
class ParseCacheServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ParseCacheService service(ParseCacheRepository repo) {
        return new ParseCacheService(repo, objectMapper);
    }

    @Test
    void sha256_isStableAndContentSensitive() throws Exception {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        ParseCacheService svc = service(repo);

        Path a = tempDir.resolve("a.pdf");
        Files.writeString(a, "content-aaa");
        Path b = tempDir.resolve("b.pdf");
        Files.writeString(b, "content-bbb");
        Path a2 = tempDir.resolve("a-copy.pdf");
        Files.writeString(a2, "content-aaa");

        String ha = svc.sha256(a);
        String hb = svc.sha256(b);
        String ha2 = svc.sha256(a2);

        assertEquals(64, ha.length(), "SHA-256 十六进制应为 64 位");
        assertEquals(ha, ha2, "相同内容哈希一致");
        assertFalse(ha.equals(hb), "不同内容哈希不同");
    }

    @Test
    void putThenGet_roundTripsPageMarkdown() {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        ParseCacheService svc = service(repo);

        List<LlamaParseService.PageMarkdown> pages = List.of(
                new LlamaParseService.PageMarkdown(1, "# 第一页\n\n正文。"),
                new LlamaParseService.PageMarkdown(2, "| 表头 |\n| --- |\n| 值 |"));

        when(repo.findByFileHash("hash123")).thenReturn(Optional.empty());
        svc.put("hash123", "test.pdf", "pdf", pages);

        // 捕获保存的实体，模拟后续读取返回
        org.mockito.ArgumentCaptor<ParseCache> captor =
                org.mockito.ArgumentCaptor.forClass(ParseCache.class);
        verify(repo).save(captor.capture());
        ParseCache saved = captor.getValue();
        assertEquals("hash123", saved.getFileHash());
        assertEquals(2, saved.getPageCount());
        assertTrue(saved.getPagesJson().contains("# 第一页"), "序列化应含第一页 md");
        assertTrue(saved.getPagesJson().contains("表头"), "序列化应含表格页 md");

        // 回读：从保存的 json 恢复页面序列
        when(repo.findByFileHash("hash123")).thenReturn(Optional.of(saved));
        Optional<List<LlamaParseService.PageMarkdown>> result = svc.get("hash123");
        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertEquals(1, result.get().get(0).pageNumber());
        assertTrue(result.get().get(0).markdown().contains("第一页"));
        assertEquals(2, result.get().get(1).pageNumber());
        assertTrue(result.get().get(1).markdown().contains("表头"));
    }

    @Test
    void get_miss_returnsEmpty() {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        ParseCacheService svc = service(repo);
        when(repo.findByFileHash("nope")).thenReturn(Optional.empty());

        Optional<List<LlamaParseService.PageMarkdown>> result = svc.get("nope");

        assertTrue(result.isEmpty(), "未命中应返回 empty");
    }

    @Test
    void get_corruptJson_fallsBackToEmpty() {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        ParseCacheService svc = service(repo);
        ParseCache cache = new ParseCache();
        cache.setPagesJson("{{{ not json");
        when(repo.findByFileHash("bad")).thenReturn(Optional.of(cache));

        Optional<List<LlamaParseService.PageMarkdown>> result = svc.get("bad");

        assertTrue(result.isEmpty(), "损坏缓存应按 miss 处理");
    }

    @Test
    void get_nullHash_returnsEmpty() {
        ParseCacheService svc = service(mock(ParseCacheRepository.class));
        assertTrue(svc.get(null).isEmpty());
        assertTrue(svc.get("").isEmpty());
    }

    @Test
    void put_emptyPages_doesNothing() {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        ParseCacheService svc = service(repo);

        svc.put("hash", "a.pdf", "pdf", List.of());
        verify(repo, never()).save(any());

        svc.put("hash", "a.pdf", "pdf", null);
        verify(repo, never()).save(any());
    }

    @Test
    void put_repoThrows_swallowsWithoutPropagating() {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        when(repo.findByFileHash(any())).thenThrow(new RuntimeException("db down"));
        ParseCacheService svc = service(repo);

        // 写入失败不应抛异常（解析链路不因缓存中断）
        svc.put("hash", "a.pdf", "pdf", List.of(new LlamaParseService.PageMarkdown(1, "md")));
    }

    @Test
    void put_existingHash_updatesInPlace() {
        ParseCacheRepository repo = mock(ParseCacheRepository.class);
        ParseCacheService svc = service(repo);
        ParseCache existing = new ParseCache();
        existing.setPagesJson("old");
        when(repo.findByFileHash("hash")).thenReturn(Optional.of(existing));

        svc.put("hash", "new.pdf", "pdf",
                List.of(new LlamaParseService.PageMarkdown(1, "新内容")));

        assertEquals("new.pdf", existing.getFileName());
        assertEquals(1, existing.getPageCount());
        assertTrue(existing.getPagesJson().contains("新内容"), "覆盖写入新序列化结果");
        verify(repo).save(existing);
    }
}
