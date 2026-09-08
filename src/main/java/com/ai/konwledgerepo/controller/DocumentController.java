package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.ChunkResponse;
import com.ai.konwledgerepo.dto.DocumentRenameRequest;
import com.ai.konwledgerepo.dto.DocumentResponse;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.document.DocumentService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理接口：上传 / 列表 / 分块查看 / 删除 / 重试 / 重命名 / 重建向量。
 * 写（上传/删除/重试/改名/重建向量）：EDITOR+；读（列表/分块）：MEMBER+。
 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;
    private final WorkspaceAccess workspaceAccess;

    public DocumentController(DocumentService documentService, WorkspaceAccess workspaceAccess) {
        this.documentService = documentService;
        this.workspaceAccess = workspaceAccess;
    }

    @PostMapping("/kb/{kbId}/documents")
    @EditorOrAbove
    public ApiResponse<List<DocumentResponse>> upload(@PathVariable Long kbId,
                                                      @RequestParam("files") List<MultipartFile> files,
                                                      @RequestParam(value = "replace", defaultValue = "false") boolean replace,
                                                      @RequestParam(value = "reuseCache", defaultValue = "false") boolean reuseCache,
                                                      @RequestParam(value = "curateGate", defaultValue = "false") boolean curateGate,
                                                      @RequestAttribute("userId") Long userId,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(kbId, workspaceId, userId, true);
        List<Document> saved = documentService.upload(kbId, files, userId, replace, reuseCache, curateGate);
        return ApiResponse.ok(saved.stream().map(DocumentResponse::from).toList());
    }

    @GetMapping("/kb/{kbId}/documents")
    public ApiResponse<List<DocumentResponse>> list(@PathVariable Long kbId,
                                                    @RequestAttribute("userId") Long userId,
                                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKbAccess(kbId, workspaceId, userId, false);
        return ApiResponse.ok(documentService.list(kbId));
    }

    @DeleteMapping("/documents/{id}")
    @EditorOrAbove
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        documentService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<ChunkResponse>> chunks(@PathVariable Long id,
                                                   @RequestAttribute("userId") Long userId,
                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, false);
        return ApiResponse.ok(documentService.chunks(id));
    }

    @PostMapping("/documents/{id}/retry")
    @EditorOrAbove
    public ApiResponse<Void> retry(@PathVariable Long id,
                                   @RequestAttribute("userId") Long userId,
                                   @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        documentService.retry(id);
        return ApiResponse.ok();
    }

    /**
     * 文档重命名（只改元数据与检索引用名：MySQL file_name + ES docName + 派生知识 source_doc_name）。
     * 不重解析、不动磁盘文件；解析失败可另用 retry，向量缺失可另用 reindex。
     */
    @PatchMapping("/documents/{id}/name")
    @EditorOrAbove
    public ApiResponse<DocumentResponse> rename(@PathVariable Long id,
                                                @RequestBody DocumentRenameRequest request,
                                                @RequestAttribute("userId") Long userId,
                                                @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        Document doc = documentService.rename(id, request.fileName(), userId);
        return ApiResponse.ok(DocumentResponse.from(doc));
    }

    /**
     * 只重建向量、不重新解析：向量化失败（模型未配置 / embedding 异常 / ES 部分写入失败）后的原地救济。
     * 初洗/精修流程中的文档会被拒绝（确认前不触 ES 的红线）。
     */
    @PostMapping("/documents/{id}/reindex")
    @EditorOrAbove
    public ApiResponse<Void> reindex(@PathVariable Long id,
                                     @RequestAttribute("userId") Long userId,
                                     @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDocAccess(id, workspaceId, userId, true);
        documentService.reindex(id);
        return ApiResponse.ok();
    }
}
