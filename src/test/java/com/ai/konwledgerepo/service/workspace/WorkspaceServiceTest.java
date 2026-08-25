package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.dto.CreateMemberRequest;
import com.ai.konwledgerepo.dto.InviteMemberRequest;
import com.ai.konwledgerepo.dto.MemberResponse;
import com.ai.konwledgerepo.dto.WorkspaceResponse;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.entity.Workspace;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多工作空间成员管理测试：创建工作空间、按空间增删改成员、邀请已有用户、
 * 跨空间按 id 越权拒绝、Owner 转让/删除限当前空间。
 */
class WorkspaceServiceTest {

    private WorkspaceRepository workspaceRepo;
    private WorkspaceMemberRepository memberRepo;
    private SysUserRepository userRepo;
    private KnowledgeBaseRepository kbRepo;
    private RedisCacheService cache;
    private WorkspaceService service;

    private Workspace ws1;
    private Workspace ws2;
    private WorkspaceMember owner;

    @BeforeEach
    void setUp() {
        workspaceRepo = mock(WorkspaceRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        userRepo = mock(SysUserRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        cache = mock(RedisCacheService.class);
        service = new WorkspaceService(workspaceRepo, memberRepo, userRepo, kbRepo, cache, mock(PasswordEncoder.class));

        ws1 = workspace(1L, "空间一", 1L);
        ws2 = workspace(2L, "空间二", 5L);
        owner = member(10L, 1L, 1L, WorkspaceMember.ROLE_OWNER);
        when(workspaceRepo.findById(1L)).thenReturn(Optional.of(ws1));
        when(workspaceRepo.findById(2L)).thenReturn(Optional.of(ws2));
        when(workspaceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Workspace workspace(long id, String name, long ownerUserId) {
        Workspace w = new Workspace();
        w.setId(id);
        w.setName(name);
        w.setOwnerUserId(ownerUserId);
        return w;
    }

    private WorkspaceMember member(long id, long userId, long workspaceId, String role) {
        WorkspaceMember m = new WorkspaceMember();
        m.setId(id);
        m.setWorkspaceId(workspaceId);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    private SysUser user(long id, String username) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    // ===== 工作空间 =====

    @Test
    void createWorkspace_makesCreatorOwner() {
        WorkspaceResponse resp = service.createWorkspace(9L, " 我的团队 ");
        assertEquals("我的团队", resp.name());
        verify(workspaceRepo).save(any());
        verify(memberRepo).save(any());
        verify(cache).deleteByPattern(RedisKeys.memberListPattern(9L));
    }

    @Test
    void createWorkspace_rejectsBlankName() {
        assertThrows(BizException.class, () -> service.createWorkspace(9L, "   "));
    }

    @Test
    void renameWorkspace_onlyOwner() {
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(member(11L, 2L, 1L, WorkspaceMember.ROLE_ADMIN)));
        assertThrows(BizException.class, () -> service.renameWorkspace(2L, 1L, "新名"));
    }

    @Test
    void renameWorkspace_updatesName() {
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 1L)).thenReturn(Optional.of(owner));
        when(memberRepo.countByWorkspaceId(1L)).thenReturn(3L);
        WorkspaceResponse resp = service.renameWorkspace(1L, 1L, "新名");
        assertEquals("新名", resp.name());
        assertEquals(WorkspaceMember.ROLE_OWNER, resp.role());
    }

    @Test
    void info_returnsRoleAndMemberCount() {
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 1L)).thenReturn(Optional.of(owner));
        when(memberRepo.countByWorkspaceId(1L)).thenReturn(3L);
        WorkspaceResponse resp = service.info(1L, 1L);
        assertEquals("空间一", resp.name());
        assertEquals(WorkspaceMember.ROLE_OWNER, resp.role());
        assertEquals(3L, resp.memberCount());
    }

    @Test
    void info_notMember_throws() {
        assertThrows(BizException.class, () -> service.info(9L, 1L));
    }

    // ===== 创建成员 =====

    @Test
    void createMember_rejectsOwnerRole() {
        assertThrows(BizException.class,
                () -> service.createMember(1L, 1L, new CreateMemberRequest("u1", "123456", "OWNER")));
        verify(userRepo, never()).save(any());
    }

    @Test
    void createMember_rejectsDuplicateUsername() {
        when(userRepo.findByUsername("u1")).thenReturn(Optional.of(user(2L, "u1")));
        assertThrows(BizException.class,
                () -> service.createMember(1L, 1L, new CreateMemberRequest("u1", "123456", "EDITOR")));
    }

    @Test
    void createMember_createsUserAndMember() {
        when(userRepo.findByUsername("u1")).thenReturn(Optional.empty());
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MemberResponse resp = service.createMember(1L, 1L, new CreateMemberRequest("u1", "123456", "EDITOR"));

        assertEquals("EDITOR", resp.role());
        assertEquals("u1", resp.username());
        verify(memberRepo).save(any());
    }

