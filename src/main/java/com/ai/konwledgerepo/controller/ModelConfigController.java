package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.ModelConfigRequest;
import com.ai.konwledgerepo.dto.ModelConfigResponse;
import com.ai.konwledgerepo.dto.ModelConfigTestResponse;
import com.ai.konwledgerepo.security.AdminOrAbove;
import com.ai.konwledgerepo.service.modelconfig.ModelConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型配置管理接口（系统级配置，按工作空间隔离）：仅当前空间的 OWNER / ADMIN 可访问。
 */
@RestController
@RequestMapping("/api/models")
@AdminOrAbove
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @GetMapping
    public ApiResponse<List<ModelConfigResponse>> list(@RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(modelConfigService.list(workspaceId));
    }

    @PostMapping
    public ApiResponse<ModelConfigResponse> create(@RequestAttribute("workspaceId") Long workspaceId,
                                                   @RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.create(request, workspaceId));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelConfigResponse> update(@RequestAttribute("workspaceId") Long workspaceId,
                                                   @PathVariable Long id,
                                                   @RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.update(id, request, workspaceId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@RequestAttribute("workspaceId") Long workspaceId,
                                    @PathVariable Long id) {
        modelConfigService.delete(id, workspaceId);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ModelConfigTestResponse> test(@RequestAttribute("workspaceId") Long workspaceId,
                                                     @PathVariable Long id) {
        return ApiResponse.ok(modelConfigService.test(id, workspaceId));
    }
}
