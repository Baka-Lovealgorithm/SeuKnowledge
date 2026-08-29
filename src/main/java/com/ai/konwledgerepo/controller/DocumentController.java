package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.ChunkResponse;
import com.ai.konwledgerepo.dto.DocumentResponse;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.document.DocumentService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理接口：上传 / 列表 / 分块查看 / 删除 / 重试。
 * 写（上传/删除/重试）：EDITOR+；读（列表/分块）：MEMBER+。
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
                                                      @RequestAttribute("userId") Long userId,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKb(kbId, workspaceId);
        List<Document> saved = documentService.upload(kbId, files, userId, replace);
        return ApiResponse.ok(saved.stream().map(DocumentResponse::from).toList());
    }

    @GetMapping("/kb/{kbId}/documents")
    public ApiResponse<List<DocumentResponse>> list(@PathVariable Long kbId,
                                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireKb(kbId, workspaceId);
        return ApiResponse.ok(documentService.list(kbId));
    }

    @DeleteMapping("/documents/{id}")
    @EditorOrAbove
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDoc(id, workspaceId);
        documentService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<ChunkResponse>> chunks(@PathVariable Long id,
                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDoc(id, workspaceId);
        return ApiResponse.ok(documentService.chunks(id));
    }

    @PostMapping("/documents/{id}/retry")
    @EditorOrAbove
    public ApiResponse<Void> retry(@PathVariable Long id,
                                   @RequestAttribute("workspaceId") Long workspaceId) {
        workspaceAccess.requireDoc(id, workspaceId);
        documentService.retry(id);
        return ApiResponse.ok();
    }
}
