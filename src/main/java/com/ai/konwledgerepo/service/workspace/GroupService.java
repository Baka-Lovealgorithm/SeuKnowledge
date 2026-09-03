package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.dto.GroupMemberResponse;
import com.ai.konwledgerepo.dto.GroupRequest;
import com.ai.konwledgerepo.dto.GroupResponse;
import com.ai.konwledgerepo.entity.GroupMember;
import com.ai.konwledgerepo.entity.KbGroup;
import com.ai.konwledgerepo.repository.GroupMemberRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KbGroupRepository;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库访问组（组级别授权基础）业务。组归属工作空间，组内成员仅限当前工作空间成员
 * （租户隔离）；建组/改名/删组/成员增删均由空间 OWNER/ADMIN（控制器 @AdminOrAbove）操作。
 * 删除组时级联清理该组在 kb_access 的授权记录，避免悬空 grantee_id。
 */
@Service
public class GroupService {

    private final KbGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final SysUserRepository userRepository;
    private final KbAccessRepository accessRepository;

    public GroupService(KbGroupRepository groupRepository,
                        GroupMemberRepository memberRepository,
                        WorkspaceMemberRepository workspaceMemberRepository,
                        SysUserRepository userRepository,
                        KbAccessRepository accessRepository) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
    }

    /** 当前工作空间下的组列表（含成员数，按 id 升序） */
    public List<GroupResponse> listGroups(Long workspaceId, Long operatorId) {
        return groupRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).stream()
                .map(g -> new GroupResponse(g.getId(), g.getName(), g.getDescription(),
                        memberCount(g.getId()), g.getCreatedAt()))
                .toList();
    }

    @Transactional
    public GroupResponse createGroup(Long workspaceId, Long operatorId, GroupRequest request) {
        String name = trimName(request.name());
        if (groupRepository.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw new BizException("已存在同名组：" + name);
        }
        KbGroup group = new KbGroup();
        group.setWorkspaceId(workspaceId);
        group.setName(name);
        group.setDescription(request.description());
        group.setCreatedBy(operatorId);
        groupRepository.save(group);
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(), 0L,
                group.getCreatedAt());
    }

    @Transactional
    public GroupResponse renameGroup(Long workspaceId, Long operatorId, Long groupId, GroupRequest request) {
        KbGroup group = requireGroupOf(workspaceId, groupId);
        String name = trimName(request.name());
        if (!group.getName().equals(name)
                && groupRepository.existsByWorkspaceIdAndName(workspaceId, name)) {
            throw new BizException("已存在同名组：" + name);
        }
        group.setName(name);
        group.setDescription(request.description());
        groupRepository.save(group);
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                memberCount(groupId), group.getCreatedAt());
    }

    /** 删除组：级联清组成员关系 + 该组的知识库授权记录 */
    @Transactional
    public void deleteGroup(Long workspaceId, Long operatorId, Long groupId) {
        KbGroup group = requireGroupOf(workspaceId, groupId);
        memberRepository.deleteByGroupId(group.getId());
        accessRepository.deleteByGranteeTypeAndGranteeId("GROUP", group.getId());
        groupRepository.delete(group);
    }

    /** 组内成员列表（含用户名） */
    public List<GroupMemberResponse> listMembers(Long workspaceId, Long operatorId, Long groupId) {
        KbGroup group = requireGroupOf(workspaceId, groupId);
        return memberRepository.findByGroupIdOrderByIdAsc(group.getId()).stream()
                .map(m -> new GroupMemberResponse(m.getUserId(),
                        username(m.getUserId()), m.getCreatedAt()))
                .toList();
    }

    /** 添加成员：目标用户必须是当前工作空间成员（可选：防重复入组） */
    @Transactional
    public GroupMemberResponse addMember(Long workspaceId, Long operatorId, Long groupId, Long userId) {
        KbGroup group = requireGroupOf(workspaceId, groupId);
        boolean inWorkspace = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, userId).isPresent();
        if (!inWorkspace) {
            throw new BizException("目标用户不是当前工作空间成员");
        }
        if (memberRepository.findByGroupIdAndUserId(group.getId(), userId).isPresent()) {
            throw new BizException("该用户已在组内");
        }
        GroupMember member = new GroupMember();
        member.setGroupId(group.getId());
        member.setUserId(userId);
        member.setCreatedBy(operatorId);
        memberRepository.save(member);
        return new GroupMemberResponse(userId, username(userId), member.getCreatedAt());
    }

    /** 移除成员 */
    @Transactional
    public void removeMember(Long workspaceId, Long operatorId, Long groupId, Long userId) {
        KbGroup group = requireGroupOf(workspaceId, groupId);
        GroupMember member = memberRepository.findByGroupIdAndUserId(group.getId(), userId)
                .orElseThrow(() -> new BizException("该用户不在组内"));
        memberRepository.delete(member);
    }

    // ===== 内部辅助 =====

    /** 组必须属于当前工作空间（防跨空间按 id 操作） */
    private KbGroup requireGroupOf(Long workspaceId, Long groupId) {
        return groupRepository.findByIdAndWorkspaceId(groupId, workspaceId)
                .orElseThrow(() -> new BizException(ErrorCodes.FORBIDDEN, "无权操作该组"));
    }

    private long memberCount(Long groupId) {
        return memberRepository.countByGroupId(groupId);
    }

    private String trimName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new BizException("组名不能为空且不超过 64 字");
        }
        return trimmed;
    }

    private String username(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getUsername())
                .orElse("#" + userId);
    }
}