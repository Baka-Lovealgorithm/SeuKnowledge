package com.ai.konwledgerepo.service.knowledgebase;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.KbAccessRequest;
import com.ai.konwledgerepo.dto.KbAccessResponse;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KbGroup;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.GroupMemberRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KbGroupRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbAccessServiceTest {

    private static final long WS = 10L;
    private static final long KB = 1L;
    private static final long OPERATOR = 2L;  // 创建者
    private static final long GRANTEE = 3L;   // 目标用户
    private static final long OTHER = 4L;     // 无管理权用户
    private static final long GROUP = 20L;    // 目标组

    private KbAccessRepository accessRepo;
    private KnowledgeBaseRepository kbRepo;
    private WorkspaceMemberRepository memberRepo;
    private SysUserRepository userRepo;
    private KbGroupRepository groupRepo;
    private KbAccessService service;

    @BeforeEach
    void setUp() {
        accessRepo = mock(KbAccessRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        userRepo = mock(SysUserRepository.class);
        groupRepo = mock(KbGroupRepository.class);
        WorkspaceAccess workspaceAccess = new WorkspaceAccess(kbRepo, null, accessRepo, memberRepo,
                mock(GroupMemberRepository.class), groupRepo);
        service = new KbAccessService(accessRepo, kbRepo, memberRepo, userRepo, groupRepo, workspaceAccess);
    }

    private KnowledgeBase kb(Long createdBy) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB);
        kb.setWorkspaceId(WS);
        kb.setCreatedBy(createdBy);
        return kb;
    }

    private WorkspaceMember member(Long userId, String role) {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(WS);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    private KbAccess acl(Long id) {
        KbAccess a = new KbAccess();
        a.setId(id);
        a.setKbId(KB);
        a.setGranteeType("USER");
        a.setGranteeId(GRANTEE);
        a.setPermission("VIEW");
        return a;
    }

    @Test
    void list_requiresManager_creatorOk() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));
        when(accessRepo.findByKbIdOrderByIdAsc(KB)).thenReturn(List.of(acl(9L)));
        SysUser u = new SysUser();
        u.setUsername("alice");
        when(userRepo.findById(GRANTEE)).thenReturn(Optional.of(u));

        List<KbAccessResponse> resp = service.list(KB, WS, OPERATOR);
        assertEquals(1, resp.size());
        assertEquals("USER", resp.get(0).granteeType());
        assertEquals("alice", resp.get(0).granteeName());
        assertEquals("VIEW", resp.get(0).permission());
    }

    @Test
    void list_nonManager_forbidden() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OTHER)).thenReturn(Optional.of(member(OTHER, "MEMBER")));

        BizException ex = assertThrows(BizException.class, () -> service.list(KB, WS, OTHER));
        assertEquals(403, ex.getCode());
    }

    @Test
    void grant_user_adminOk_createsAcl() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "ADMIN")));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, GRANTEE)).thenReturn(Optional.of(member(GRANTEE, "MEMBER")));
        when(accessRepo.findByKbIdAndGranteeTypeAndGranteeId(KB, "USER", GRANTEE)).thenReturn(Optional.empty());
        when(accessRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KbAccessResponse resp = service.grant(KB, WS, OPERATOR, new KbAccessRequest("USER", GRANTEE, "EDIT"));
        assertEquals("USER", resp.granteeType());
        assertEquals(GRANTEE, resp.granteeId());
        assertEquals("EDIT", resp.permission());
        verify(accessRepo).save(any());
    }

    @Test
    void grant_group_ok_createsGroupAcl() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "ADMIN")));
        KbGroup g = new KbGroup();
        g.setId(GROUP);
        g.setWorkspaceId(WS);
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.of(g));
        when(accessRepo.findByKbIdAndGranteeTypeAndGranteeId(KB, "GROUP", GROUP)).thenReturn(Optional.empty());
        when(accessRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KbAccessResponse resp = service.grant(KB, WS, OPERATOR, new KbAccessRequest("GROUP", GROUP, "VIEW"));
        assertEquals("GROUP", resp.granteeType());
        assertEquals(GROUP, resp.granteeId());
        assertEquals("VIEW", resp.permission());
        verify(accessRepo).save(any());
    }

    @Test
    void grant_group_notInWorkspace_forbidden() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.grant(KB, WS, OPERATOR, new KbAccessRequest("GROUP", GROUP, "VIEW")));
        assertEquals("目标组不存在或不属于当前工作空间", ex.getMessage());
    }

    @Test
    void grant_self_forbidden() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));

        BizException ex = assertThrows(BizException.class,
                () -> service.grant(KB, WS, OPERATOR, new KbAccessRequest("USER", OPERATOR, "VIEW")));
        assertEquals("不能向自己授权（创建者/管理员始终可访问）", ex.getMessage());
    }

    @Test
    void grant_outsideWorkspaceMember_forbidden() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, GRANTEE)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.grant(KB, WS, OPERATOR, new KbAccessRequest("USER", GRANTEE, "VIEW")));
        assertEquals("目标用户不是当前工作空间成员", ex.getMessage());
    }

    @Test
    void revoke_updatesPermission_notFound_throws() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));
        when(accessRepo.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.revoke(KB, WS, OPERATOR, 99L));
        assertEquals("授权记录不存在", ex.getMessage());
    }

    @Test
    void setVisibility_restricted_ok() {
        KnowledgeBase kb = kb(OPERATOR);
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var v = service.setVisibility(KB, WS, OPERATOR, "RESTRICTED");
        assertEquals("RESTRICTED", v.value());
        assertEquals("RESTRICTED", kb.getVisibility());
    }

    @Test
    void setVisibility_invalid_throws() {
        when(kbRepo.findById(KB)).thenReturn(Optional.of(kb(OPERATOR)));
        when(memberRepo.findByWorkspaceIdAndUserId(WS, OPERATOR)).thenReturn(Optional.of(member(OPERATOR, "MEMBER")));

        BizException ex = assertThrows(BizException.class, () -> service.setVisibility(KB, WS, OPERATOR, "SECRET"));
        assertEquals("可见性只能是 PUBLIC / RESTRICTED", ex.getMessage());
    }
}