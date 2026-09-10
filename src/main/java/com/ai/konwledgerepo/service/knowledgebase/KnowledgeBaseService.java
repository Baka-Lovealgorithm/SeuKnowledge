package com.ai.konwledgerepo.service.knowledgebase;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.KbCreateRequest;
import com.ai.konwledgerepo.dto.KbResponse;
import com.ai.konwledgerepo.dto.KbSnapshot;
import com.ai.konwledgerepo.dto.KbUpdateRequest;
import com.ai.konwledgerepo.entity.GroupMember;
import com.ai.konwledgerepo.entity.KbAccess;
import com.ai.konwledgerepo.entity.KbGroup;
import com.ai.konwledgerepo.entity.KbStatus;
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
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库业务：CRUD 与状态流转。
 * 缓存：kb:{id} 快照（问答热路径）、kb:list:{workspaceId} 列表、kb:count:{kbId} 文档计数；
 * 写路径统一失效，TTL 兜底防陈旧。
 * 内容清空见 {@link KnowledgeBaseContentService}（独立服务，避免本类与 VectorIngestionService 循环依赖）。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final RedisCacheService redisCacheService;
    private final WorkspaceAccess workspaceAccess;
    private final KbAccessRepository accessRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final KbGroupRepository groupRepository;
    private final Duration kbTtl;
    private final Duration kbCountTtl;
    private final Duration kbListTtl;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepository,
                                DocumentRepository documentRepository,
                                RedisCacheService redisCacheService,
                                WorkspaceAccess workspaceAccess,
                                KbAccessRepository accessRepository,
                                WorkspaceMemberRepository memberRepository,
                                GroupMemberRepository groupMemberRepository,
                                KbGroupRepository groupRepository,
                                SeuCacheProperties cacheProps) {
        this.kbRepository = kbRepository;
        this.documentRepository = documentRepository;
        this.redisCacheService = redisCacheService;
        this.workspaceAccess = workspaceAccess;
        this.accessRepository = accessRepository;
        this.memberRepository = memberRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRepository = groupRepository;
        this.kbTtl = Duration.ofSeconds(cacheProps.kbTtlSeconds());
        this.kbCountTtl = Duration.ofSeconds(cacheProps.kbCountTtlSeconds());
        this.kbListTtl = Duration.ofSeconds(cacheProps.kbListTtlSeconds());
    }

    /** 知识库写路径统一失效：快照 / 列表 / 文档计数（内容清空等重操作复用） */
    public void evictKbCache(Long id, Long workspaceId) {
        if (id != null) {
            redisCacheService.delete(RedisKeys.kb(id));
            redisCacheService.delete(RedisKeys.kbCount(id));
        }
        if (workspaceId != null) {
            redisCacheService.delete(RedisKeys.kbList(workspaceId));
        }
    }

    /**
     * 当前工作空间下的未归档知识库（列表缓存 kb:list:{workspaceId}，TTL 60s）。
     * 可见性过滤：OWNER/ADMIN 与知识库创建者可见全部；普通成员仅见 PUBLIC 与
     * 已授权（kb_access）的 RESTRICTED 知识库——"看不见"的知识库不出现在列表。
     */
    public List<KbResponse> list(Long workspaceId, Long userId) {
        List<KbResponse> cached = redisCacheService.get(RedisKeys.kbList(workspaceId),
                new com.fasterxml.jackson.core.type.TypeReference<List<KbResponse>>() {
                }).orElse(null);
        if (cached != null) {
            return filterVisible(cached, workspaceId, userId);
        }
        List<KbResponse> result = kbRepository.findByWorkspaceIdAndArchivedFalseOrderByIdDesc(workspaceId).stream()
                .map(this::toResponse)
                .toList();
        redisCacheService.set(RedisKeys.kbList(workspaceId), result, kbListTtl);
        return filterVisible(result, workspaceId, userId);
    }

    /**
     * 按用户过滤可见知识库：管理员/创建者全量；其余剔除未授权的 RESTRICTED 库。
     * 同时对可见项填充 canEdit（与写路径 ACL 同口径），供前端按行渲染编辑类按钮。
     */
    private List<KbResponse> filterVisible(List<KbResponse> all, Long workspaceId, Long userId) {
        if (userId == null) {
            return all;
        }
        boolean admin = isWorkspaceAdmin(workspaceId, userId);
        Set<Long> granted = admin ? null : grantedKbIds(workspaceId, userId);
        return all.stream()
                .filter(kb -> admin || isVisibleTo(kb, granted, userId))
                .map(kb -> kb.withCanEdit(
                        workspaceAccess.canEdit(kb.id(), workspaceId, userId, kb.createdBy(), kb.visibility())))
                .toList();
    }

    /**
     * 当前用户在该工作空间内可见的知识库 id 集合。
     * 供会话列表 / 抽取任务列表等「按知识库归属查询」的接口过滤，避免撤权后仍能读到旧数据。
     * 与 {@link #filterVisible} 同源，保证「列表可见」与「按 id 可访问」口径一致。
     */
    public Set<Long> visibleKbIds(Long workspaceId, Long userId) {
        return list(workspaceId, userId).stream()
                .map(KbResponse::id)
                .collect(Collectors.toSet());
    }

    /** 知识库对指定用户是否可见：PUBLIC 全空间可见；RESTRICTED 仅授权者与创建者可见 */
    private boolean isVisibleTo(KbResponse kb, Set<Long> granted, Long userId) {
        return !KbVisibility.isRestricted(kb.visibility())
                || granted.contains(kb.id())
                || (kb.createdBy() != null && kb.createdBy().equals(userId));
    }

    /** 是否空间 OWNER/ADMIN（管理员始终可见/可管理全部知识库） */
    private boolean isWorkspaceAdmin(Long workspaceId, Long userId) {
        String role = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole).orElse(null);
        return Roles.OWNER.equals(role) || Roles.ADMIN.equals(role);
    }

    /**
     * 用户已授权的知识库 id 集合（双粒度）：user 直授权 + 用户所属组（属当前工作空间）的组授权。
     */
    private Set<Long> grantedKbIds(Long workspaceId, Long userId) {
        Set<Long> granted = new HashSet<>();
        accessRepository.findByGranteeTypeAndGranteeId("USER", userId).stream()
                .map(KbAccess::getKbId)
                .forEach(granted::add);
        // 用户在当前工作空间内的所属组 → 这些组被授权的知识库
        Set<Long> workspaceGroupIds = groupRepository.findByWorkspaceIdOrderByIdAsc(workspaceId).stream()
                .map(KbGroup::getId)
                .collect(Collectors.toSet());
        Set<Long> userGroupIds = groupMemberRepository.findByUserId(userId).stream()
                .map(GroupMember::getGroupId)
                .filter(workspaceGroupIds::contains)
                .collect(Collectors.toSet());
        if (!userGroupIds.isEmpty()) {
            accessRepository.findByGranteeTypeAndGranteeIdIn("GROUP", userGroupIds).stream()
                    .map(KbAccess::getKbId)
                    .forEach(granted::add);
        }
        return granted;
    }

    public KbResponse create(KbCreateRequest request, Long userId, Long workspaceId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.name());
        kb.setDescription(request.description());
        kb.setStatus(KbStatus.DRAFT.value());
        kb.setVisibility(KbVisibility.PUBLIC.value());
        kb.setCreatedBy(userId);
        kb.setWorkspaceId(workspaceId);
        kbRepository.save(kb);
        evictKbCache(kb.getId(), workspaceId);
        return toResponse(kb);
    }

    public KbResponse update(Long id, KbUpdateRequest request, Long workspaceId) {
        KnowledgeBase kb = getInWorkspace(id, workspaceId);
        kb.setName(request.name());
        kb.setDescription(request.description());
        kbRepository.save(kb);
        evictKbCache(id, workspaceId);
        return toResponse(kb);
    }

    /** 删除 = 归档；级联清理知识库授权记录（ACL，派生删除需事务） */
    @Transactional
    public void delete(Long id, Long workspaceId) {
        KnowledgeBase kb = getInWorkspace(id, workspaceId);
        kb.setArchived(true);
        kbRepository.save(kb);
        accessRepository.deleteByKbId(id);
        evictKbCache(id, workspaceId);
    }

    public KbResponse updateStatus(Long id, String status, Long workspaceId) {
        if (!KbStatus.isValid(status)) {
            throw new BizException("非法的知识库状态: " + status);
        }
        KnowledgeBase kb = getInWorkspace(id, workspaceId);
        kb.setStatus(status);
        kbRepository.save(kb);
        evictKbCache(id, workspaceId);
        return toResponse(kb);
    }

    public KnowledgeBase getEntity(Long id) {
        return kbRepository.findById(id)
                .orElseThrow(() -> new BizException("知识库不存在"));
    }

    /** 校验知识库存在且属于当前工作空间（归属校验唯一入口：WorkspaceAccess） */
    public KnowledgeBase getInWorkspace(Long id, Long workspaceId) {
        return workspaceAccess.requireKb(id, workspaceId);
    }

    /**
     * 问答热路径的知识库读取（Redis 快照优先，miss 回源 DB 回填）。
     * 仅含问答所需字段（name / status / archived），不缓存完整实体。
     */
    public KnowledgeBase getEntityCached(Long id) {
        KbSnapshot cached = redisCacheService.get(RedisKeys.kb(id), KbSnapshot.class).orElse(null);
        if (cached != null) {
            KnowledgeBase kb = new KnowledgeBase();
            kb.setId(cached.id());
            kb.setName(cached.name());
            kb.setStatus(cached.status());
            kb.setArchived(cached.archived());
            kb.setWorkspaceId(cached.workspaceId());
            kb.setVisibility(cached.visibility());
            return kb;
        }
        KnowledgeBase kb = getEntity(id);
        redisCacheService.set(RedisKeys.kb(id), KbSnapshot.from(kb), kbTtl);
        return kb;
    }

    public KbResponse toResponse(KnowledgeBase kb) {
        Long docCount = cachedDocCount(kb.getId());
        return new KbResponse(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getStatus(),
                kb.getArchived(),
                docCount,
                kb.getCreatedAt(),
                kb.getVisibility(),
                kb.getCreatedBy());
    }

    /** 知识库文档计数（Redis 缓存 kb:count:{kbId}，TTL 300s；文档上传/删除后由 DocumentService 失效） */
    public Long cachedDocCount(Long kbId) {
        Long cached = redisCacheService.getString(RedisKeys.kbCount(kbId)).map(Long::valueOf).orElse(null);
        if (cached != null) {
            return cached;
        }
        long count = documentRepository.countByKbId(kbId);
        redisCacheService.setString(RedisKeys.kbCount(kbId), String.valueOf(count), kbCountTtl);
        return count;
    }

    /** 文档计数失效（DocumentService 上传/删除/重试后调用） */
    public void evictDocCount(Long kbId) {
        if (kbId != null) {
            redisCacheService.delete(RedisKeys.kbCount(kbId));
        }
    }

    /** 知识库列表失效（DocumentService 上传/删除/重试后调用，需工作空间 id） */
    public void evictKbList(Long workspaceId) {
        if (workspaceId != null) {
            redisCacheService.delete(RedisKeys.kbList(workspaceId));
        }
    }
}
