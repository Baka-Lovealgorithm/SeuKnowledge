package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.Document;

import java.time.LocalDateTime;

/**
 * 文档响应。
 */
public record DocumentResponse(Long id, Long kbId, String fileName, String fileType, Long fileSize,
                               String parseStatus, Integer chunkCount, String errorMsg, LocalDateTime createdAt,
                               Long suspectCount, String curateStatus) {

    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(doc.getId(), doc.getKbId(), doc.getFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getParseStatus(), doc.getChunkCount(), doc.getErrorMsg(), doc.getCreatedAt(),
                null, doc.getCurateStatus());
    }

    /** 带待审核数（SUSPECT 计数）的响应构造，供文档列表展示"待审核 N 块"徽标 */
    public static DocumentResponse withSuspectCount(Document doc, Long suspectCount) {
        return new DocumentResponse(doc.getId(), doc.getKbId(), doc.getFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getParseStatus(), doc.getChunkCount(), doc.getErrorMsg(), doc.getCreatedAt(),
                suspectCount, doc.getCurateStatus());
    }
}
