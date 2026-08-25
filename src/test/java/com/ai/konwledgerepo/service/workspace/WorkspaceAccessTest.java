package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作空间归属校验唯一入口（WorkspaceAccess）测试：
 * requireKb / requireDoc / requireBelongs 的跨空间拒绝、缺失 404、历史空归属放行语义。
 * 这是阶段 2 安全缺口补齐后的专项回归网。
 */
class WorkspaceAccessTest {

    private static final long WS = 10L;
    private static final long OTHER_WS = 99L;

    private KnowledgeBaseRepository kbRepo;
    private DocumentRepository docRepo;
    private WorkspaceAccess access;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KnowledgeBaseRepository.class);
        docRepo = mock(DocumentRepository.class);
        access = new WorkspaceAccess(kbRepo, docRepo);
    }

    private KnowledgeBase kb(long id, Long workspaceId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setWorkspaceId(workspaceId);
        return kb;
    }

    @Test
    void requireKb_sameWorkspace_returnsKb() {
        KnowledgeBase kb = kb(1L, WS);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));

        assertSame(kb, access.requireKb(1L, WS));
    }

    @Test
    void requireKb_crossWorkspace_throws403() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb(1L, OTHER_WS)));

        BizException ex = assertThrows(BizException.class, () -> access.requireKb(1L, WS));
        assertEquals(403, ex.getCode());
    }

    @Test
    void requireKb_missing_throws404Message() {
        when(kbRepo.findById(1L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> access.requireKb(1L, WS));
        assertEquals("知识库不存在", ex.getMessage());
    }

    @Test
    void requireKb_nullWorkspaceLegacy_accessible() {
        // 历史数据未回填 workspaceId：视为可访问（防御性放行，与多工作空间升级前语义一致）
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb(1L, null)));

        assertNull(access.requireKb(1L, WS).getWorkspaceId());
    }

    @Test
    void requireDoc_crossWorkspaceKb_throws403() {
        Document doc = new Document();
        doc.setId(7L);
        doc.setKbId(1L);
        when(docRepo.findById(7L)).thenReturn(Optional.of(doc));
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb(1L, OTHER_WS)));

        BizException ex = assertThrows(BizException.class, () -> access.requireDoc(7L, WS));
        assertEquals(403, ex.getCode());
    }

    @Test
    void requireDoc_sameWorkspace_returnsDoc() {
        Document doc = new Document();
        doc.setId(7L);
        doc.setKbId(1L);
        when(docRepo.findById(7L)).thenReturn(Optional.of(doc));
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb(1L, WS)));

        assertSame(doc, access.requireDoc(7L, WS));
    }

    @Test
    void requireDoc_missing_throws404Message() {
        when(docRepo.findById(7L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> access.requireDoc(7L, WS));
        assertEquals("文档不存在", ex.getMessage());
    }

    @Test
    void requireBelongs_crossWorkspace_throwsWithMessage() {
        BizException ex = assertThrows(BizException.class,
                () -> access.requireBelongs(OTHER_WS, WS, "无权操作该模型配置"));
        assertEquals(403, ex.getCode());
        assertEquals("无权操作该模型配置", ex.getMessage());
    }

    @Test
    void requireBelongs_sameOrNull_ok() {
        access.requireBelongs(WS, WS, "无权操作该模型配置");
        access.requireBelongs(null, WS, "无权操作该模型配置");
    }
}
