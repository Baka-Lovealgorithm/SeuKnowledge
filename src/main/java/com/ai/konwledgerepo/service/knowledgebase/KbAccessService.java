package com.ai.konwledgerepo.service.knowledgebase;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.dto.KbAccessRequest;
import com.ai.konwledgerepo.dto.KbAccessResponse;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KbVisibility;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KbGroupRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.SysUserRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.security.Roles;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 知识库对象级授权（ACL）业务：
 * 管理权 = 空间 OWNER/ADMIN 或知识库创建者；授权对象仅限当前工作空间成员（租户隔离，
 * 不暴露全局用户目录）；授权主体支持 USER（用户）与 GROUP（组，组内成员整体生效）；
 * permission 支持 VIEW（只读）/ EDIT（可读写）。
 */
@Service
public class KbAccessService {

    private final KbAccessRepository accessRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final SysUserRepository userRepository;
    private final KbGroupRepository groupRepository;
    private final WorkspaceAccess workspaceAccess;

    public KbAccessService(KbAccessRepository accessRepository,
                           KnowledgeBaseRepository kbRepository,
                           WorkspaceMemberRepository memberRepository,
                           SysUserRepository userRepository,
                           KbGroupRepository groupRepository,
                           WorkspaceAccess workspaceAccess) {
        this.accessRepository = accessRepository;
        this.kbRepository = kbRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.workspaceAccess = workspaceAccess;
    }

    /** 校验管理权并返回知识库（空间 ADMIN/OWNER 或创建者） */
    private KnowledgeBase requireManager(Long kbId, Long workspaceId, Long userId) {
        KnowledgeBase kb = workspaceAccess.requireKb(kbId, workspaceId);
        String role = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole).orElse(null);
        boolean admin = Roles.OWNER.equals(role) || Roles.ADMIN.equals(role);
        boolean creator = kb.getCreatedBy() != null && kb.getCreatedBy().equals(userId);
        if (!admin && !creator) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权管理该知识库权限（仅空间管理员或创建者可管理）");
        }
        return kb;
    }

    /** 授权列表（管理权人可见） */
    public List<KbAccessResponse> list(Long kbId, Long workspaceId, Long userId) {
        requireManager(kbId, workspaceId, userId);
        return accessRepository.findByKbIdOrderByIdAsc(kbId).stream()
                .map(a -> toResponse(a, userId))
                .toList();
    }

    /** 授予/更新用户或组权限（管理权人；USER 须为当前空间成员，GROUP 须为当前空间内的组） */
    @Transactional
    public KbAccessResponse grant(Long kbId, Long workspaceId, Long userId, KbAccessRequest request) {
        requireManager(kbId, workspaceId, userId);
        String granteeType = request.granteeType() == null ? "USER" : request.granteeType();
        Long granteeId = request.granteeId();
        validateGrantee(granteeType, granteeId, workspaceId, userId);
        String permission = request.permission() == null ? "VIEW" : request.permission();
        Optional<KbAccess> existing = accessRepository
                .findByKbIdAndGranteeTypeAndGranteeId(kbId, granteeType, granteeId);
        KbAccess acl;
        if (existing.isPresent()) {
            acl = existing.get();
            acl.setPermission(permission);
        } else {
            acl = new KbAccess();
            acl.setKbId(kbId);
            acl.setGranteeType(granteeType);
            acl.setGranteeId(granteeId);
            acl.setPermission(permission);
            acl.setCreatedBy(userId);
        }
        accessRepository.save(acl);
        return toResponse(acl, userId);
    }

    /**
     * 校验授权对象合法性：
     * USER → 不能向自己授权（创建者/管理员始终可访问）且须为当前空间成员；
     * GROUP → 组必须存在且属于当前工作空间（租户隔离）。
     */
    private void validateGrantee(String granteeType, Long granteeId, Long workspaceId, Long operatorId) {
        if ("GROUP".equals(granteeType)) {
            boolean groupInWorkspace = groupRepository.findByIdAndWorkspaceId(granteeId, workspaceId).isPresent();
            if (!groupInWorkspace) {
                throw new BizException("目标组不存在或不属于当前工作空间");
            }
            return;
        }
        if (granteeId.equals(operatorId)) {
            throw new BizException("不能向自己授权（创建者/管理员始终可访问）");
        }
        boolean granteeInWorkspace = memberRepository
                .findByWorkspaceIdAndUserId(workspaceId, granteeId).isPresent();
        if (!granteeInWorkspace) {
            throw new BizException("目标用户不是当前工作空间成员");
        }
    }

    /** 移除授权（管理权人） */
    @Transactional
    public void revoke(Long kbId, Long workspaceId, Long userId, Long accessId) {
        requireManager(kbId, workspaceId, userId);
        KbAccess acl = accessRepository.findById(accessId)
                .orElseThrow(() -> new BizException("授权记录不存在"));
        if (!acl.getKbId().equals(kbId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "授权记录不属于该知识库");
        }
        accessRepository.delete(acl);
    }

    /** 切换可见性：PUBLIC / RESTRICTED（管理权人） */
    @Transactional
    public KbVisibility setVisibility(Long kbId, Long workspaceId, Long userId, String visibility) {
        if (!"PUBLIC".equals(visibility) && !"RESTRICTED".equals(visibility)) {
            throw new BizException("可见性只能是 PUBLIC / RESTRICTED");
        }
        KnowledgeBase kb = requireManager(kbId, workspaceId, userId);
        kb.setVisibility(visibility);
        kbRepository.save(kb);
        return KbVisibility.PUBLIC.is(visibility) ? KbVisibility.PUBLIC : KbVisibility.RESTRICTED;
    }

    private KbAccessResponse toResponse(KbAccess acl, Long operatorId) {
        String name = "GROUP".equals(acl.getGranteeType())
                ? groupName(acl.getGranteeId())
                : username(acl.getGranteeId());
        return new KbAccessResponse(acl.getId(), acl.getKbId(), acl.getGranteeType(), acl.getGranteeId(),
                name, acl.getPermission(), acl.getCreatedAt());
    }

    private String username(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getUsername())
                .orElse("#" + userId);
    }

    private String groupName(Long groupId) {
        return groupRepository.findById(groupId)
                .map(g -> g.getName())
                .orElse("#" + groupId);
    }
}
