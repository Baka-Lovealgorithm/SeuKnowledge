package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.Chunk;

/**
 * 分块结果响应。
 */
public record ChunkResponse(Long id, Long docId, Integer seq, String content, Integer pageNum, String title,
                            String status, String esId, String cleanStatus, String cleanReason) {

    public static ChunkResponse from(Chunk c) {
        return new ChunkResponse(c.getId(), c.getDocId(), c.getSeq(), c.getContent(), c.getPageNum(),
                c.getTitle(), c.getStatus(), c.getEsId(), c.getCleanStatus(), c.getCleanReason());
    }
}
