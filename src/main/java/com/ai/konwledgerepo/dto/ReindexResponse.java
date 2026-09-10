package com.ai.konwledgerepo.dto;

/**
 * 「重建向量」（{@code POST /documents/{id}/reindex}）的结果。
 * <p>
 * 之所以要回传而不是 {@code Void}：当文档全部块都处于待人工审核（{@code clean_status=SUSPECT}，
 * DEFER 决策下不进 ES）时，本接口是<b>空操作</b>——以前固定返回成功，前端弹「已触发重建向量」，
 * 用户以为在处理，其实一块都没动，只能回头去点「重试」（全量重解析：删块删向量 + 再花一次云端解析）。
 *
 * @param reset          本次从 FAILED 退回待向量化的块数；0 表示没有失败块（ingest 仍会重跑一遍兜遗漏）
 * @param awaitingReview 该文档待人工审核（SUSPECT）的块数；{@code reset=0 且它 > 0} 时应引导去「文档精修」处置
 */
public record ReindexResponse(int reset, int awaitingReview) {
}
