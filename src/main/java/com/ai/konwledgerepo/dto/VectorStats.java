package com.ai.konwledgerepo.dto;

/**
 * 单文档向量健康度摘要（文档列表"向量 i/t"标记的数据源）。
 * <p>
 * 口径：只统计<b>应该出现在向量库里</b>的块——清洗丢弃（{@code status=FILTERED}）与待人工审核
 * （{@code cleanStatus=SUSPECT}，由精修页负责、列表另有"待审核 N"徽标）两类均剔除，
 * 否则列表会长期挂着"12/15"这种看着像故障的比值。构建见 {@code DocumentService#vectorCounts}。
 *
 * @param indexed 已写入 ES（INDEXED）
 * @param pending 待向量化（EMBEDDING：解析刚落库、向量化排队/进行中、或向量化被跳过）
 * @param failed  向量化或 ES 写入失败（FAILED，可用「重建向量」重试）
 */
public record VectorStats(int indexed, int pending, int failed) {

    public static final VectorStats EMPTY = new VectorStats(0, 0, 0);

    /** 应向量化总数 */
    public int total() {
        return indexed + pending + failed;
    }

    /** 是否仍有块在向量化途中（前端据此继续轮询列表） */
    public boolean inflight() {
        return pending > 0;
    }

    /** 是否全部完成且无失败 */
    public boolean healthy() {
        return pending == 0 && failed == 0;
    }
}
