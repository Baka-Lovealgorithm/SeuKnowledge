package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.config.props.SeuRecallProperties;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.service.vector.VectorSearchService;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识召回节点测试：多查询×多来源（CHUNK/BUSINESS/QA）合并去重；
 * 已保留证据（累计池）从候选排除；queries 为空时短路返回空候选池；
 * 召回条数按 SeuRecallProperties 配置透传给检索器。
 */
class KnowledgeRecallNodeTest {

    private VectorSearchService vectorSearchService;
    private QaTracing qaTracing;
    private KnowledgeRecallNode node;

    @BeforeEach
    void setUp() {
        vectorSearchService = mock(VectorSearchService.class);
        qaTracing = QaTracing.disabled();
        node = new KnowledgeRecallNode(vectorSearchService, qaTracing, new SeuRecallProperties(8, 8),
                Executors.newVirtualThreadPerTaskExecutor(), new SeuQaProperties(20, 2, 32, 30, true, false, false, 0.4));
    }

    private ChunkEvidence ev(long chunkId, String sourceType, double score) {
        return new ChunkEvidence(chunkId, 100L + chunkId, 1L, "doc" + chunkId, 1,
                sourceType, "标题" + chunkId, "内容" + chunkId, score);
    }

    @Test
    void multiQueryHits_mergeAndDedupAcrossSourcesAndQueries() throws Exception {
        // q1：CHUNK[1,2] BUSINESS[3] QA[4]；q2：CHUNK[2,5] BUSINESS[] QA[4]（chunk 2 / QA 4 跨查询重复）
        when(vectorSearchService.search(anyLong(), anyString(), anyInt(), anyList()))
                .thenAnswer(inv -> {
                    String query = inv.getArgument(1);
                    String type = ((List<?>) inv.getArgument(3)).get(0).toString();
                    return switch (query + ":" + type) {
                        case "q1:CHUNK" -> List.of(ev(1, "CHUNK", 0.9), ev(2, "CHUNK", 0.8));
                        case "q1:BUSINESS" -> List.of(ev(3, "BUSINESS", 0.7));
                        case "q1:QA" -> List.of(ev(4, "QA", 0.6));
                        case "q2:CHUNK" -> List.of(ev(2, "CHUNK", 0.8), ev(5, "CHUNK", 0.5));
                        case "q2:QA" -> List.of(ev(4, "QA", 0.6));
                        default -> List.of();
                    };
                });

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.KB_ID, 1L);
        data.put(QaContextKey.QUERIES, List.of("q1", "q2"));

        Map<String, Object> out = node.apply(new OverAllState(data));

        List<ChunkEvidence> chunks = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(5, chunks.size(), "跨查询/跨来源按 sourceType:chunkId 去重后应为 5 条");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), chunks.stream().map(ChunkEvidence::chunkId).toList());
        assertEquals(QaState.RERANK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void accumulatedEvidence_excludedFromCandidates() throws Exception {
        ChunkEvidence kept = ev(1, "CHUNK", 0.9);
        when(vectorSearchService.search(anyLong(), anyString(), anyInt(), anyList()))
                .thenReturn(List.of(kept));

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.KB_ID, 1L);
        data.put(QaContextKey.QUERIES, List.of("q1"));
        data.put(QaContextKey.ACCUMULATED_CHUNKS, List.of(kept));

        Map<String, Object> out = node.apply(new OverAllState(data));

        assertTrue(QaContext.chunks(out.get(QaContextKey.CHUNKS)).isEmpty(),
                "累计池中已保留的证据不应再次进入 rerank 候选");
        assertEquals(QaState.RERANK.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void emptyQueries_returnsEmptyChunksWithoutSearch() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.KB_ID, 1L);
        data.put(QaContextKey.QUERIES, List.of());

        Map<String, Object> out = node.apply(new OverAllState(data));

        assertTrue(QaContext.chunks(out.get(QaContextKey.CHUNKS)).isEmpty());
        assertEquals(QaState.RERANK.name(), out.get(QaContextKey.NEXT));
        verify(vectorSearchService, never()).search(anyLong(), anyString(), anyInt(), anyList());
    }

    @Test
    void configuredTopK_passedToSearcher() throws Exception {
        // 证据池扩容：CHUNK 每查询召回 15、BUSINESS/QA 各 8——topK 必须按配置透传
        KnowledgeRecallNode expanded = new KnowledgeRecallNode(vectorSearchService, qaTracing,
                new SeuRecallProperties(15, 8), Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, true, false, false, 0.4));
        when(vectorSearchService.search(anyLong(), anyString(), anyInt(), anyList())).thenReturn(List.of());

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.KB_ID, 1L);
        data.put(QaContextKey.QUERIES, List.of("q1"));

        expanded.apply(new OverAllState(data));

        verify(vectorSearchService).search(1L, "q1", 15, List.of("CHUNK"));
        verify(vectorSearchService).search(1L, "q1", 8, List.of("BUSINESS"));
        verify(vectorSearchService).search(1L, "q1", 8, List.of("QA"));
    }
}
