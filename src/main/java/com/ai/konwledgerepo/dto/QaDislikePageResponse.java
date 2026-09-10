package com.ai.konwledgerepo.dto;

import java.util.List;

/**
 * 点踩明细分页结果（一次请求同时拿到页数据与总数，避免前端为总数再打一次接口）。
 *
 * @param items 当前页明细
 * @param total 满足条件的总条数（前端分页控件用）
 * @param page  实际生效的页码（从 0 起，负数已归零）
 * @param size  实际生效的页大小（限 1~100）
 */
public record QaDislikePageResponse(List<DislikeItemResponse> items, long total, int page, int size) {
}
