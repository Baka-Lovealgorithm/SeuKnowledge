package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.KbAccessRequest;
import com.ai.konwledgerepo.dto.KbAccessResponse;
import com.ai.konwledgerepo.entity.KbVisibility;
import com.ai.konwledgerepo.service.knowledgebase.KbAccessService;
import jakarta.validation.Valid;
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

import java.util.List;

/**
 * 知识库对象级授权接口（ACL）。
 * 管理权：空间 OWNER/ADMIN 或知识库创建者；授权对象仅限当前空间成员。
 * 列表/授权/移除/切换可见性均需管理权（服务内 requireManager 校验）。
 */
@RestController
@RequestMapping("/api/kb/{kbId}/access")
public class KbAccessController {

    private final KbAccessService accessService;

    public KbAccessController(KbAccessService accessService) {
        this.accessService = accessService;
    }

    /** 授权列表 */
    @GetMapping
    public ApiResponse<List<KbAccessResponse>> list(@PathVariable Long kbId,
                                                    @RequestAttribute("userId") Long userId,
                                                    @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(accessService.list(kbId, workspaceId, userId));
    }

    /** 授予/更新用户权限（VIEW / EDIT） */
    @PostMapping
    public ApiResponse<KbAccessResponse> grant(@PathVariable Long kbId,
                                               @RequestBody @Valid KbAccessRequest request,
                                               @RequestAttribute("userId") Long userId,
                                               @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(accessService.grant(kbId, workspaceId, userId, request));
    }

    /** 移除授权 */
    @DeleteMapping("/{accessId}")
    public ApiResponse<Void> revoke(@PathVariable Long kbId,
                                    @PathVariable Long accessId,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        accessService.revoke(kbId, workspaceId, userId, accessId);
        return ApiResponse.ok();
    }

    /** 切换可见性：PUBLIC / RESTRICTED */
    @PatchMapping("/visibility")
    public ApiResponse<KbVisibility> setVisibility(@PathVariable Long kbId,
                                                   @RequestParam String visibility,
                                                   @RequestAttribute("userId") Long userId,
                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(accessService.setVisibility(kbId, workspaceId, userId, visibility));
    }
}
