package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.GroupMemberResponse;
import com.ai.konwledgerepo.dto.GroupRequest;
import com.ai.konwledgerepo.dto.GroupResponse;
import com.ai.konwledgerepo.entity.GroupMember;
import com.ai.konwledgerepo.entity.KbGroup;
import com.ai.konwledgerepo.entity.SysUser;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.GroupMemberRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KbGroupRepository;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库访问组业务测试：建组/改名/删组、成员增删、租户隔离（跨空间组拒绝）、
 * 成员池限定当前工作空间成员、删组级联清理授权。
 */
class GroupServiceTest {

    private static final long WS = 10L;
    private static final long OTHER_WS = 99L;
    private static final long OPERATOR = 2L;   // ADMIN
    private static final long MEMBER_USER = 3L; // 空间普通成员
    private static final long OUTSIDER = 4L;    // 非空间成员
    private static final long GROUP = 20L;

    private KbGroupRepository groupRepo;
    private GroupMemberRepository memberRepo;
    private WorkspaceMemberRepository wsMemberRepo;
    private SysUserRepository userRepo;
    private KbAccessRepository accessRepo;
    private GroupService service;

    @BeforeEach
    void setUp() {
        groupRepo = mock(KbGroupRepository.class);
        memberRepo = mock(GroupMemberRepository.class);
        wsMemberRepo = mock(WorkspaceMemberRepository.class);
        userRepo = mock(SysUserRepository.class);
        accessRepo = mock(KbAccessRepository.class);
        service = new GroupService(groupRepo, memberRepo, wsMemberRepo, userRepo, accessRepo);
    }

    private KbGroup group(Long id, Long workspaceId, String name) {
        KbGroup g = new KbGroup();
        g.setId(id);
        g.setWorkspaceId(workspaceId);
        g.setName(name);
        return g;
    }

    private WorkspaceMember member(Long userId, String role) {
        WorkspaceMember m = new WorkspaceMember();
        m.setWorkspaceId(WS);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void createGroup_ok() {
        when(groupRepo.existsByWorkspaceIdAndName(WS, "研发组")).thenReturn(false);
        when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GroupResponse resp = service.createGroup(WS, OPERATOR, new GroupRequest("研发组", "desc"));
        assertEquals("研发组", resp.name());
        assertEquals(0L, resp.memberCount());
    }

    @Test
    void createGroup_duplicateName_throws() {
        when(groupRepo.existsByWorkspaceIdAndName(WS, "研发组")).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.createGroup(WS, OPERATOR, new GroupRequest("研发组", null)));
        assertEquals("已存在同名组：研发组", ex.getMessage());
    }

    @Test
    void renameGroup_crossWorkspace_forbidden() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.renameGroup(WS, OPERATOR, GROUP, new GroupRequest("新名", null)));
        assertEquals(403, ex.getCode());
    }

    @Test
    void deleteGroup_cascadesMembersAndAccess() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.of(group(GROUP, WS, "研发组")));

        service.deleteGroup(WS, OPERATOR, GROUP);

        verify(memberRepo).deleteByGroupId(GROUP);
        verify(accessRepo).deleteByGranteeTypeAndGranteeId("GROUP", GROUP);
        verify(groupRepo).delete(any(KbGroup.class));
    }

    @Test
    void addMember_ok() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.of(group(GROUP, WS, "研发组")));
        when(wsMemberRepo.findByWorkspaceIdAndUserId(WS, MEMBER_USER))
                .thenReturn(Optional.of(member(MEMBER_USER, "MEMBER")));
        when(memberRepo.findByGroupIdAndUserId(GROUP, MEMBER_USER)).thenReturn(Optional.empty());
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SysUser u = new SysUser();
        u.setUsername("bob");
        when(userRepo.findById(MEMBER_USER)).thenReturn(Optional.of(u));

        GroupMemberResponse resp = service.addMember(WS, OPERATOR, GROUP, MEMBER_USER);
        assertEquals(MEMBER_USER, resp.userId());
        assertEquals("bob", resp.username());
    }

    @Test
    void addMember_notWorkspaceMember_forbidden() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.of(group(GROUP, WS, "研发组")));
        when(wsMemberRepo.findByWorkspaceIdAndUserId(WS, OUTSIDER)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.addMember(WS, OPERATOR, GROUP, OUTSIDER));
        assertEquals("目标用户不是当前工作空间成员", ex.getMessage());
    }

    @Test
    void addMember_duplicate_throws() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.of(group(GROUP, WS, "研发组")));
        when(wsMemberRepo.findByWorkspaceIdAndUserId(WS, MEMBER_USER))
                .thenReturn(Optional.of(member(MEMBER_USER, "MEMBER")));
        when(memberRepo.findByGroupIdAndUserId(GROUP, MEMBER_USER))
                .thenReturn(Optional.of(new GroupMember()));

        BizException ex = assertThrows(BizException.class,
                () -> service.addMember(WS, OPERATOR, GROUP, MEMBER_USER));
        assertEquals("该用户已在组内", ex.getMessage());
    }

    @Test
    void listMembers_crossWorkspaceGroup_forbidden() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.listMembers(WS, OPERATOR, GROUP));
        assertEquals(403, ex.getCode());
    }

    @Test
    void removeMember_notInGroup_throws() {
        when(groupRepo.findByIdAndWorkspaceId(GROUP, WS)).thenReturn(Optional.of(group(GROUP, WS, "研发组")));
        when(memberRepo.findByGroupIdAndUserId(GROUP, MEMBER_USER)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> service.removeMember(WS, OPERATOR, GROUP, MEMBER_USER));
        assertEquals("该用户不在组内", ex.getMessage());
    }

    @Test
    void listGroups_includesMemberCount() {
        when(groupRepo.findByWorkspaceIdOrderByIdAsc(WS)).thenReturn(List.of(group(GROUP, WS, "研发组")));
        when(memberRepo.countByGroupId(GROUP)).thenReturn(3L);

        List<GroupResponse> resp = service.listGroups(WS, OPERATOR);
        assertEquals(1, resp.size());
        assertEquals("研发组", resp.get(0).name());
        assertEquals(3L, resp.get(0).memberCount());
    }
}