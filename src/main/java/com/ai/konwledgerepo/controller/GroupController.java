package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.GroupMemberRequest;
import com.ai.konwledgerepo.dto.GroupMemberResponse;
import com.ai.konwledgerepo.dto.GroupRequest;
import com.ai.konwledgerepo.dto.GroupResponse;
import com.ai.konwledgerepo.security.AdminOrAbove;
import com.ai.konwledgerepo.service.workspace.GroupService;
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
 * 知识库访问组管理接口（组级别授权的基础）。组归属当前工作空间，组内成员仅限空间成员。
 * 组列表（GET）对空间成员开放（知识库共享弹窗需要选组）；建组/改名/删组/成员增删要求
 * 空间 ADMIN/OWNER（@AdminOrAbove）。
 */
@RestController
@RequestMapping("/api/workspace/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /** 组列表（含成员数）——空间成员可见，供知识库共享弹窗选择组 */
    @GetMapping
    public ApiResponse<List<GroupResponse>> list(@RequestAttribute("userId") Long userId,
                                                 @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(groupService.listGroups(workspaceId, userId));
    }

    /** 新建组 */
    @PostMapping
    @AdminOrAbove
    public ApiResponse<GroupResponse> create(@RequestBody @Valid GroupRequest request,
                                             @RequestAttribute("userId") Long userId,
                                             @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(groupService.createGroup(workspaceId, userId, request));
    }

    /** 改名/改描述 */
    @PutMapping("/{groupId}")
    @AdminOrAbove
    public ApiResponse<GroupResponse> rename(@PathVariable Long groupId,
                                             @RequestBody @Valid GroupRequest request,
                                             @RequestAttribute("userId") Long userId,
                                             @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(groupService.renameGroup(workspaceId, userId, groupId, request));
    }

    /** 删除组（级联清成员关系与知识库授权记录） */
    @DeleteMapping("/{groupId}")
    @AdminOrAbove
    public ApiResponse<Void> delete(@PathVariable Long groupId,
                                    @RequestAttribute("userId") Long userId,
                                    @RequestAttribute("workspaceId") Long workspaceId) {
        groupService.deleteGroup(workspaceId, userId, groupId);
        return ApiResponse.ok();
    }

    /** 组内成员列表 */
    @GetMapping("/{groupId}/members")
    @AdminOrAbove
    public ApiResponse<List<GroupMemberResponse>> members(@PathVariable Long groupId,
                                                          @RequestAttribute("userId") Long userId,
                                                          @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(groupService.listMembers(workspaceId, userId, groupId));
    }

    /** 添加成员（限当前工作空间成员） */
    @PostMapping("/{groupId}/members")
    @AdminOrAbove
    public ApiResponse<GroupMemberResponse> addMember(@PathVariable Long groupId,
                                                      @RequestBody @Valid GroupMemberRequest request,
                                                      @RequestAttribute("userId") Long userId,
                                                      @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(groupService.addMember(workspaceId, userId, groupId, request.userId()));
    }

    /** 移除成员 */
    @DeleteMapping("/{groupId}/members/{memberUserId}")
    @AdminOrAbove
    public ApiResponse<Void> removeMember(@PathVariable Long groupId,
                                          @PathVariable Long memberUserId,
                                          @RequestAttribute("userId") Long userId,
                                          @RequestAttribute("workspaceId") Long workspaceId) {
        groupService.removeMember(workspaceId, userId, groupId, memberUserId);
        return ApiResponse.ok();
    }
}