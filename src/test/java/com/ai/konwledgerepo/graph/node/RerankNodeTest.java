package com.ai.konwledgerepo.graph.node;

import com.ai.konwledgerepo.config.props.SeuRerankProperties;
import com.ai.konwledgerepo.graph.ChunkEvidence;
import com.ai.konwledgerepo.graph.EvidenceReranker;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaState;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.QaTracing;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重排节点测试：交叉编码器启用 → 按重排分截断配额；未配置/异常 → 按 ES 分降级截断；
 * 空候选短路；CHUNK 与 业务知识+问答对 分组各取配额；重排模型按工作空间动态解析；
 * 组内候选不超配额（且不超 maxDocs）→ 跳过精排按 ES 分全保留。
 */
class RerankNodeTest {

    private static final long WS = 7L;

    private ModelFactory modelFactory;
    private EvidenceReranker reranker;
    private QaTracing qaTracing;

    @BeforeEach
    void setUp() {
        modelFactory = mock(ModelFactory.class);
        reranker = mock(EvidenceReranker.class);
        qaTracing = QaTracing.disabled();
    }

    private ChunkEvidence ev(long chunkId, String sourceType, double esScore) {
        return new ChunkEvidence(chunkId, 100L + chunkId, 1L, "doc" + chunkId, 1,
                sourceType, "标题" + chunkId, "内容" + chunkId, esScore);
    }

    private List<ChunkEvidence> fiveChunks() {
        return List.of(
                ev(1, "CHUNK", 0.9), ev(2, "CHUNK", 0.8), ev(3, "CHUNK", 0.7),
                ev(4, "CHUNK", 0.6), ev(5, "CHUNK", 0.5));
    }

