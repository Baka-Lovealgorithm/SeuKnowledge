package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.QaPairRequest;
import com.ai.konwledgerepo.dto.QaPairResponse;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.knowledge.QaPairService;
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
 * 问答对管理接口。读：MEMBER+；写（增删改/审核/禁用/归一化/回退）：EDITOR+。
 */
@RestController
@RequestMapping("/api")
public class QaPairController {

    private final QaPairService service;
    private final WorkspaceAccess workspaceAccess;

    public QaPairController(QaPairService service, WorkspaceAccess workspaceAccess) {
        this.service = service;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping("/kb/{kbId}/qa-pairs")
    public ApiResponse<List<QaPairResponse>> list(@PathVariable Long kbId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKb(kbId, workspaceId);
        return ApiResponse.ok(service.list(kbId, status));
    }

    @PostMapping("/kb/{kbId}/qa-pairs")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> create(@PathVariable Long kbId,
                                              @RequestBody @Valid QaPairRequest request,
                                              @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKb(kbId, workspaceId);
        return ApiResponse.ok(service.create(kbId, request));
    }

    @PutMapping("/qa-pairs/{id}")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> update(@PathVariable Long id,
                                              @RequestBody @Valid QaPairRequest request,
                                              @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.update(id, request, workspaceId));
    }

    @DeleteMapping("/qa-pairs/{id}")
    @EditorOrAbove
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        service.delete(id, workspaceId);
        return ApiResponse.ok();
    }

    @PostMapping("/qa-pairs/{id}/approve")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> approve(@PathVariable Long id,
                                               @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.approve(id, workspaceId));
    }

    @PostMapping("/qa-pairs/{id}/reject")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> reject(@PathVariable Long id,
                                              @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.reject(id, workspaceId));
    }

    @PostMapping("/qa-pairs/{id}/disable")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> disable(@PathVariable Long id,
                                               @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.disable(id, workspaceId));
    }

    @PostMapping("/qa-pairs/{id}/enable")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> enable(@PathVariable Long id,
                                              @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.enable(id, workspaceId));
    }

    @PostMapping("/qa-pairs/{id}/normalize")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> normalize(@PathVariable Long id,
                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.normalize(id, workspaceId));
    }

    @GetMapping("/qa-pairs/{id}/versions")
    public ApiResponse<List<QaPairResponse>> versions(@PathVariable Long id,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.versions(id, workspaceId));
    }

    @PostMapping("/qa-pairs/{id}/versions/{version}/rollback")
    @EditorOrAbove
    public ApiResponse<QaPairResponse> rollback(@PathVariable Long id,
                                                @PathVariable int version,
                                                @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.rollback(id, version, workspaceId));
    }
}
