package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.PromptPreviewRequest;
import com.ai.konwledgerepo.dto.PromptTemplateResponse;
import com.ai.konwledgerepo.dto.PromptTemplateUpdateRequest;
import com.ai.konwledgerepo.security.AdminOrAbove;
import com.ai.konwledgerepo.service.prompt.PromptTemplateService;
import jakarta.validation.Valid;
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
 * 提示词模板管理接口（平台级配置）：节点提示词 / 抽取 / 记忆等模板的统一管理。
 * 仅当前空间的 OWNER / ADMIN 可访问（与模型配置一致）。
 * <p>更新/重置后立即失效 Redis 缓存，问答链路无需重启即生效。
 */
@RestController
@RequestMapping("/api/prompts")
@AdminOrAbove
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /** 全部平台层模板列表（含 content / version / 更新时间） */
    @GetMapping
    public ApiResponse<List<PromptTemplateResponse>> list() {
        return ApiResponse.ok(promptTemplateService.list());
    }

    /** 更新模板内容：version+1 + 失效缓存 */
    @PutMapping("/{key}")
    public ApiResponse<PromptTemplateResponse> update(@PathVariable String key,
                                                      @RequestBody @Valid PromptTemplateUpdateRequest request,
                                                      @RequestAttribute("userId") Long userId) {
        return ApiResponse.ok(promptTemplateService.update(key, request.content(), userId));
    }

    /** 重置为 classpath 出厂默认（恢复种子模板并失效缓存） */
    @PostMapping("/{key}/reset")
    public ApiResponse<PromptTemplateResponse> reset(@PathVariable String key) {
        return ApiResponse.ok(promptTemplateService.resetToDefault(key));
    }

    /** 渲染预览：传模板变量（{{name}}）→ 返回渲染后文本，改提示词前先看最终效果（不改库） */
    @PostMapping("/preview")
    public ApiResponse<String> preview(@RequestBody @Valid PromptPreviewRequest request) {
        return ApiResponse.ok(promptTemplateService.preview(request.key(), request.variables()));
    }
}
