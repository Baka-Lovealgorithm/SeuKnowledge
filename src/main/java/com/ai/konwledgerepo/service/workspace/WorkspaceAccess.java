package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KbVisibility;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.GroupMemberRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KbGroupRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.security.Roles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作空间归属校验 + 知识库对象级权限（ACL）校验：
 * 所有按知识库/文档 id 的访问先校验其属于当前工作空间，防止跨工作空间访问；
 * RESTRICTED 知识库进一步校验调用用户是否被显式授权（kb_access）。
 * <p>
 * ACL 校验规则：
 * <ol>
 *   <li>空间 OWNER / ADMIN 始终可访问（管理员兜底，不受 ACL 限制）；</li>
 *   <li>知识库创建者始终可访问（创建者委派管理权）；</li>
 *   <li>PUBLIC 知识库：空间成员按角色访问（现状行为）；</li>
 *   <li>RESTRICTED 知识库：双粒度授权——命中 kb_access 的 user 直授（USER）或所在组
 *       授权（GROUP）即可访问，取最高权限（EDIT 覆盖 VIEW，仅有 VIEW 则只读），否则 403。</li>
 * </ol>
 * 生效方式：
 * <ul>
 *   <li>{@link #requireKb}/{@link #requireDoc}：归属校验 + 自动 ACL——从请求上下文
 *       （AuthInterceptor 注入的 userId / role）读取调用者，按 HTTP method 区分读写
 *       （GET/HEAD/OPTIONS 为读，其余为写）；无请求上下文（异步线程、单元测试）
 *       时仅归属校验，保证系统内部任务不受影响；</li>
 *   <li>{@link #requireKbAccess}/{@link #requireDocAccess}：显式 ACL——调用方传入
 *       userId 与读写语义，用于问答热路径等 method 语义与真实读写不一致的场景。
 *       二者不经过自动 ACL（不做 method 推断），仅按传入的 write 语义判定一次。</li>
 * </ul>
 * <p>
 * 另提供 {@link #canEdit} 作为「只读判定」：不抛异常，供列表接口按用户回吐可编辑标记，
 * 与写路径的 ACL 判定共用 {@link #effectivePermission} 同一口径，避免前后端两套规则。
 */
@Component
public class WorkspaceAccess {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final KbAccessRepository accessRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final KbGroupRepository groupRepository;

    public WorkspaceAccess(KnowledgeBaseRepository kbRepository,
                           DocumentRepository documentRepository,
                           KbAccessRepository accessRepository,
                           WorkspaceMemberRepository memberRepository,
                           GroupMemberRepository groupMemberRepository,
                           KbGroupRepository groupRepository) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.accessRepository = accessRepository;
        this.memberRepository = memberRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRepository = groupRepository;
    }

    /** 校验知识库存在且属于当前工作空间，返回该知识库（含自动 ACL） */
    public KnowledgeBase requireKb(Long kbId, Long workspaceId) {
        KnowledgeBase kb = loadKbInWorkspace(kbId, workspaceId);
        applyAutoAcl(kb, workspaceId);
        return kb;
    }

    /**
     * 完整鉴权（归属 + ACL）：显式指定调用者与读写语义。
     * <p>
     * 实现上直接查库并只做一次 ACL 判定，不委托 {@link #requireKb}——后者会额外跑一次
     * 基于 HTTP method 的自动 ACL，对显式路径既冗余又可能在 method 语义与传参不一致时误伤
     * （如 POST + write=false 的只读接口被 method 推断当成写而拒绝）。
     *
     * @param write 写语义：true 要求 EDIT 授权；false 要求 VIEW 或 EDIT 授权
     */
    public KnowledgeBase requireKbAccess(Long kbId, Long workspaceId, Long userId, boolean write) {
        KnowledgeBase kb = loadKbInWorkspace(kbId, workspaceId);
        if (userId != null) {
            checkAcl(kb, workspaceId, userId, write);
        }
        return kb;
    }

    /** 校验文档存在且其知识库属于当前工作空间（含自动 ACL） */
    public Document requireDoc(Long docId, Long workspaceId) {
        Document doc = loadDoc(docId);
        requireKb(doc.getKbId(), workspaceId);
        return doc;
    }

    /** 完整鉴权（归属 + ACL）的文档版本（单次 ACL 判定） */
    public Document requireDocAccess(Long docId, Long workspaceId, Long userId, boolean write) {
        Document doc = loadDoc(docId);
        requireKbAccess(doc.getKbId(), workspaceId, userId, write);
        return doc;
    }

    /**
     * 只读的可编辑判定（不抛异常）：与写路径同一口径。
     * 供列表接口回吐 canEdit 使用，使前端按行渲染按钮与服务端写校验保持一致。
     *
     * @return true 表示该用户对该知识库具备写权限（管理员/创建者/EDIT 授权）
     */
    public boolean canEdit(KnowledgeBase kb, Long workspaceId, Long userId) {
        if (kb == null || userId == null) {
            return false;
        }
        return "EDIT".equals(effectivePermission(kb, workspaceId, userId));
    }

    /**
     * 可编辑判定（字段版）：供只有 id / 可见性 / 创建者的场景复用，避免为判定构造实体。
     * 与 {@link #canEdit(KnowledgeBase, Long, Long)} 共用同一 {@link #effectivePermission} 口径。
     */
    public boolean canEdit(Long kbId, Long workspaceId, Long userId, Long createdBy, String visibility) {
        if (kbId == null || userId == null) {
            return false;
        }
        KnowledgeBase probe = new KnowledgeBase();
        probe.setId(kbId);
        probe.setVisibility(visibility);
        probe.setCreatedBy(createdBy);
        return "EDIT".equals(effectivePermission(probe, workspaceId, userId));
    }

    /** 知识库加载 + 工作空间归属校验（不含 ACL） */
    private KnowledgeBase loadKbInWorkspace(Long kbId, Long workspaceId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException("知识库不存在"));
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(workspaceId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权访问该知识库");
        }
        return kb;
    }

    private Document loadDoc(Long docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
    }

    /**
     * 通用归属谓词：校验已加载资源的工作空间归属（资源实体本身不带 kbId 的场景，如模型配置/会话）。
     * 资源 workspaceId 为空（历史数据未回填）时视为可访问，与 requireKb 语义一致。
     */
    public void requireBelongs(Long resourceWorkspaceId, Long workspaceId, String deniedMessage) {
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(workspaceId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, deniedMessage);
        }
    }

    // ===== ACL 内部实现 =====

    /** 从请求上下文读取调用者并叠加 ACL（GET/HEAD/OPTIONS 视为读，其余视为写） */
    private void applyAutoAcl(KnowledgeBase kb, Long workspaceId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return; // 非请求线程（异步任务/单元测试）：仅归属校验
        }
        HttpServletRequest request = sra.getRequest();
        Object userIdAttr = request.getAttribute("userId");
        if (!(userIdAttr instanceof Long userId)) {
            return;
        }
        String method = request.getMethod();
        boolean write = !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
        checkAcl(kb, workspaceId, userId, write);
    }

    /**
     * 核心 ACL 判定：管理员/创建者免检 → PUBLIC 放行 → RESTRICTED 双粒度（user 直授 ∪ 组授权）。
     * 合并规则 = 取最高权限：任一来源给到 EDIT 即可编辑，只有全部来源都是 VIEW 才算只读。
     */
    private void checkAcl(KnowledgeBase kb, Long workspaceId, Long userId, boolean write) {
        String effective = effectivePermission(kb, workspaceId, userId);
        if (effective == null) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权访问该知识库");
        }
        if (write && !"EDIT".equals(effective)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权编辑该知识库（仅授权只读）");
        }
    }

    /**
     * 计算用户对知识库的有效权限（"EDIT" / "VIEW" / null=无权）。
     * 与 {@link #checkAcl} 同源，供 {@link #canEdit} 等只读判定复用，保证口径唯一。
     */
    private String effectivePermission(KnowledgeBase kb, Long workspaceId, Long userId) {
        String role = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole).orElse(null);
        if (Roles.OWNER.equals(role) || Roles.ADMIN.equals(role)) {
            return "EDIT"; // 管理员兜底
        }
        if (role == null) {
            return null; // 非本空间成员：无任何权限
        }
        if (kb.getCreatedBy() != null && kb.getCreatedBy().equals(userId)) {
            return "EDIT"; // 创建者委派
        }
        if (!KbVisibility.isRestricted(kb.getVisibility())) {
            // PUBLIC：空间成员按角色访问；EDITOR 及以上可写，MEMBER 只读
            return Roles.EDITOR.equals(role) ? "EDIT" : "VIEW";
        }
        // RESTRICTED：双粒度——user 直授 ∪ 所在组授权，取最高权限
        String effective = userGrant(kb.getId(), userId);
        Map<Long, String> groupGrants = kbGroupGrants(kb.getId());
        for (Long groupId : groupGrants.keySet()) {
            if (userBelongsToGroupInWorkspace(workspaceId, groupId, userId)) {
                effective = higher(effective, groupGrants.get(groupId));
            }
        }
        return effective;
    }

    /** user 级直授权限（无命中返回 null） */
    private String userGrant(Long kbId, Long userId) {
        return accessRepository
                .findByKbIdAndGranteeTypeAndGranteeId(kbId, "USER", userId)
                .map(KbAccess::getPermission)
                .orElse(null);
    }

    /** 该知识库的 GROUP 授权映射：groupId → permission */
    private Map<Long, String> kbGroupGrants(Long kbId) {
        return accessRepository.findByKbIdAndGranteeType(kbId, "GROUP").stream()
                .collect(Collectors.toMap(KbAccess::getGranteeId, KbAccess::getPermission, (a, b) -> higher(a, b)));
    }

    /**
     * 用户是否属于「属于当前工作空间的」组 groupId 的成员。
     * 先确证组归当前空间（防跨空间授权被误判），再查成员关系。
     */
    private boolean userBelongsToGroupInWorkspace(Long workspaceId, Long groupId, Long userId) {
        boolean groupInWorkspace = groupRepository.findByIdAndWorkspaceId(groupId, workspaceId).isPresent();
        return groupInWorkspace
                && groupMemberRepository.findByGroupIdAndUserId(groupId, userId).isPresent();
    }

    /** 权限取高：EDIT 优先于 VIEW；两者皆空返回 null */
    private static String higher(String current, String candidate) {
        if (candidate == null) {
            return current;
        }
        if ("EDIT".equals(candidate)) {
            return "EDIT";
        }
        return current == null ? "VIEW" : current;
    }
}
