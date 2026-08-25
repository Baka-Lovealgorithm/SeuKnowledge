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
 * 读：MEMBER+（工作空间内所有知识库只读）；写（增删改/状态）：EDITOR+。
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseContentService contentService;

    public KnowledgeBaseController(KnowledgeBaseService kbService, KnowledgeBaseContentService contentService) {
        this.kbService = kbService;
        this.contentService = contentService;
    }

    @GetMapping
    public ApiResponse<List<KbResponse>> list(@RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(kbService.list(workspaceId));
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
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(kbService.update(id, request, workspaceId));
    }

    @DeleteMapping("/{id}")
    @EditorOrAbove
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        kbService.delete(id, workspaceId);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @EditorOrAbove
    public ApiResponse<KbResponse> updateStatus(@PathVariable Long id,
                                                @RequestParam String status,
                                                @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(kbService.updateStatus(id, status, workspaceId));
    }

    @DeleteMapping("/{id}/content")
    @EditorOrAbove
    public ApiResponse<Void> clearContent(@PathVariable Long id,
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        contentService.clearContent(id, workspaceId);
        return ApiResponse.ok();
    }
}
