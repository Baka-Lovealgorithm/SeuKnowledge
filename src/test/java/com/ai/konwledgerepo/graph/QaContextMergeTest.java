package com.ai.konwledgerepo.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 累计证据池合并工具（QaContext.mergeEvidence）单元测试：
 * 验证按 sourceType:chunkId 去重、保留先出现者、null 分组安全。
 */
class QaContextMergeTest {

    private static ChunkEvidence ev(long chunkId, String sourceType) {
        return new ChunkEvidence(chunkId, 1L, 1L, "doc.pdf", 1, sourceType, "title", "content", 1.0);
    }

    @Test
    void merge_dedupBySourceTypeAndChunkId() {
        List<ChunkEvidence> accumulated = List.of(ev(1, "CHUNK"), ev(2, "CHUNK"));
        List<ChunkEvidence> current = List.of(ev(2, "CHUNK"), ev(3, "CHUNK")); // 2 重复

        List<ChunkEvidence> merged = QaContext.mergeEvidence(accumulated, current);

        assertEquals(3, merged.size(), "重复 chunkId=2 应只保留一条");
        assertEquals(List.of(1L, 2L, 3L), merged.stream().map(ChunkEvidence::chunkId).toList(),
                "保留先出现的顺序：累计池在前");
    }

    @Test
    void merge_sameChunkIdDifferentSourceType_keepsBoth() {
        List<ChunkEvidence> accumulated = List.of(ev(1, "CHUNK"));
        List<ChunkEvidence> current = List.of(ev(1, "BUSINESS")); // 同 chunkId 不同来源

        List<ChunkEvidence> merged = QaContext.mergeEvidence(accumulated, current);

        assertEquals(2, merged.size(), "来源类型不同不应去重");
    }

    @Test
    void merge_nullSourceType_treatedAsChunk() {
        List<ChunkEvidence> accumulated = List.of(ev(1, null));
        List<ChunkEvidence> current = List.of(ev(1, "CHUNK")); // null 与 CHUNK 视为同一键

        List<ChunkEvidence> merged = QaContext.mergeEvidence(accumulated, current);

        assertEquals(1, merged.size(), "null sourceType 应按 CHUNK 去重");
    }

    @Test
    void merge_nullGroup_ignored() {
        List<ChunkEvidence> accumulated = List.of(ev(1, "CHUNK"));

        List<ChunkEvidence> merged = QaContext.mergeEvidence(accumulated, null, List.of());

        assertEquals(1, merged.size());
        assertTrue(merged.contains(accumulated.get(0)));
    }

    @Test
    void merge_allEmpty_returnsEmpty() {
        assertTrue(QaContext.mergeEvidence().isEmpty());
        assertTrue(QaContext.mergeEvidence(List.of(), null).isEmpty());
    }
}
