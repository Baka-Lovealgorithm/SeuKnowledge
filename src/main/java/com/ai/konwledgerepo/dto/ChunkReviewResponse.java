package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.Chunk;

/**
 * 清洗审核队列项（SUSPECT chunk + 所属文档名）。
 */
public record ChunkReviewResponse(Long chunkId, Long docId, String docName, Integer seq, Integer pageNum,
                                  String title, String content, String cleanReason, String status,
                                  String cleanStatus) {

    public static ChunkReviewResponse of(Chunk c, String docName) {
        return new ChunkReviewResponse(c.getId(), c.getDocId(), docName, c.getSeq(), c.getPageNum(),
                c.getTitle(), c.getContent(), c.getCleanReason(), c.getStatus(), c.getCleanStatus());
    }
}
