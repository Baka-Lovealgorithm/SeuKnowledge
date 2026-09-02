package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ChunkReviewResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.document.ChunkReviewService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档精修接口（原"清洗人工审核"）：待审核（SUSPECT）队列查询（读：MEMBER+）、
 * 保留/编辑/删除/回退待审核/批量（写：EDITOR+）。
 * 精修动作按 chunk 归属文档做空间与 ACL 校验。
 */
@RestController
@RequestMapping("/api")
public class ChunkReviewController {

    private final ChunkReviewService reviewService;
    private final WorkspaceAccess workspaceAccess;
    private final ChunkRepository chunkRepository;

    public ChunkReviewController(ChunkReviewService reviewService,
                                 WorkspaceAccess workspaceAccess,
                                 ChunkRepository chunkRepository) {
        this.reviewService = reviewService;
        this.workspaceAccess = workspaceAccess;
        this.chunkRepository = chunkRepository;
    }

    @GetMapping("/kb/{kbId}/chunks/suspect")
    public ApiResponse<List<ChunkReviewResponse>> suspectQueue(@PathVariable Long kbId,
                                                               @RequestAttribute("userId") Long userId,
                                                               @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(kbId, workspaceId, userId, false);
        return ApiResponse.ok(reviewService.suspectQueue(kbId));
    }

    @PostMapping("/chunks/{id}/review/keep")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> keep(@PathVariable Long id,
                                                 @RequestAttribute("userId") Long userId,
                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        requireDocAccess(id, workspaceId, userId);
        return ApiResponse.ok(reviewService.keep(id, userId));
    }

    @PostMapping("/chunks/{id}/review/drop")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> drop(@PathVariable Long id,
                                                 @RequestAttribute("userId") Long userId,
                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        requireDocAccess(id, workspaceId, userId);
        return ApiResponse.ok(reviewService.drop(id, userId));
    }

    /** 已审核回退待审核（KEEP→SUSPECT，已向量化则移出 ES 恢复 DEFER） */
    @PostMapping("/chunks/{id}/review/unkeep")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> unkeep(@PathVariable Long id,
                                                   @RequestAttribute("userId") Long userId,
                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        requireDocAccess(id, workspaceId, userId);
        return ApiResponse.ok(reviewService.unkeep(id, userId));
    }

    @PostMapping("/chunks/{id}/edit")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> edit(@PathVariable Long id,
                                                 @RequestBody EditRequest request,
                                                 @RequestAttribute("userId") Long userId,
                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        requireDocAccess(id, workspaceId, userId);
        return ApiResponse.ok(reviewService.edit(id, request.content(), request.title(), userId));
    }

    @PostMapping("/chunks/review/batch")
    @EditorOrAbove
    public ApiResponse<List<ChunkReviewService.BatchItemResult>> batch(@RequestBody BatchRequest request,
                                                                       @RequestAttribute("userId") Long userId,
                                                                       @RequestAttribute("workspaceId") Long workspaceId) {
        if (request.ids() != null) {
            for (Long id : request.ids()) {
                requireDocAccess(id, workspaceId, userId);
            }
        }
        return ApiResponse.ok(reviewService.batch(request.ids(), request.action(), userId));
    }

    private void requireDocAccess(Long chunkId, Long workspaceId, Long userId) {
        Chunk chunk = chunkRepository.findById(chunkId)
                .orElseThrow(() -> new BizException("chunk 不存在: " + chunkId));
        workspaceAccess.requireDocAccess(chunk.getDocId(), workspaceId, userId, true);
    }

    public record EditRequest(String content, String title) {
    }

    public record BatchRequest(List<Long> ids, String action) {
    }
}
