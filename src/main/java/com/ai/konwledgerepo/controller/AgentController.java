package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.AgentRequest;
import com.ai.konwledgerepo.dto.AgentResponse;
import com.ai.konwledgerepo.security.EditorOrAbove;
import com.ai.konwledgerepo.service.agent.AgentService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 配置接口：知识库绑定默认 Agent，支持动态修改提示词与答案/记忆策略参数。
 * 读：MEMBER+；写（修改配置）：EDITOR+。
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentService agentService;
    private final WorkspaceAccess workspaceAccess;

    public AgentController(AgentService agentService, WorkspaceAccess workspaceAccess) {
        this.agentService = agentService;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping("/kb/{kbId}/agent")
    public ApiResponse<AgentResponse> get(@PathVariable Long kbId,
                                          @RequestAttribute("userId") Long userId,
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        String kbName = workspaceAccess.requireKbAccess(kbId, workspaceId, userId, false).getName();
        return ApiResponse.ok(agentService.getResponse(kbId, kbName));
    }

    @PutMapping("/kb/{kbId}/agent")
    @EditorOrAbove
    public ApiResponse<AgentResponse> update(@PathVariable Long kbId,
                                             @RequestBody @Valid AgentRequest request,
                                             @RequestAttribute("userId") Long userId,
                                             @RequestAttribute("workspaceId") Long workspaceId) {
        String kbName = workspaceAccess.requireKbAccess(kbId, workspaceId, userId, true).getName();
        return ApiResponse.ok(agentService.update(kbId, kbName, request));
    }
}
