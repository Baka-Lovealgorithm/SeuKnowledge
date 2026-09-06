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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识召回节点测试：多查询×多来源（CHUNK/BUSINESS/QA）合并去重，跨查询 RRF 分数累加
 * （多查询共同命中分数相加，候选池按累加分降序输出）；已保留证据（累计池）从候选排除；
 * queries 为空时短路返回空候选池；召回条数按 SeuRecallProperties 配置透传给检索器。
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
                Executors.newVirtualThreadPerTaskExecutor(), new SeuQaProperties(20, 2, 32, 30, true, false, false, 0.4, false, 60, 200));
    }

    private ChunkEvidence ev(long chunkId, String sourceType, double score) {
        return new ChunkEvidence(chunkId, 100L + chunkId, 1L, "doc" + chunkId, 1,
                sourceType, "标题" + chunkId, "内容" + chunkId, score);
    }

    @Test
    void multiQueryHits_dedupAndAccumulateScoresAcrossQueries() throws Exception {
        // searchBySources(kbId, query, chunkTop, sourceTop) 返回 [CHUNK, BUSINESS, QA] 顺序合并结果。
        // q1：CHUNK[1,2] BUSINESS[3] QA[4]；q2：CHUNK[2,5] BUSINESS[] QA[4]（chunk 2 / QA 4 跨查询重复）
        when(vectorSearchService.searchBySources(anyLong(), anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    String query = inv.getArgument(1);
                    return switch (query) {
                        case "q1" -> List.of(ev(1, "CHUNK", 0.9), ev(2, "CHUNK", 0.8),
                                ev(3, "BUSINESS", 0.7), ev(4, "QA", 0.6));
                        case "q2" -> List.of(ev(2, "CHUNK", 0.8), ev(5, "CHUNK", 0.5), ev(4, "QA", 0.6));
                        default -> List.of();
                    };
                });

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.KB_ID, 1L);
        data.put(QaContextKey.QUERIES, List.of("q1", "q2"));

        Map<String, Object> out = node.apply(new OverAllState(data));

        List<ChunkEvidence> chunks = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(5, chunks.size(), "跨查询/跨来源按 sourceType:chunkId 去重后应为 5 条");
        // 跨查询重复命中的分数累加（RAG-Fusion）：chunk 2 = 0.8+0.8、QA 4 = 0.6+0.6，
        // 候选池按累加分降序输出（同分保首现顺序）
        assertEquals(List.of(2L, 4L, 1L, 3L, 5L), chunks.stream().map(ChunkEvidence::chunkId).toList());
        Map<Long, Double> scoreById = chunks.stream()
                .collect(Collectors.toMap(ChunkEvidence::chunkId, ChunkEvidence::score));
        assertEquals(1.6, scoreById.get(2L), 1e-9, "chunk 2 被两条查询命中：0.8 + 0.8");
        assertEquals(1.2, scoreById.get(4L), 1e-9, "QA 4 被两条查询命中：0.6 + 0.6");
        assertEquals(0.9, scoreById.get(1L), 1e-9, "单查询命中不累加，保持原分");
        assertEquals(0.5, scoreById.get(5L), 1e-9, "单查询命中不累加，保持原分");
        assertEquals(QaState.RERANK.name(), out.get(QaContextKey.NEXT));

        // 关键：每条 query 只触发一次 searchBySources（即每条 query 仅向量化一次，向量在一轮检索内复用）
        verify(vectorSearchService, times(2)).searchBySources(anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void accumulatedEvidence_excludedFromCandidates() throws Exception {
        ChunkEvidence kept = ev(1, "CHUNK", 0.9);
        when(vectorSearchService.searchBySources(anyLong(), anyString(), anyInt(), anyInt()))
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
        verify(vectorSearchService, never()).searchBySources(anyLong(), anyString(), anyInt(), anyInt());
        verify(vectorSearchService, never()).search(anyLong(), anyString(), anyInt(), anyList());
    }

    @Test
    void configuredTopK_passedToSearcher() throws Exception {
        // 证据池扩容：CHUNK 每查询召回 15、BUSINESS/QA 各 8——chunkTop/sourceTop 必须按配置透传
        KnowledgeRecallNode expanded = new KnowledgeRecallNode(vectorSearchService, qaTracing,
                new SeuRecallProperties(15, 8), Executors.newVirtualThreadPerTaskExecutor(),
                new SeuQaProperties(20, 2, 32, 30, true, false, false, 0.4, false, 60, 200));
        when(vectorSearchService.searchBySources(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of());

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.KB_ID, 1L);
        data.put(QaContextKey.QUERIES, List.of("q1"));

        expanded.apply(new OverAllState(data));

        // 每条 query 只调用一次 searchBySources，且 topK 按配置透传（chunkTop=15、sourceTop=8）
        verify(vectorSearchService).searchBySources(1L, "q1", 15, 8);
        verify(vectorSearchService, times(1)).searchBySources(anyLong(), anyString(), anyInt(), anyInt());
    }
}
