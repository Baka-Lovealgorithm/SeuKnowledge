package com.ai.konwledgerepo.repository;

/**
 * chunk 向量状态计数投影（供文档列表一次 group-by 取回全部文档的向量健康度，避免逐文档 count 的 N+1）。
 * <p>
 * 使用方：{@link ChunkRepository#statusCountsByKb(Long)}、{@code DocumentService.list}。
 */
public interface ChunkStatusCount {

    Long getDocId();

    /** chunk 向量状态：EMBEDDING / INDEXED / FAILED / FILTERED */
    String getStatus();

    /** 清洗状态：null=未清洗 / KEEP / SUSPECT / FILTERED */
    String getCleanStatus();

    Long getCnt();
}
