package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.BusinessKnowledgeRequest;
import com.ai.konwledgerepo.dto.BusinessKnowledgeResponse;
import com.ai.konwledgerepo.dto.MergeRequest;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.knowledge.BusinessKnowledgeService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * 业务知识管理接口。读：MEMBER+；写（增删改/审核/合并/回退）：EDITOR+。
 */
@RestController
@RequestMapping("/api")
public class BusinessKnowledgeController {

    private final BusinessKnowledgeService service;
    private final WorkspaceAccess workspaceAccess;

    public BusinessKnowledgeController(BusinessKnowledgeService service, WorkspaceAccess workspaceAccess) {
        this.service = service;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping("/kb/{kbId}/business-knowledge")
    public ApiResponse<List<BusinessKnowledgeResponse>> list(@PathVariable Long kbId,
                                                             @RequestParam(required = false) String status,
                                                             @RequestAttribute("userId") Long userId,
                                                             @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(kbId, workspaceId, userId, false);
        return ApiResponse.ok(service.list(kbId, status));
    }

    @PostMapping("/kb/{kbId}/business-knowledge")
    @EditorOrAbove
    public ApiResponse<BusinessKnowledgeResponse> create(@PathVariable Long kbId,
                                                         @RequestBody @Valid BusinessKnowledgeRequest request,
                                                         @RequestAttribute("userId") Long userId,
                                                         @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(kbId, workspaceId, userId, true);
        return ApiResponse.ok(service.create(kbId, request));
    }

    @PutMapping("/business-knowledge/{id}")
    @EditorOrAbove
    public ApiResponse<BusinessKnowledgeResponse> update(@PathVariable Long id,
                                                         @RequestBody @Valid BusinessKnowledgeRequest request,
                                                         @RequestAttribute("userId") Long userId,
                                                         @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(service.kbIdOf(id, workspaceId), workspaceId, userId, true);
        return ApiResponse.ok(service.update(id, request, workspaceId));
    }

    @DeleteMapping("/business-knowledge/{id}")
    @EditorOrAbove
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(service.kbIdOf(id, workspaceId), workspaceId, userId, true);
        service.delete(id, workspaceId);
        return ApiResponse.ok();
    }

    @PostMapping("/business-knowledge/{id}/approve")
    @EditorOrAbove
    public ApiResponse<BusinessKnowledgeResponse> approve(@PathVariable Long id,
                                                          @RequestAttribute("userId") Long userId,
                                                          @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(service.kbIdOf(id, workspaceId), workspaceId, userId, true);
        return ApiResponse.ok(service.approve(id, workspaceId));
    }

    @PostMapping("/business-knowledge/{id}/reject")
    @EditorOrAbove
    public ApiResponse<BusinessKnowledgeResponse> reject(@PathVariable Long id,
                                                         @RequestAttribute("userId") Long userId,
                                                         @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(service.kbIdOf(id, workspaceId), workspaceId, userId, true);
        return ApiResponse.ok(service.reject(id, workspaceId));
    }

    @PostMapping("/business-knowledge/{id}/merge")
    @EditorOrAbove
    public ApiResponse<BusinessKnowledgeResponse> merge(@PathVariable Long id,
                                                        @RequestBody @Valid MergeRequest request,
                                                        @RequestAttribute("userId") Long userId,
                                                        @RequestAttribute("workspaceId") Long workspaceId) {
        // source 与 target 都必须通过 ACL：两条记录可能跨库
        workspaceAccess.requireKbAccess(service.kbIdOfAnyVersion(request.targetId(), workspaceId),
                workspaceId, userId, true);
        workspaceAccess.requireKbAccess(service.kbIdOf(id, workspaceId), workspaceId, userId, true);
        return ApiResponse.ok(service.merge(id, request.targetId(), workspaceId));
    }

    @GetMapping("/business-knowledge/{id}/versions")
    public ApiResponse<List<BusinessKnowledgeResponse>> versions(@PathVariable Long id,
                                                                 @RequestAttribute("userId") Long userId,
                                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(service.kbIdOfAnyVersion(id, workspaceId), workspaceId, userId, false);
        return ApiResponse.ok(service.versions(id, workspaceId));
    }

    @PostMapping("/business-knowledge/{id}/versions/{version}/rollback")
    @EditorOrAbove
    public ApiResponse<BusinessKnowledgeResponse> rollback(@PathVariable Long id,
                                                           @PathVariable int version,
                                                           @RequestAttribute("userId") Long userId,
                                                           @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(service.kbIdOf(id, workspaceId), workspaceId, userId, true);
        return ApiResponse.ok(service.rollback(id, version, workspaceId));
    }
}
