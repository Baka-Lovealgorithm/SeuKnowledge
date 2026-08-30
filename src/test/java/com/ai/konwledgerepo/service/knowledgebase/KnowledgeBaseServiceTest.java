package com.ai.konwledgerepo.service.knowledgebase;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.KbCreateRequest;
import com.ai.konwledgerepo.dto.KbResponse;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    private KnowledgeBaseRepository kbRepo;
    private DocumentRepository docRepo;
    private RedisCacheService cache;
    private KbAccessRepository accessRepo;
    private WorkspaceMemberRepository memberRepo;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KnowledgeBaseRepository.class);
        docRepo = mock(DocumentRepository.class);
        cache = mock(RedisCacheService.class);
        accessRepo = mock(KbAccessRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        WorkspaceAccess workspaceAccess = new WorkspaceAccess(kbRepo, docRepo, accessRepo, memberRepo);
        service = new KnowledgeBaseService(kbRepo, docRepo, cache, workspaceAccess, accessRepo, memberRepo,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
    }

    private KnowledgeBase kb(long id, String visibility, Long createdBy) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setWorkspaceId(10L);
        kb.setVisibility(visibility);
        kb.setCreatedBy(createdBy);
        return kb;
    }

    private WorkspaceMember member(Long userId, String role) {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(10L);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void create_setsDraftStatusPublicAndUser() {
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.countByKbId(any())).thenReturn(0L);

        KbResponse resp = service.create(new KbCreateRequest("测试库", "描述"), 1L, 10L);
        assertEquals("测试库", resp.name());
        assertEquals("DRAFT", resp.status());
        assertEquals("PUBLIC", resp.visibility(), "新建知识库默认公开");
        assertEquals(1L, resp.createdBy());
    }

    @Test
    void updateStatus_invalidStatus_throws() {
        assertThrows(BizException.class, () -> service.updateStatus(1L, "BAD", 10L));
    }

    @Test
    void updateStatus_validStatus() {
        KnowledgeBase kb = kb(1L, "PUBLIC", 1L);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KbResponse resp = service.updateStatus(1L, "AVAILABLE", 10L);
        assertEquals("AVAILABLE", resp.status());
    }

    @Test
    void updateStatus_otherWorkspace_throws() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setWorkspaceId(99L);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));

        assertThrows(BizException.class, () -> service.updateStatus(1L, "AVAILABLE", 10L));
    }

    @Test
    void delete_archivesInsteadOfRemoving() {
        KnowledgeBase kb = kb(1L, "PUBLIC", 1L);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.delete(1L, 10L);
        assertTrue(kb.getArchived(), "删除应为归档（软删除）");
    }

    @Test
    void getEntity_notFound_throws() {
        when(kbRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.getEntity(99L));
    }

    @Test
    void list_member_seesPublicAndGrantedRestrictedOnly() {
        List<KnowledgeBase> all = List.of(
                kb(1L, "PUBLIC", 9L),
                kb(2L, "RESTRICTED", 9L),
                kb(3L, "RESTRICTED", 5L)); // 创建者是当前用户（userId=5）
        when(kbRepo.findByWorkspaceIdAndArchivedFalseOrderByIdDesc(10L)).thenReturn(all);
        when(memberRepo.findByWorkspaceIdAndUserId(10L, 5L)).thenReturn(Optional.of(member(5L, "MEMBER")));
        KbAccess acl = new KbAccess();
        acl.setKbId(2L);
        acl.setGranteeType("USER");
        acl.setGranteeId(5L);
        when(accessRepo.findByGranteeTypeAndGranteeId("USER", 5L)).thenReturn(List.of(acl));

        List<KbResponse> resp = service.list(10L, 5L);
        assertEquals(List.of(1L, 2L, 3L), resp.stream().map(KbResponse::id).toList(),
                "普通成员可见：公开库 + 已授权私有库 + 自己创建的私有库");
    }

    @Test
    void list_member_restrictedWithoutGrant_hidden() {
        List<KnowledgeBase> all = List.of(
                kb(1L, "PUBLIC", 9L),
                kb(2L, "RESTRICTED", 9L));
        when(kbRepo.findByWorkspaceIdAndArchivedFalseOrderByIdDesc(10L)).thenReturn(all);
        when(memberRepo.findByWorkspaceIdAndUserId(10L, 5L)).thenReturn(Optional.of(member(5L, "MEMBER")));
        when(accessRepo.findByGranteeTypeAndGranteeId("USER", 5L)).thenReturn(List.of());

        List<KbResponse> resp = service.list(10L, 5L);
        assertEquals(List.of(1L), resp.stream().map(KbResponse::id).toList(),
                "未授权的私有库对普通成员不可见");
    }

    @Test
    void list_admin_seesAll() {
        List<KnowledgeBase> all = List.of(
                kb(1L, "PUBLIC", 9L),
                kb(2L, "RESTRICTED", 9L));
        when(kbRepo.findByWorkspaceIdAndArchivedFalseOrderByIdDesc(10L)).thenReturn(all);
        when(memberRepo.findByWorkspaceIdAndUserId(10L, 5L)).thenReturn(Optional.of(member(5L, "ADMIN")));

        List<KbResponse> resp = service.list(10L, 5L);
        assertEquals(List.of(1L, 2L), resp.stream().map(KbResponse::id).toList(),
                "管理员可见全部（含私有库）");
    }
}
