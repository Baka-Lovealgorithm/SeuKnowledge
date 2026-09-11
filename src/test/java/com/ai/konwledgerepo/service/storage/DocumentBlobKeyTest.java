package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.support.StorageTestSupport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * object key 口径与文件名安全化的纯单元测试（不连任何存储后端、常跑）。
 * <p>
 * 这些是一个「契约」而非实现细节：key 布局一旦变了，MinIO 控制台里的目录结构、
 * 运维的备份粒度（按 kb 前缀）、以及本地落盘位置都会跟着变，所以必须有用例钉住。
 */
class DocumentBlobKeyTest {

    // ==================== key 布局 ====================

    @Test
    void originalKey_layoutIsWsKbRawTypeName() {
        assertEquals("3/7/raw/pdf/abc.pdf",
                DocumentBlobService.originalKey(3L, 7L, "pdf", "abc.pdf"));
    }

    @Test
    void mdKey_layoutIsWsKbDerivedMd() {
        assertEquals("3/7/derived/md/42.md", DocumentBlobService.mdKey(3L, 7L, 42L));
    }

    /** md 与原始件必须在不同分支下：一个是 raw/、一个是 derived/，否则「哪份是干净原始件」说不清。 */
    @Test
    void mdKey_isDisjointFromOriginalKey() {
        String original = DocumentBlobService.originalKey(1L, 2L, "pdf", "x.pdf");
        String md = DocumentBlobService.mdKey(1L, 2L, 2L);
        assertFalse(original.startsWith("1/2/derived/"));
        assertFalse(md.startsWith("1/2/raw/"));
    }

    // ==================== 对象名 ====================

    /**
     * 纯 uuid + 扩展名：key 里不含任何可读段。
     * <p>
     * 这是「重命名文档不必搬对象」的根据——S3 没有 rename 原语（改名 = copy + delete，
     * 两步非原子且要整份复制），而 key 里的名字又不参与程序逻辑，所以干脆不放。
     * 该契约由 {@code storedName} 的签名保证（它根本收不到文件名），编译期即成立。
     */
    @Test
    void storedName_isPlainUuidWithExtension() {
        String stored = DocumentBlobService.storedName("pdf");
        assertTrue(stored.matches("[0-9a-f]{32}\\.pdf"), stored);
    }

    /** 唯一性完全由 uuid 承担：同一批文件、同样的扩展名也不会撞 key。 */
    @Test
    void storedName_isUniqueForIdenticalInput() {
        assertNotEquals(DocumentBlobService.storedName("pdf"), DocumentBlobService.storedName("pdf"));
    }

    /** 归类段非法时的兜底：返回的对象名仍须可用（fileType 只影响目录，不该阻断上传）。 */
    @Test
    void storedName_usesFallbackTypeWhenFileTypeInvalid() {
        String stored = DocumentBlobService.storedName(DocumentBlobService.normalizeFileType(null));
        assertTrue(stored.endsWith("." + DocumentBlobService.TYPE_FALLBACK), stored);
    }

    // ==================== 扩展名归类段 ====================

    @Test
    void normalizeFileType_lowercasesAndGuards() {
        assertEquals("pdf", DocumentBlobService.normalizeFileType("PDF"));
        assertEquals("xlsx", DocumentBlobService.normalizeFileType(" xlsx "));
        assertEquals(DocumentBlobService.TYPE_FALLBACK, DocumentBlobService.normalizeFileType(null));
        assertEquals(DocumentBlobService.TYPE_FALLBACK, DocumentBlobService.normalizeFileType(""));
        assertEquals(DocumentBlobService.TYPE_FALLBACK, DocumentBlobService.normalizeFileType("a b"));
        assertEquals(DocumentBlobService.TYPE_FALLBACK, DocumentBlobService.normalizeFileType("../x"));
        assertEquals(DocumentBlobService.TYPE_FALLBACK, DocumentBlobService.normalizeFileType("x".repeat(17)));
    }

    // ==================== 落点范围解析的失败面 ====================

    @Test
    void putOriginal_missingKbId_throwsInsteadOfWritingSomewhereUnknown() {
        DocumentBlobService blobService = StorageTestSupport.localBlobService();
        assertThrows(BizException.class, () -> blobService.putOriginal(
                null, "pdf", "application/pdf", InputStream.nullInputStream(), 0L));
    }

    @Test
    void putMdStrict_missingKbId_throws() {
        DocumentBlobService blobService = StorageTestSupport.localBlobService();
        assertThrows(BizException.class, () -> blobService.putMdStrict(null, 1L, "# md"));
    }

    /** 宽松模式必须吞掉解析失败：kb 解析不到时只影响镜像，不能把整篇文档判成解析失败。 */
    @Test
    void putMdQuietly_missingKbId_doesNotThrow() {
        DocumentBlobService blobService = StorageTestSupport.localBlobService();
        assertDoesNotThrow(() -> blobService.putMdQuietly(null, 1L, "# md"));
    }

    @Test
    void putMd_nullDocIdOrBlankText_isNoOp() {
        DocumentBlobService blobService = StorageTestSupport.localBlobService();
        assertDoesNotThrow(() -> blobService.putMdStrict(1L, null, "# md"));
        assertDoesNotThrow(() -> blobService.putMdStrict(1L, 1L, "   "));
    }
}
