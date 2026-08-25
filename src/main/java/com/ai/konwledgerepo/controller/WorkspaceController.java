package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.CreateMemberRequest;
import com.ai.konwledgerepo.dto.CreateWorkspaceRequest;
import com.ai.konwledgerepo.dto.InviteMemberRequest;
import com.ai.konwledgerepo.dto.MemberResponse;
import com.ai.konwledgerepo.dto.RenameWorkspaceRequest;
import com.ai.konwledgerepo.dto.TransferWorkspaceRequest;
import com.ai.konwledgerepo.dto.UpdateRoleRequest;
import com.ai.konwledgerepo.dto.WorkspaceResponse;
import com.ai.konwledgerepo.security.AdminOrAbove;
import com.ai.konwledgerepo.security.RequireRole;
import com.ai.konwledgerepo.security.Roles;
import com.ai.konwledgerepo.service.workspace.WorkspaceService;
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
 * 工作空间与成员管理接口（多工作空间，所有成员操作限定当前工作空间）。
 * - 任意已登录用户：创建工作空间（POST /api/workspace，无角色要求）；
 * - 成员：查询当前空间信息（GET /api/workspace）；
 * - ADMIN+：成员管理（创建账号/邀请已有用户/改角色/移除，不可改或移除 Owner）；
 * - OWNER：重命名、转让拥有权、删除工作空间。
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    // ===== 工作空间（任意已登录用户可创建） =====

    /** 创建工作空间（创建者成为 OWNER；无成员关系的用户也可调用） */
    @PostMapping
    public ApiResponse<WorkspaceResponse> create(@RequestAttribute("userId") Long userId,
                                                 @RequestBody @Valid CreateWorkspaceRequest request) {
        return ApiResponse.ok(service.createWorkspace(userId, request.name()));
    }

    /** 当前空间信息（名称/角色/成员数） */
    @GetMapping
    public ApiResponse<WorkspaceResponse> info(@RequestAttribute("userId") Long userId,
                                               @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.info(userId, workspaceId));
    }

    /** 重命名工作空间（仅拥有者） */
    @PutMapping
    @RequireRole(Roles.OWNER)
    public ApiResponse<WorkspaceResponse> rename(@RequestAttribute("userId") Long userId,
                                                 @RequestAttribute("workspaceId") Long workspaceId,
                                                 @RequestBody @Valid RenameWorkspaceRequest request) {
        return ApiResponse.ok(service.renameWorkspace(userId, workspaceId, request.name()));
    }

    /** 删除工作空间（归档全部知识库并移除所有成员；仅拥有者，前端需二次确认） */
    @DeleteMapping
    @RequireRole(Roles.OWNER)
    public ApiResponse<Void> deleteWorkspace(@RequestAttribute("userId") Long userId,
                                             @RequestAttribute("workspaceId") Long workspaceId) {
        service.deleteWorkspace(userId, workspaceId);
        return ApiResponse.ok();
    }

    // ===== 成员管理（ADMIN+） =====

    /** 成员列表 */
    @GetMapping("/members")
    @AdminOrAbove
    public ApiResponse<List<MemberResponse>> members(@RequestAttribute("userId") Long userId,
                                                     @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(service.listMembers(userId, workspaceId));
    }

    /** 创建成员（创建账号并加入当前工作空间；角色：ADMIN / EDITOR / MEMBER） */
    @PostMapping("/members")
    @AdminOrAbove
    public ApiResponse<MemberResponse> createMember(@RequestAttribute("userId") Long userId,
                                                    @RequestAttribute("workspaceId") Long workspaceId,
                                                    @RequestBody @Valid CreateMemberRequest request) {
        return ApiResponse.ok(service.createMember(userId, workspaceId, request));
    }

    /** 邀请已有用户加入当前工作空间（按用户名；角色：ADMIN / EDITOR / MEMBER） */
    @PostMapping("/members/invite")
    @AdminOrAbove
    public ApiResponse<MemberResponse> inviteMember(@RequestAttribute("userId") Long userId,
                                                    @RequestAttribute("workspaceId") Long workspaceId,
                                                    @RequestBody @Valid InviteMemberRequest request) {
        return ApiResponse.ok(service.inviteMember(userId, workspaceId, request));
    }

    /** 修改成员角色（不可修改拥有者） */
    @PutMapping("/members/{id}/role")
    @AdminOrAbove
    public ApiResponse<MemberResponse> updateRole(@RequestAttribute("userId") Long userId,
                                                  @RequestAttribute("workspaceId") Long workspaceId,
                                                  @PathVariable Long id,
                                                  @RequestBody @Valid UpdateRoleRequest request) {
        return ApiResponse.ok(service.updateRole(userId, workspaceId, id, request.role()));
    }

    /** 移除成员（不可移除拥有者） */
    @DeleteMapping("/members/{id}")
    @AdminOrAbove
    public ApiResponse<Void> removeMember(@RequestAttribute("userId") Long userId,
                                          @RequestAttribute("workspaceId") Long workspaceId,
                                          @PathVariable Long id) {
        service.removeMember(userId, workspaceId, id);
        return ApiResponse.ok();
    }

    // ===== 拥有者专属 =====

    /** 转让拥有权：原 Owner 降为 ADMIN */
    @PostMapping("/owner/transfer")
    @RequireRole(Roles.OWNER)
    public ApiResponse<MemberResponse> transferOwnership(@RequestAttribute("userId") Long userId,
                                                         @RequestAttribute("workspaceId") Long workspaceId,
                                                         @RequestBody @Valid TransferWorkspaceRequest request) {
        return ApiResponse.ok(service.transferOwnership(userId, workspaceId, request.memberId()));
    }
}
