package com.ai.konwledgerepo.service.storage;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.support.StorageTestSupport;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("3/7/raw/pdf/abc_x.pdf",
                DocumentBlobService.originalKey(3L, 7L, "pdf", "abc_x.pdf"));
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

    // ==================== 文件名安全化 ====================

    @Test
    void safeBaseName_stripsDirectoryAndExtension() {
        assertEquals("报告", DocumentBlobService.safeBaseName("报告.pdf"));
        assertEquals("报告", DocumentBlobService.safeBaseName("../../etc/报告.pdf"));
        assertEquals("报告", DocumentBlobService.safeBaseName("C:\\Users\\x\\报告.docx"));
        // 多段扩展名只去最后一段
        assertEquals("报表v1.2", DocumentBlobService.safeBaseName("报表v1.2.xlsx"));
    }

    @Test
    void safeBaseName_keepsChineseSpacesAndInnerDots() {
        // 中文、空格、括号、中间的点都保留：可读性优先，只有真正危险的字符才换掉
        assertEquals("项目报告v1.2 (终稿)", DocumentBlobService.safeBaseName("项目报告v1.2 (终稿).pdf"));
    }

    @Test
    void safeBaseName_replacesIllegalChars() {
        assertEquals("a_b_c_d_e_f_g_h", DocumentBlobService.safeBaseName("a:b*c?d\"e<f>g|h.pdf"));
        assertEquals("a_b", DocumentBlobService.safeBaseName("a\u0000b.txt")); // 控制字符
        assertEquals("c_d", DocumentBlobService.safeBaseName("c*d.xlsx"));
    }

    @Test
    void safeBaseName_rejectsNamesThatCannotBeUsed() {
        assertNull(DocumentBlobService.safeBaseName(null));
        assertNull(DocumentBlobService.safeBaseName("   "));
        assertNull(DocumentBlobService.safeBaseName(".pdf"));    // 以点开头 → 无可用主名
        assertNull(DocumentBlobService.safeBaseName("...x"));    // 去掉扩展名后只剩点
        assertNull(DocumentBlobService.safeBaseName("___.txt")); // 只剩替换符
    }

    @Test
    void safeBaseName_neverExceedsLimitAndNeverSplitsSurrogatePair() {
        String longChinese = "报".repeat(100);
        assertEquals(DocumentBlobService.MAX_NAME_SEGMENT_CODE_POINTS,
                DocumentBlobService.safeBaseName(longChinese).length());

        // emoji 是代理对：按码点截断，末尾不能留半个字符（否则本地落盘会写出非法文件名）
        String emoji = "😀".repeat(70);
        String truncated = DocumentBlobService.safeBaseName(emoji + ".txt");
        assertEquals(DocumentBlobService.MAX_NAME_SEGMENT_CODE_POINTS,
                truncated.codePointCount(0, truncated.length()));
        assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1)));
    }

    // ==================== 对象名 ====================

    @Test
    void storedName_isUuidPrefixedBySafeName() {
        String stored = DocumentBlobService.storedName("季度报告.pdf", "pdf");
        assertTrue(stored.endsWith("_季度报告.pdf"), stored);
        // uuid 段固定 32 位十六进制（去掉了连字符）
        assertEquals(32, stored.indexOf('_'));
        assertTrue(stored.substring(0, 32).matches("[0-9a-f]{32}"), stored);
    }

    @Test
    void storedName_fallsBackToPlainUuidWhenNameUnusable() {
        String stored = DocumentBlobService.storedName("   ", "txt");
        assertFalse(stored.contains("_"), stored);
        assertTrue(stored.endsWith(".txt"), stored);
        assertEquals(32 + 4, stored.length());
    }

    @Test
    void storedName_isUniqueEvenForIdenticalInput() {
        assertFalse(DocumentBlobService.storedName("同名.pdf", "pdf")
                .equals(DocumentBlobService.storedName("同名.pdf", "pdf")));
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
                null, "a.pdf", "pdf", "application/pdf", InputStream.nullInputStream(), 0L));
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
