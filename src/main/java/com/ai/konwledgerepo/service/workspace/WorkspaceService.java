package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 工作空间与成员管理（多工作空间）：
 * - 任意已登录用户可创建工作空间（创建者成为 OWNER）；
 * - 拥有者 Owner（每空间唯一）：成员管理 + 转让/删除/重命名工作空间等最终控制权；
 * - 管理员 Admin：管理团队成员（不能更改/移除 Owner）；
 * - 编辑者 Editor / 普通成员 Member：见各接口权限矩阵；
 * - 所有成员操作均限定在「当前工作空间」内，防跨空间按 id 越权。
 */
@Service
public class WorkspaceService {

    /** 创建/邀请成员时允许指定的角色（OWNER 唯一，通过转让产生） */
    private static final Set<String> ASSIGNABLE_ROLES =
            Set.of(WorkspaceMember.ROLE_ADMIN, WorkspaceMember.ROLE_EDITOR, WorkspaceMember.ROLE_MEMBER);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final SysUserRepository userRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final RedisCacheService redisCacheService;
    private final PasswordEncoder passwordEncoder;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository memberRepository,
                            SysUserRepository userRepository,
                            KnowledgeBaseRepository kbRepository,
                            RedisCacheService redisCacheService,
                            PasswordEncoder passwordEncoder) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.kbRepository = kbRepository;
        this.redisCacheService = redisCacheService;
        this.passwordEncoder = passwordEncoder;
    }

    /** 成员角色/归属变更后按模式失效其鉴权缓存（多空间全部条目），保证权限即时生效 */
    private void evictMemberCache(Long userId) {
        if (userId != null) {
            redisCacheService.deleteByPattern(RedisKeys.memberListPattern(userId));
        }
    }

    // ===== 工作空间 =====

    /** 创建工作空间：创建者成为 OWNER（任意已登录用户可创建） */
    @Transactional
    public WorkspaceResponse createWorkspace(Long userId, String name) {
        String trimmed = trimName(name);
        Workspace workspace = new Workspace();
        workspace.setName(trimmed);
        workspace.setOwnerUserId(userId);
        workspaceRepository.save(workspace);

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(userId);
        member.setRole(WorkspaceMember.ROLE_OWNER);
        memberRepository.save(member);
        evictMemberCache(userId);
        return toResponse(workspace, WorkspaceMember.ROLE_OWNER);
    }

    /** 重命名工作空间；仅 OWNER */
    @Transactional
    public WorkspaceResponse renameWorkspace(Long operatorId, Long workspaceId, String name) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireOwner(operatorId, workspaceId);
        workspace.setName(trimName(name));
        workspaceRepository.save(workspace);
        return toResponse(workspace, requireMember(operatorId, workspaceId).getRole());
    }

    /** 当前工作空间信息（名称/角色/成员数）；成员可访问 */
    public WorkspaceResponse info(Long operatorId, Long workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        WorkspaceMember member = requireMember(operatorId, workspaceId);
        return toResponse(workspace, member.getRole());
    }

    /** 删除工作空间：归档全部知识库并移除全部成员；仅 OWNER */
    @Transactional
    public void deleteWorkspace(Long operatorId, Long workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireOwner(operatorId, workspaceId);
        List<KnowledgeBase> kbs = kbRepository.findByWorkspaceIdAndArchivedFalseOrderByIdDesc(workspaceId);
        for (KnowledgeBase kb : kbs) {
            kb.setArchived(true);
        }
        kbRepository.saveAll(kbs);
        List<WorkspaceMember> members = memberRepository.findByWorkspaceIdOrderByIdDesc(workspaceId);
        members.forEach(m -> evictMemberCache(m.getUserId()));
        memberRepository.deleteAll(members);
        workspaceRepository.delete(workspace);
    }

    // ===== 成员管理（当前工作空间内） =====

    /** 成员列表（含用户名；调用方需为 OWNER/ADMIN） */
    public List<MemberResponse> listMembers(Long operatorId, Long workspaceId) {
        requireWorkspace(workspaceId);
        return memberRepository.findByWorkspaceIdOrderByIdDesc(workspaceId).stream()
                .map(m -> toResponse(m, userRepository.findById(m.getUserId()).orElse(null)))
                .toList();
    }

    /** 创建成员（指定角色；调用方需为 OWNER/ADMIN）：创建账号并加入当前工作空间 */
    @Transactional
    public MemberResponse createMember(Long operatorId, Long workspaceId, CreateMemberRequest request) {
        requireWorkspace(workspaceId);
        validateAssignableRole(request.role());
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new BizException("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole("MEMBER");
        user.setEnabled(true);
        userRepository.save(user);

        WorkspaceMember member = saveMembership(workspaceId, user.getId(), request.role());
        return toResponse(member, user);
    }

    /** 邀请已有用户加入当前工作空间（按用户名；调用方需为 OWNER/ADMIN） */
    @Transactional
    public MemberResponse inviteMember(Long operatorId, Long workspaceId, InviteMemberRequest request) {
        requireWorkspace(workspaceId);
        validateAssignableRole(request.role());
        SysUser user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BizException("用户不存在：" + request.username()));
        WorkspaceMember member = saveMembership(workspaceId, user.getId(), request.role());
        return toResponse(member, user);
    }

    /** 修改成员角色；调用方需为 OWNER/ADMIN，且不可修改/移除拥有者 */
    @Transactional
    public MemberResponse updateRole(Long operatorId, Long workspaceId, Long memberId, String newRole) {
        requireWorkspace(workspaceId);
        validateAssignableRole(newRole);
        WorkspaceMember target = requireMemberOf(workspaceId, memberId);
        if (WorkspaceMember.ROLE_OWNER.equals(target.getRole())) {
            throw new BizException("不能修改拥有者的角色（转让请使用转让功能）");
        }
        target.setRole(newRole);
        memberRepository.save(target);
        evictMemberCache(target.getUserId());
        return toResponse(target, userRepository.findById(target.getUserId()).orElse(null));
    }

    /** 移除成员；调用方需为 OWNER/ADMIN，且不可移除拥有者 */
    @Transactional
    public void removeMember(Long operatorId, Long workspaceId, Long memberId) {
        requireWorkspace(workspaceId);
        WorkspaceMember target = requireMemberOf(workspaceId, memberId);
        if (WorkspaceMember.ROLE_OWNER.equals(target.getRole())) {
            throw new BizException("不能移除拥有者");
        }
        memberRepository.delete(target);
        evictMemberCache(target.getUserId());
    }

    /** 转让拥有权：原 Owner 降为 ADMIN，目标成员成为新的 Owner；仅 Owner 可操作 */
    @Transactional
    public MemberResponse transferOwnership(Long operatorId, Long workspaceId, Long targetMemberId) {
        Workspace workspace = requireWorkspace(workspaceId);
        requireOwner(operatorId, workspaceId);
        WorkspaceMember target = requireMemberOf(workspaceId, targetMemberId);
        if (WorkspaceMember.ROLE_OWNER.equals(target.getRole())) {
            throw new BizException("目标已是拥有者");
        }
        WorkspaceMember currentOwner = requireMember(operatorId, workspaceId);
        currentOwner.setRole(WorkspaceMember.ROLE_ADMIN);
        memberRepository.save(currentOwner);
        target.setRole(WorkspaceMember.ROLE_OWNER);
        memberRepository.save(target);
        workspace.setOwnerUserId(target.getUserId());
        workspaceRepository.save(workspace);
        evictMemberCache(currentOwner.getUserId());
        evictMemberCache(target.getUserId());
        return toResponse(target, userRepository.findById(target.getUserId()).orElse(null));
    }

    // ===== 内部辅助 =====

    private WorkspaceMember saveMembership(Long workspaceId, Long userId, String role) {
        if (memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId).isPresent()) {
            throw new BizException("该用户已是本工作空间成员");
        }
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        memberRepository.save(member);
        evictMemberCache(userId);
        return member;
    }

    private void validateAssignableRole(String role) {
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new BizException("角色只能是 ADMIN / EDITOR / MEMBER（拥有者唯一，通过转让产生）");
        }
    }

    private Workspace requireWorkspace(Long workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BizException("工作空间不存在"));
    }

    /** 当前用户在指定工作空间的成员记录（不存在即无权） */
    private WorkspaceMember requireMember(Long userId, Long workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BizException(ErrorCodes.FORBIDDEN, "无权访问该工作空间"));
    }

    /** 指定成员记录必须属于当前工作空间（防跨空间按 id 操作） */
    private WorkspaceMember requireMemberOf(Long workspaceId, Long memberId) {
        WorkspaceMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BizException("成员不存在"));
        if (!member.getWorkspaceId().equals(workspaceId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权操作该成员");
        }
        return member;
    }

    private void requireOwner(Long operatorId, Long workspaceId) {
        WorkspaceMember member = requireMember(operatorId, workspaceId);
        if (!WorkspaceMember.ROLE_OWNER.equals(member.getRole())) {
            throw new BizException(ErrorCodes.FORBIDDEN, "仅拥有者可执行该操作");
        }
    }

    private String trimName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            throw new BizException("工作空间名称不能为空且不超过 128 字");
        }
        return trimmed;
    }

    private WorkspaceResponse toResponse(Workspace workspace, String role) {
        long memberCount = memberRepository.countByWorkspaceId(workspace.getId());
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), role, memberCount);
    }

    private MemberResponse toResponse(WorkspaceMember m, SysUser user) {
        return new MemberResponse(m.getId(), m.getUserId(),
                user == null ? null : user.getUsername(), m.getRole(), m.getCreatedAt());
    }
}
