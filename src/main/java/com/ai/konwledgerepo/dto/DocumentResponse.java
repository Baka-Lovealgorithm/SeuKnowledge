package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.Document;

import java.time.LocalDateTime;

/**
 * 文档响应。
 */
public record DocumentResponse(Long id, Long kbId, String fileName, String fileType, Long fileSize,
                               String parseStatus, Integer chunkCount, String errorMsg, LocalDateTime createdAt) {

    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(doc.getId(), doc.getKbId(), doc.getFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getParseStatus(), doc.getChunkCount(), doc.getErrorMsg(), doc.getCreatedAt());
    }
}
