package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.KbCreateRequest;
import com.ai.konwledgerepo.dto.KbResponse;
import com.ai.konwledgerepo.dto.KbUpdateRequest;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseContentService;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理接口。
 * 读：MEMBER+（可见性过滤在 Service.list：RESTRICTED 仅授权用户/创建者/管理员可见）；
 * 写（增删改/状态）：EDITOR+ 且 RESTRICTED 库需 EDIT 授权。
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseContentService contentService;
    private final com.ai.konwledgerepo.service.workspace.WorkspaceAccess workspaceAccess;

    public KnowledgeBaseController(KnowledgeBaseService kbService,
                                   KnowledgeBaseContentService contentService,
                                   com.ai.konwledgerepo.service.workspace.WorkspaceAccess workspaceAccess) {
        this.kbService = kbService;
        this.contentService = contentService;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping
    public ApiResponse<List<KbResponse>> list(@RequestAttribute("workspaceId") Long workspaceId,
                                              @RequestAttribute("userId") Long userId) {
        return ApiResponse.ok(kbService.list(workspaceId, userId));
    }

    @PostMapping
    @EditorOrAbove
    public ApiResponse<KbResponse> create(@RequestBody @Valid KbCreateRequest request,
                                          @RequestAttribute("userId") Long userId,
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(kbService.create(request, userId, workspaceId));
    }

    @PutMapping("/{id}")
    @EditorOrAbove
    public ApiResponse<KbResponse> update(@PathVariable Long id,
                                          @RequestBody @Valid KbUpdateRequest request,
                                          @RequestAttribute("userId") Long userId,
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(kbService.update(id, request, workspaceId));
    }

    @DeleteMapping("/{id}")
    @EditorOrAbove
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(id, workspaceId, userId, true);
        kbService.delete(id, workspaceId);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @EditorOrAbove
    public ApiResponse<KbResponse> updateStatus(@PathVariable Long id,
                                                @RequestParam String status,
                                                @RequestAttribute("userId") Long userId,
                                                @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(kbService.updateStatus(id, status, workspaceId));
    }

    @DeleteMapping("/{id}/content")
    @EditorOrAbove
    public ApiResponse<Void> clearContent(@PathVariable Long id,
                                          @RequestAttribute("userId") Long userId,
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(id, workspaceId, userId, true);
        contentService.clearContent(id, workspaceId);
        return ApiResponse.ok();
    }
}
