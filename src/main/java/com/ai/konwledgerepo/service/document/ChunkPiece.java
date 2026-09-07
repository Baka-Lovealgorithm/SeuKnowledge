package com.ai.konwledgerepo.service.document;

/**
 * 分块片段：内容 + 页码（LlamaParse/PDF 解析可能有有效页码，txt/md 为 0）+ 所属小节标题（标题感知分块产物，可为空）。
 */
public record ChunkPiece(String content, int pageNum, String title) {

    public ChunkPiece(String content, int pageNum) {
        this(content, pageNum, null);
    }
}
