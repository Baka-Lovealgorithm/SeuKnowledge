package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ChunkReviewResponse;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.document.DocumentCurateService;
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
 * 文档初洗/精修接口（原"文档人工策展"）：读（队列/md/chunk 列表）MEMBER+；
 * 写（保存 md、接受、确认、chunk 精修）EDITOR+，按文档做空间与 ACL 校验。
 * <p>
 * 流程：PREVIEWING（初洗，chunk 只读）→ accept（ACCEPTED，精修）→ confirm（统一向量化）。
 * 保存 md 会同步重分块（仍 PREVIEWING）；接受后后悔通道关闭；确认要求全部未删除分块已审核。
 */
@RestController
@RequestMapping("/api")
public class DocumentCurateController {

    private final DocumentCurateService curateService;
    private final WorkspaceAccess workspaceAccess;

    public DocumentCurateController(DocumentCurateService curateService, WorkspaceAccess workspaceAccess) {
        this.curateService = curateService;
        this.workspaceAccess = workspaceAccess;
    }

    /** 初洗/精修队列（初洗中/精修中文档，跨文档聚合；前端按状态过滤） */
    @GetMapping("/kb/{kbId}/curate/queue")
    public ApiResponse<List<DocumentCurateService.CurateQueueItem>> queue(@PathVariable Long kbId,
                                                                          @RequestAttribute("userId") Long userId,
                                                                          @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(kbId, workspaceId, userId, false);
        return ApiResponse.ok(curateService.queue(kbId));
    }

    /** 单文档初洗信息（状态/统计，处理页头部） */
    @GetMapping("/documents/{id}/curate")
    public ApiResponse<DocumentCurateService.CurateDocInfo> info(@PathVariable Long id,
                                                                 @RequestAttribute("userId") Long userId,
                                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, false);
        return ApiResponse.ok(curateService.docInfo(id));
    }

    /** 取最新版本整篇 md（在线编辑回显） */
    @GetMapping("/documents/{id}/curate/md")
    public ApiResponse<String> getMd(@PathVariable Long id,
                                     @RequestAttribute("userId") Long userId,
                                     @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, false);
        return ApiResponse.ok(curateService.getMd(id));
    }

    /** 初洗/精修文档全部 chunk（待审核 SUSPECT 优先），供初洗只读列表/精修工作台 */
    @GetMapping("/documents/{id}/curate/chunks")
    public ApiResponse<List<ChunkReviewResponse>> chunks(@PathVariable Long id,
                                                         @RequestAttribute("userId") Long userId,
                                                         @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, false);
        return ApiResponse.ok(curateService.chunks(id));
    }

    /** 保存整篇 md（初洗在线编辑 → 切页 → 重分块，仍 PREVIEWING） */
    @PostMapping("/documents/{id}/curate/md")
    @EditorOrAbove
    public ApiResponse<DocumentCurateService.RechunkResult> saveMd(@PathVariable Long id,
                                                                   @RequestBody SaveMdRequest request,
                                                                   @RequestAttribute("userId") Long userId,
                                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        if (request.content() == null || request.content().isBlank()) {
            throw new BizException("清洗后的 md 内容不能为空");
        }
        return ApiResponse.ok(curateService.saveMd(id, request.content(), userId));
    }

    /** 接受：PREVIEWING → ACCEPTED（关闭 md 编辑/重分块，chunk 进入精修阶段） */
    @PostMapping("/documents/{id}/curate/accept")
    @EditorOrAbove
    public ApiResponse<Void> accept(@PathVariable Long id,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        curateService.accept(id, userId);
        return ApiResponse.ok();
    }

    /** 确认完成：ACCEPTED → 全部未删除分块已审核后统一向量化（异步 ingest），回到照旧 */
    @PostMapping("/documents/{id}/curate/confirm")
    @EditorOrAbove
    public ApiResponse<Void> confirm(@PathVariable Long id,
                                     @RequestAttribute("userId") Long userId,
                                     @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        curateService.confirm(id, userId);
        return ApiResponse.ok();
    }

    /** 精修：编辑任意 chunk（仅 ACCEPTED 且确认前；只改 MySQL + 审计，不触 ES） */
    @PostMapping("/documents/{id}/curate/chunks/{chunkId}/edit")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> editChunk(@PathVariable Long id,
                                                      @PathVariable Long chunkId,
                                                      @RequestBody EditRequest request,
                                                      @RequestAttribute("userId") Long userId,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(curateService.editChunk(chunkId, request.content(), request.title(), userId));
    }

    /** 精修：删除 chunk（软删 FILTERED，记录保留；不触 ES） */
    @PostMapping("/documents/{id}/curate/chunks/{chunkId}/drop")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> dropChunk(@PathVariable Long id,
                                                      @PathVariable Long chunkId,
                                                      @RequestAttribute("userId") Long userId,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(curateService.dropChunk(chunkId, userId));
    }

    /** 精修：保留 SUSPECT（→KEEP，已审核） */
    @PostMapping("/documents/{id}/curate/chunks/{chunkId}/keep")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> keepChunk(@PathVariable Long id,
                                                      @PathVariable Long chunkId,
                                                      @RequestAttribute("userId") Long userId,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(curateService.keepChunk(chunkId, userId));
    }

    /** 精修：已审核回退待审核（KEEP→SUSPECT） */
    @PostMapping("/documents/{id}/curate/chunks/{chunkId}/unkeep")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> unkeepChunk(@PathVariable Long id,
                                                        @PathVariable Long chunkId,
                                                        @RequestAttribute("userId") Long userId,
                                                        @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(curateService.unkeepChunk(chunkId, userId));
    }

    /** 精修：合并相邻 chunk（source 并入 target，保留 target id；合并后置 SUSPECT 待审，不触 ES） */
    @PostMapping("/documents/{id}/curate/chunks/merge")
    @EditorOrAbove
    public ApiResponse<ChunkReviewResponse> mergeChunk(@PathVariable Long id,
                                                       @RequestBody MergeRequest request,
                                                       @RequestAttribute("userId") Long userId,
                                                       @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        return ApiResponse.ok(curateService.mergeChunk(id, request.sourceId(), request.targetId(), userId));
    }

    public record SaveMdRequest(String content) {
    }

    public record EditRequest(String content, String title) {
    }

    public record MergeRequest(Long sourceId, Long targetId) {
    }
}
