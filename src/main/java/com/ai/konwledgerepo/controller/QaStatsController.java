package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.dto.QaDislikePageResponse;
import com.ai.konwledgerepo.dto.QaOverviewResponse;
import com.ai.konwledgerepo.security.AdminOrAbove;
import com.ai.konwledgerepo.service.stats.QaStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答反馈汇总接口：**整个系统里唯一跨用户读取问答内容的入口**。
 * <p>
 * 类级 {@code @AdminOrAbove} 把两个端点都限制为当前工作空间的 OWNER/ADMIN
 * （AuthInterceptor 按成员角色解析，方法级注解优先、类级兜底）。普通成员即便直连 URL 也是 403。
 * <p>
 * 只读、无副作用：不改任何消息行，也不产生 LLM 调用。
 */
@RestController
@RequestMapping("/api/stats/qa")
@AdminOrAbove
public class QaStatsController {

    private final QaStatsService statsService;

    public QaStatsController(QaStatsService statsService) {
        this.statsService = statsService;
    }

    /** 总览：回答数/点赞点踩/踩率/无证据/中断 + 原因分布 + 自检快照均值（默认近 30 天，窗口限 1~365） */
    @GetMapping("/overview")
    public ApiResponse<QaOverviewResponse> overview(@RequestAttribute("userId") Long userId,
                                                    @RequestAttribute("workspaceId") Long workspaceId,
                                                    @RequestParam(name = "days", defaultValue = "30") int days) {
        return ApiResponse.ok(statsService.overview(workspaceId, userId, days));
    }

    /**
     * 点踩明细（分页，按评价时间倒序）。总数随页数据一起返回，前端不必为分页控件再打一次接口。
     * 该次访问会在服务端留一条 INFO 痕迹（requestId/userId 由 MDC 自动携带）。
     */
    @GetMapping("/dislikes")
    public ApiResponse<QaDislikePageResponse> dislikes(@RequestAttribute("userId") Long userId,
                                                       @RequestAttribute("workspaceId") Long workspaceId,
                                                       @RequestParam(name = "days", defaultValue = "30") int days,
                                                       @RequestParam(name = "page", defaultValue = "0") int page,
                                                       @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(statsService.dislikes(workspaceId, userId, days, page, size));
    }
}
