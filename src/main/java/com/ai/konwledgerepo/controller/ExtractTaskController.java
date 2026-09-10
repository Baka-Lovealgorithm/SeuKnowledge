package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.ExtractTaskCreateRequest;
import com.ai.konwledgerepo.dto.ExtractTaskResponse;
import com.ai.konwledgerepo.dto.ExtractTaskResultResponse;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.extract.ExtractTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 抽取任务接口。创建/重试：EDITOR+；列表/详情/结果预览：MEMBER+ 只读。
 */
@RestController
@RequestMapping("/api/extract/tasks")
public class ExtractTaskController {

    private final ExtractTaskService service;

    public ExtractTaskController(ExtractTaskService service) {
        this.service = service;
    }

    @PostMapping
    @EditorOrAbove
    public ApiResponse<ExtractTaskResponse> create(@RequestBody @Valid ExtractTaskCreateRequest request,
                                                   @RequestAttribute("userId") Long userId,
                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.create(request, userId, workspaceId));
    }

    @GetMapping
    public ApiResponse<List<ExtractTaskResponse>> list(@RequestAttribute("userId") Long userId,
                                                       @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.list(workspaceId, userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExtractTaskResponse> get(@PathVariable Long id,
                                                @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.get(id, workspaceId));
    }

    @GetMapping("/{id}/results")
    public ApiResponse<ExtractTaskResultResponse> results(@PathVariable Long id,
                                                          @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.results(id, workspaceId));
    }

    /** 重试失败任务：仅重抽失败文档，重抽前清掉这些文档的旧 DRAFT 草稿 */
    @PostMapping("/{id}/retry")
    @EditorOrAbove
    public ApiResponse<ExtractTaskResponse> retry(@PathVariable Long id,
                                                  @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.retry(id, workspaceId));
    }
}
