package com.ai.konwledgerepo.dto;

import java.util.List;

/**
 * 文档分块分页结果（精修 / 初洗预览共用，一次请求同时拿到页数据与统计，避免前端为总数再打接口）。
 *
 * @param items        当前页分块（精修/初洗侧为 {@link ChunkReviewResponse}，普通文档侧为 {@link ChunkResponse}）
 * @param total        满足过滤条件的总条数（分页控件用）
 * @param page         实际生效的页码（从 0 起，负数已归零）
 * @param size         实际生效的页大小（限 1~200）
 * @param suspectCount 该文档当前待人工审核（SUSPECT）分块数（不受 cleanStatus 过滤影响；
 *                     「一键通过」「确认完成并向量化」的可用性判断依据）
 */
public record ChunkPageResponse<T>(List<T> items, long total, int page, int size, long suspectCount) {
}