    @Test
    void createMember_workspaceNotExist_throws() {
        when(workspaceRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(BizException.class,
                () -> service.createMember(1L, 99L, new CreateMemberRequest("u1", "123456", "EDITOR")));
    }

    // ===== 邀请已有用户 =====

    @Test
    void inviteMember_invitesExistingUser() {
        when(userRepo.findByUsername("u1")).thenReturn(Optional.of(user(2L, "u1")));
        MemberResponse resp = service.inviteMember(1L, 1L, new InviteMemberRequest("u1", "MEMBER"));
        assertEquals("MEMBER", resp.role());
        verify(memberRepo).save(any());
        verify(cache).deleteByPattern(RedisKeys.memberListPattern(2L));
    }

    @Test
    void inviteMember_unknownUser_throws() {
        when(userRepo.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThrows(BizException.class,
                () -> service.inviteMember(1L, 1L, new InviteMemberRequest("ghost", "MEMBER")));
    }

    @Test
    void inviteMember_duplicateMembership_throws() {
        when(userRepo.findByUsername("u1")).thenReturn(Optional.of(user(2L, "u1")));
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(member(11L, 2L, 1L, WorkspaceMember.ROLE_MEMBER)));
        assertThrows(BizException.class,
                () -> service.inviteMember(1L, 1L, new InviteMemberRequest("u1", "MEMBER")));
    }

    // ===== 角色修改 / 移除（限当前空间） =====

    @Test
    void updateRole_rejectsOwnerTarget() {
        WorkspaceMember target = member(11L, 2L, 1L, WorkspaceMember.ROLE_OWNER);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        assertThrows(BizException.class, () -> service.updateRole(1L, 1L, 11L, "ADMIN"));
    }

    @Test
    void updateRole_changesEditorToAdmin() {
        WorkspaceMember target = member(11L, 2L, 1L, WorkspaceMember.ROLE_EDITOR);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        when(userRepo.findById(2L)).thenReturn(Optional.of(user(2L, "u1")));

        MemberResponse resp = service.updateRole(1L, 1L, 11L, "ADMIN");
        assertEquals("ADMIN", resp.role());
    }

    @Test
    void updateRole_crossWorkspaceMember_rejected() {
        // 成员记录属于空间 2，但操作方在当前空间 1 → 防跨空间按 id 越权
        WorkspaceMember target = member(11L, 2L, 2L, WorkspaceMember.ROLE_EDITOR);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        assertThrows(BizException.class, () -> service.updateRole(1L, 1L, 11L, "ADMIN"));
    }

    @Test
    void removeMember_rejectsOwner() {
        WorkspaceMember target = member(11L, 2L, 1L, WorkspaceMember.ROLE_OWNER);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        assertThrows(BizException.class, () -> service.removeMember(1L, 1L, 11L));
        verify(memberRepo, never()).delete(any());
    }

    @Test
    void removeMember_removesEditor() {
        WorkspaceMember target = member(11L, 2L, 1L, WorkspaceMember.ROLE_EDITOR);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        service.removeMember(1L, 1L, 11L);
        verify(memberRepo).delete(target);
    }

    // ===== 转让 / 删除（限当前空间 + OWNER） =====

    @Test
    void transferOwnership_onlyOwnerCanTransfer() {
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(member(11L, 2L, 1L, WorkspaceMember.ROLE_ADMIN)));
        assertThrows(BizException.class, () -> service.transferOwnership(2L, 1L, 10L));
    }

    @Test
    void transferOwnership_swapsRoles() {
        WorkspaceMember target = member(11L, 2L, 1L, WorkspaceMember.ROLE_EDITOR);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 1L)).thenReturn(Optional.of(owner));
        when(userRepo.findById(2L)).thenReturn(Optional.of(user(2L, "u1")));

        MemberResponse resp = service.transferOwnership(1L, 1L, 11L);

        assertEquals(WorkspaceMember.ROLE_OWNER, resp.role());
        assertEquals(WorkspaceMember.ROLE_ADMIN, owner.getRole(), "原 Owner 应降为 ADMIN");
        assertEquals(2L, ws1.getOwnerUserId(), "工作空间拥有者应更新");
    }

    @Test
    void transferOwnership_targetInOtherWorkspace_rejected() {
        WorkspaceMember target = member(11L, 2L, 2L, WorkspaceMember.ROLE_EDITOR);
        when(memberRepo.findById(11L)).thenReturn(Optional.of(target));
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 1L)).thenReturn(Optional.of(owner));
        assertThrows(BizException.class, () -> service.transferOwnership(1L, 1L, 11L));
    }

    @Test
    void deleteWorkspace_onlyOwner() {
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(member(11L, 2L, 1L, WorkspaceMember.ROLE_ADMIN)));
        assertThrows(BizException.class, () -> service.deleteWorkspace(2L, 1L));
    }

    @Test
    void deleteWorkspace_archivesKbsAndRemovesMembers() {
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 1L)).thenReturn(Optional.of(owner));
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(5L);
        kb.setWorkspaceId(1L);
        when(kbRepo.findByWorkspaceIdAndArchivedFalseOrderByIdDesc(1L)).thenReturn(List.of(kb));
        when(memberRepo.findByWorkspaceIdOrderByIdDesc(1L)).thenReturn(List.of(owner));

        service.deleteWorkspace(1L, 1L);

        assertTrue(kb.getArchived(), "删除工作空间应归档其全部知识库");
        verify(workspaceRepo).delete(ws1);
        verify(memberRepo).deleteAll(any());
        verify(cache).deleteByPattern(RedisKeys.memberListPattern(1L));
    }

    @Test
    void deleteWorkspace_ownerOfOtherWorkspaceCannotDeleteThisOne() {
        // 用户在空间 2 是 OWNER，但在空间 1 无成员记录 → 删除空间 1 应被拒
        when(memberRepo.findByWorkspaceIdAndUserId(1L, 5L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.deleteWorkspace(5L, 1L));
    }
}
