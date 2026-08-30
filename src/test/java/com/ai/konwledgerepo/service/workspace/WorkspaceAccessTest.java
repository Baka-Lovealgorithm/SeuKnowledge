package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作空间归属校验 + 知识库 ACL 校验唯一入口（WorkspaceAccess）测试：
 * requireKb / requireDoc / requireBelongs 的跨空间拒绝、缺失 404、历史空归属放行语义；
 * RESTRICTED 知识库的 ACL：无授权拒绝、VIEW 只读、EDIT 可写、管理员/创建者免检、
 * 非请求线程（无请求上下文）仅归属校验。
 */
class WorkspaceAccessTest {

    private static final long WS = 10L;
    private static final long OTHER_WS = 99L;
    private static final long USER = 5L;

    private KnowledgeBaseRepository kbRepo;
    private DocumentRepository docRepo;
    private KbAccessRepository accessRepo;
    private WorkspaceMemberRepository memberRepo;
    private WorkspaceAccess access;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KnowledgeBaseRepository.class);
        docRepo = mock(DocumentRepository.class);
        accessRepo = mock(KbAccessRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        access = new WorkspaceAccess(kbRepo, docRepo, accessRepo, memberRepo);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private KnowledgeBase kb(long id, Long workspaceId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setWorkspaceId(workspaceId);
        kb.setVisibility("PUBLIC");
        return kb;
    }

    private KnowledgeBase restrictedKb(long id, Long workspaceId, Long createdBy) {
        KnowledgeBase kb = kb(id, workspaceId);
        kb.setVisibility("RESTRICTED");
        kb.setCreatedBy(createdBy);
        return kb;
    }

    /** 模拟请求线程：注入 userId / workspaceId 与 HTTP method（AuthInterceptor 语义） */
    private MockHttpServletRequest request(String method, Long userId) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/x");
        if (userId != null) {
            req.setAttribute("userId", userId);
            req.setAttribute("workspaceId", WS);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        return req;
    }

    // ===== 归属校验（无请求上下文 / 非敏感路径） =====

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

    // ===== RESTRICTED 库 ACL（请求线程，自动叠加） =====

    private WorkspaceMember member(String role) {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(WS);
        m.setUserId(USER);
        m.setRole(role);
        return m;
    }

    @Test
    void restrictedKb_noRequestContext_ownershipOnly() {
        // 无请求上下文（异步任务/单元测试）：仅归属校验，ACL 不叠加
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, 99L)));

        assertSame(1L, access.requireKb(1L, WS).getId());
    }

    @Test
    void restrictedKb_noGrant_readRejected() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, 99L)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("MEMBER")));
        when(accessRepo.findByKbIdAndGranteeTypeAndGranteeId(1L, "USER", USER)).thenReturn(Optional.empty());
        request("GET", USER);

        BizException ex = assertThrows(BizException.class, () -> access.requireKb(1L, WS));
        assertEquals(403, ex.getCode());
    }

    @Test
    void restrictedKb_viewGrant_readOk_writeRejected() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, 99L)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("MEMBER")));
        KbAccess acl = new KbAccess();
        acl.setKbId(1L);
        acl.setGranteeType("USER");
        acl.setGranteeId(USER);
        acl.setPermission("VIEW");
        when(accessRepo.findByKbIdAndGranteeTypeAndGranteeId(1L, "USER", USER)).thenReturn(Optional.of(acl));

        // GET 读通过
        request("GET", USER);
        assertSame(1L, access.requireKb(1L, WS).getId());

        // POST 写拒绝（VIEW 授权不足）
        request("POST", USER);
        BizException ex = assertThrows(BizException.class, () -> access.requireKb(1L, WS));
        assertEquals(403, ex.getCode());
    }

    @Test
    void restrictedKb_editGrant_readAndWriteOk() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, 99L)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("MEMBER")));
        KbAccess acl = new KbAccess();
        acl.setKbId(1L);
        acl.setGranteeType("USER");
        acl.setGranteeId(USER);
        acl.setPermission("EDIT");
        when(accessRepo.findByKbIdAndGranteeTypeAndGranteeId(1L, "USER", USER)).thenReturn(Optional.of(acl));

        request("GET", USER);
        assertSame(1L, access.requireKb(1L, WS).getId());
        request("POST", USER);
        assertSame(1L, access.requireKb(1L, WS).getId());
    }

    @Test
    void restrictedKb_admin_alwaysAllowed() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, 99L)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("ADMIN")));
        request("GET", USER);

        assertSame(1L, access.requireKb(1L, WS).getId());
    }

    @Test
    void restrictedKb_creator_alwaysAllowed() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, USER)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("MEMBER")));
        request("POST", USER);

        assertSame(1L, access.requireKb(1L, WS).getId());
    }

    @Test
    void publicKb_noGrant_allowed() {
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb(1L, WS)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("MEMBER")));
        request("GET", USER);

        assertSame(1L, access.requireKb(1L, WS).getId());
    }

    @Test
    void requireDocAccess_explicitWriteChecksAcl() {
        Document doc = new Document();
        doc.setId(7L);
        doc.setKbId(1L);
        when(docRepo.findById(7L)).thenReturn(Optional.of(doc));
        when(kbRepo.findById(1L)).thenReturn(Optional.of(restrictedKb(1L, WS, 99L)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, USER)).thenReturn(Optional.of(member("MEMBER")));
        KbAccess acl = new KbAccess();
        acl.setKbId(1L);
        acl.setGranteeType("USER");
        acl.setGranteeId(USER);
        acl.setPermission("VIEW");
        when(accessRepo.findByKbIdAndGranteeTypeAndGranteeId(1L, "USER", USER)).thenReturn(Optional.of(acl));

        // 显式写语义：VIEW 授权不足
        BizException ex = assertThrows(BizException.class,
                () -> access.requireDocAccess(7L, WS, USER, true));
        assertEquals(403, ex.getCode());

        // 显式读语义：VIEW 授权通过
        assertSame(doc, access.requireDocAccess(7L, WS, USER, false));
    }
}
