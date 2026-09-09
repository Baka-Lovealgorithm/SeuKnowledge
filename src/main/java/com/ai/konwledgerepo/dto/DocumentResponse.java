package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.Document;

import java.time.LocalDateTime;

/**
 * 文档响应。
 * <p>
 * {@code vector} 是"解析状态之外"的第二条健康线：{@code parseStatus=SUCCESS} 只代表分块已落 MySQL，
 * 向量是否真的进了 ES 由 vector 计数说明（历史上二者之间的差异对用户完全不可见，
 * 表现为"文档显示成功却检索不到"）。
 *
 * @param suspectCount 清洗判定待人工审核（SUSPECT）的块数
 * @param vector       向量健康度（indexed/pending/failed）；无 chunk 或未统计时为 null
 */
public record DocumentResponse(Long id, Long kbId, String fileName, String fileType, Long fileSize,
                               String parseStatus, Integer chunkCount, String errorMsg, LocalDateTime createdAt,
                               Long suspectCount, String curateStatus, VectorStats vector) {

    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(doc.getId(), doc.getKbId(), doc.getFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getParseStatus(), doc.getChunkCount(), doc.getErrorMsg(), doc.getCreatedAt(),
                null, doc.getCurateStatus(), null);
    }

    /**
     * 列表用完整响应：待审核块数 + 向量健康度。
     *
     * @param suspectCount 该文档 cleanStatus=SUSPECT 的块数
     * @param vector       该文档 chunk 的向量状态计数（无 chunk 时传 {@link VectorStats#EMPTY}）
     */
    public static DocumentResponse withVectorStats(Document doc, Long suspectCount, VectorStats vector) {
        return new DocumentResponse(doc.getId(), doc.getKbId(), doc.getFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getParseStatus(), doc.getChunkCount(), doc.getErrorMsg(), doc.getCreatedAt(),
                suspectCount, doc.getCurateStatus(), vector);
    }
}
