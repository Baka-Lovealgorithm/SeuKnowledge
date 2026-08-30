package com.ai.konwledgerepo.service.workspace;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KbVisibility;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.WorkspaceMember;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KbAccessRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.WorkspaceMemberRepository;
import com.ai.konwledgerepo.security.Roles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

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
 *   <li>RESTRICTED 知识库：须命中 kb_access 授权（VIEW 可读、EDIT 可读写），否则 403。</li>
 * </ol>
 * 生效方式：
 * <ul>
 *   <li>{@link #requireKb}/{@link #requireDoc}：归属校验 + 自动 ACL——从请求上下文
 *       （AuthInterceptor 注入的 userId / role）读取调用者，按 HTTP method 区分读写
 *       （GET/HEAD/OPTIONS 为读，其余为写）；无请求上下文（异步线程、单元测试）
 *       时仅归属校验，保证系统内部任务不受影响；</li>
 *   <li>{@link #requireKbAccess}/{@link #requireDocAccess}：显式 ACL——调用方传入
 *       userId 与读写语义，用于问答热路径等 method 语义与真实读写不一致的场景。</li>
 * </ul>
 */
@Component
public class WorkspaceAccess {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final KbAccessRepository accessRepository;
    private final WorkspaceMemberRepository memberRepository;

    public WorkspaceAccess(KnowledgeBaseRepository kbRepository,
                           DocumentRepository documentRepository,
                           KbAccessRepository accessRepository,
                           WorkspaceMemberRepository memberRepository) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.accessRepository = accessRepository;
        this.memberRepository = memberRepository;
    }

    /** 校验知识库存在且属于当前工作空间，返回该知识库 */
    public KnowledgeBase requireKb(Long kbId, Long workspaceId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new BizException("知识库不存在"));
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(workspaceId)) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权访问该知识库");
        }
        applyAutoAcl(kb, workspaceId);
        return kb;
    }

    /**
     * 完整鉴权（归属 + ACL）：显式指定调用者与读写语义。
     *
     * @param write 写语义：true 要求 EDIT 授权；false 要求 VIEW 或 EDIT 授权
     */
    public KnowledgeBase requireKbAccess(Long kbId, Long workspaceId, Long userId, boolean write) {
        KnowledgeBase kb = requireKb(kbId, workspaceId);
        if (userId != null) {
            checkAcl(kb, workspaceId, userId, write);
        }
        return kb;
    }

    /** 校验文档存在且其知识库属于当前工作空间 */
    public Document requireDoc(Long docId, Long workspaceId) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        requireKb(doc.getKbId(), workspaceId);
        return doc;
    }

    /** 完整鉴权（归属 + ACL）的文档版本 */
    public Document requireDocAccess(Long docId, Long workspaceId, Long userId, boolean write) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new BizException("文档不存在"));
        requireKbAccess(doc.getKbId(), workspaceId, userId, write);
        return doc;
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

    /** 核心 ACL 判定：管理员/创建者免检 → PUBLIC 放行 → RESTRICTED 查 kb_access */
    private void checkAcl(KnowledgeBase kb, Long workspaceId, Long userId, boolean write) {
        String role = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole).orElse(null);
        if (Roles.OWNER.equals(role) || Roles.ADMIN.equals(role)) {
            return; // 管理员兜底
        }
        if (kb.getCreatedBy() != null && kb.getCreatedBy().equals(userId)) {
            return; // 创建者委派
        }
        if (!KbVisibility.isRestricted(kb.getVisibility())) {
            return; // PUBLIC：空间成员按角色访问
        }
        // RESTRICTED：须命中 kb_access 授权
        Optional<KbAccess> acl = accessRepository
                .findByKbIdAndGranteeTypeAndGranteeId(kb.getId(), "USER", userId);
        if (acl.isEmpty()) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权访问该知识库");
        }
        if (write && !"EDIT".equals(acl.get().getPermission())) {
            throw new BizException(ErrorCodes.FORBIDDEN, "无权编辑该知识库（仅授权只读）");
        }
    }
}