    private OverAllState state(List<ChunkEvidence> chunks) {
        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "如何申请报销？");
        data.put(QaContextKey.WORKSPACE_ID, WS);
        data.put(QaContextKey.CHUNKS, chunks);
        return new OverAllState(data);
    }

    private RerankNode node() {
        return new RerankNode(modelFactory, qaTracing, new SeuRerankProperties(4, 4, 20, 1500, 10000));
    }

    @Test
    void rerankConfigured_cutsByQuotaWithRerankScores() throws Exception {
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(20);
        // 原 ES 分最高的 chunk 重排分最低 → 被挤出前 4
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(0.1, 0.9, 0.8, 0.7, 0.6));

        Map<String, Object> out = node().apply(state(fiveChunks()));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(4, result.size(), "chunkTop=4 应按重排分截断为 4 条");
        assertEquals(2L, result.get(0).chunkId(), "重排分最高者应排第一");
        assertEquals(QaState.ANSWER_COMPOSE.name(), out.get(QaContextKey.NEXT));
        assertEquals(4, QaContext.chunks(out.get(QaContextKey.ACCUMULATED_CHUNKS)).size(),
                "本轮精选应并入累计证据池");
    }

    @Test
    void rerankNotConfigured_fallsBackToEsScore() throws Exception {
        // 未配置重排模型（Optional.empty）→ 降级按 ES 分截断
        when(modelFactory.getReranker(WS)).thenReturn(Optional.empty());

        Map<String, Object> out = node().apply(state(fiveChunks()));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(4, result.size(), "未配置编码器时按 ES 分同配额截断");
        assertEquals(1L, result.get(0).chunkId(), "降级按 ES 分排序，最高分应排第一");
        assertEquals(QaState.ANSWER_COMPOSE.name(), out.get(QaContextKey.NEXT));
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void rerankConfiguredButDisabled_fallsBackToEsScore() throws Exception {
        // 有配置但 isConfigured=false（如 apiKey 为空）→ 同样降级
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(false);

        Map<String, Object> out = node().apply(state(fiveChunks()));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(4, result.size());
        assertEquals(1L, result.get(0).chunkId());
        verify(reranker, never()).rerank(anyString(), anyList());
    }

    @Test
    void rerankThrows_degradesToEsScore() throws Exception {
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(20);
        when(reranker.rerank(anyString(), anyList())).thenThrow(new RuntimeException("rerank 服务不可用"));

        Map<String, Object> out = node().apply(state(fiveChunks()));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(4, result.size(), "精排异常应降级为按 ES 分截断，保证链路可用");
        assertEquals(1L, result.get(0).chunkId());
        assertEquals(QaState.ANSWER_COMPOSE.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    void emptyChunks_returnsEmptyAndRoutesToCompose() throws Exception {
        Map<String, Object> out = node().apply(state(List.of()));

        assertTrue(QaContext.chunks(out.get(QaContextKey.CHUNKS)).isEmpty());
        assertEquals(QaState.ANSWER_COMPOSE.name(), out.get(QaContextKey.NEXT));
        verify(modelFactory, never()).getReranker(anyLong());
    }

    @Test
    void rerankQuery_usesEffectiveQuestion() throws Exception {
        // 精排 query 与召回/生成同源：优先消歧后有效问题，而非原始指代句（指代句打分语义失真）
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(20);
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(0.5, 0.5));

        Map<String, Object> data = new HashMap<>();
        data.put(QaContextKey.RAW_QUESTION, "它怎么申请？");
        data.put(QaContextKey.RESOLVED_QUESTION, "国家奖学金的申请条件是什么");
        data.put(QaContextKey.WORKSPACE_ID, WS);
        data.put(QaContextKey.CHUNKS, fiveChunks());
        node().apply(new OverAllState(data));

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(reranker).rerank(queryCaptor.capture(), anyList());
        assertEquals("国家奖学金的申请条件是什么", queryCaptor.getValue(),
                "重排 query 应为消歧后有效问题（effectiveQuestion），而非 RAW_QUESTION");
    }

    @Test
    void metadataPrefix_prependsDocNameAndChapter() {
        ChunkEvidence c = new ChunkEvidence(1L, 2L, 3L, "ZRDDS用户手册.pdf", 5,
                "CHUNK", "3.2 编译 IDL 文件", "正文", 0.9);
        String prefix = RerankNode.metadataPrefix(c);
        assertTrue(prefix.contains("文档：ZRDDS用户手册.pdf"), "应包含文档名: " + prefix);
        assertTrue(prefix.contains("章节：3.2 编译 IDL 文件"), "应包含章节标题: " + prefix);
        assertTrue(prefix.endsWith("\n"), "应以换行结束便于拼接正文");
    }

    @Test
    void metadataPrefix_skipsBlankOrDuplicateTitle() {
        // 标题与文档名相同（封面页）→ 不重复拼接章节
        ChunkEvidence dup = new ChunkEvidence(1L, 2L, 3L, "用户手册", 1, "CHUNK", "用户手册", "正文", 0.9);
        String dupPrefix = RerankNode.metadataPrefix(dup);
        assertTrue(dupPrefix.contains("文档：用户手册"), "应包含文档名: " + dupPrefix);
        assertTrue(!dupPrefix.contains("章节："), "标题与文档名相同时不应重复拼接: " + dupPrefix);

        // 标题与文档名为空 → 空前缀（纯正文）
        ChunkEvidence blank = new ChunkEvidence(1L, 2L, 3L, "", 1, "CHUNK", "", "正文", 0.9);
        assertEquals("", RerankNode.metadataPrefix(blank));

        assertEquals("", RerankNode.metadataPrefix(null), "null 证据应返回空前缀");
    }

    @Test
    void mixedSources_cutsChunkAndOtherGroupsByRespectiveQuota() throws Exception {
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(20);
        // 重排分与 ES 分同序，便于断言分组配额
        when(reranker.rerank(anyString(), anyList())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(1);
            List<Double> scores = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                scores.add(1.0 - i * 0.1);
            }
            return scores;
        });

        List<ChunkEvidence> mixed = List.of(
                ev(1, "CHUNK", 0.9), ev(2, "CHUNK", 0.8), ev(3, "CHUNK", 0.7),
                ev(4, "BUSINESS", 0.6), ev(5, "BUSINESS", 0.5),
                ev(6, "QA", 0.4), ev(7, "QA", 0.3));

        RerankNode node = new RerankNode(modelFactory, qaTracing, new SeuRerankProperties(2, 3, 20, 1500, 10000));
        Map<String, Object> out = node.apply(state(mixed));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(5, result.size(), "chunk 组 3 条取 2 + other 组 4 条取 3 = 5 条");
        assertEquals(2, result.stream().filter(c -> "CHUNK".equals(c.sourceType())).count());
        assertEquals(3, result.stream().filter(c -> !"CHUNK".equals(c.sourceType())).count());
    }

    @Test
    void groupWithinQuota_skipsRerankKeepsAllByEsOrder() throws Exception {
        // 组内候选不超配额 → 精排不淘汰任何条目，跳过调用按 ES 分降序全保留（省一次精排请求）
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(20);

        List<ChunkEvidence> three = List.of(
                ev(1, "CHUNK", 0.7), ev(2, "CHUNK", 0.9), ev(3, "CHUNK", 0.8));
        Map<String, Object> out = node().apply(state(three));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(3, result.size(), "不超配额应全保留");
        assertEquals(2L, result.get(0).chunkId(), "按 ES 分降序");
        assertEquals(3L, result.get(1).chunkId());
        assertEquals(1L, result.get(2).chunkId());
        verify(reranker, never()).rerank(anyString(), anyList());
        assertEquals(QaState.ANSWER_COMPOSE.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixed_otherGroupWithinQuota_skipsOtherRerankOnly() throws Exception {
        // chunk 组超配额正常精排，other 组不超配额跳过 → 仅 1 次精排请求且入参不含业务/问答条目
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(20);
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(0.5, 0.4, 0.3, 0.2, 0.1));

        List<ChunkEvidence> mixed = List.of(
                ev(1, "CHUNK", 0.9), ev(2, "CHUNK", 0.8), ev(3, "CHUNK", 0.7),
                ev(4, "CHUNK", 0.6), ev(5, "CHUNK", 0.5),
                ev(6, "BUSINESS", 0.4), ev(7, "QA", 0.3));
        Map<String, Object> out = node().apply(state(mixed));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(6, result.size(), "chunk 组 5 取 4 + other 组 2 全保留 = 6 条");
        assertEquals(2, result.stream().filter(c -> !"CHUNK".equals(c.sourceType())).count(),
                "other 组不超配额应全保留");
        ArgumentCaptor<List<String>> docsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(reranker, times(1)).rerank(anyString(), docsCaptor.capture());
        assertTrue(docsCaptor.getValue().stream().noneMatch(d -> String.valueOf(d).contains("内容6")
                        || String.valueOf(d).contains("内容7")),
                "精排入参只应包含 chunk 组文档");
        assertEquals(QaState.ANSWER_COMPOSE.name(), out.get(QaContextKey.NEXT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupAboveMaxDocs_evenWithinQuota_stillReranks() throws Exception {
        // 候选虽不超配额但超 maxDocs → 精排会截断集合，不可跳过（钉死 min(quota, maxDocs) 谓词）
        when(modelFactory.getReranker(WS)).thenReturn(Optional.of(reranker));
        when(reranker.isConfigured()).thenReturn(true);
        when(reranker.maxDocs()).thenReturn(2);
        when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(0.9, 0.1));

        List<ChunkEvidence> three = List.of(
                ev(1, "CHUNK", 0.9), ev(2, "CHUNK", 0.8), ev(3, "CHUNK", 0.7));
        RerankNode maxDocsNode = new RerankNode(modelFactory, qaTracing,
                new SeuRerankProperties(4, 4, 2, 1500, 10000));
        Map<String, Object> out = maxDocsNode.apply(state(three));

        List<ChunkEvidence> result = QaContext.chunks(out.get(QaContextKey.CHUNKS));
        assertEquals(2, result.size(), "maxDocs=2 截断后精排返回 2 条");
        ArgumentCaptor<List<String>> docsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(reranker, times(1)).rerank(anyString(), docsCaptor.capture());
        assertEquals(2, docsCaptor.getValue().size(), "精排入参应按 maxDocs 截断为 2 条");
        assertEquals(1L, result.get(0).chunkId(), "重排分最高者（chunk 1）应排第一");
    }
}
