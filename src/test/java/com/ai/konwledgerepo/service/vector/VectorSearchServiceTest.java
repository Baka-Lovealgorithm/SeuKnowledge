package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BM25 查询体构建的单元测试（不触达 ES）：核心断言是标题/文档名必须打在可分词的
 * {@code .text} 子字段上——打在顶层 keyword 上时自然语言问句永远无法命中（历史缺陷）。
 */
class VectorSearchServiceTest {

    @Test
    void bm25Query_queriesAnalyzedSubFieldsWithBoosts() {
        Query query = VectorSearchService.bm25Query(7L, "研究意义", null);

        List<String> fields = query.bool().must().get(0).multiMatch().fields();
        assertTrue(fields.contains(ChunkDocFields.CONTENT + "^1.0"), "正文为主");
        assertTrue(fields.contains(ChunkDocFields.TITLE_TEXT + "^2.0"),
                "标题必须打在 " + ChunkDocFields.TITLE_TEXT + " 上，顶层 keyword 无法被自然语言命中");
        assertTrue(fields.contains(ChunkDocFields.DOC_NAME_TEXT + "^0.5"), "文档名辅助低权重");
        assertEquals(3, fields.size());
    }

    @Test
    void bm25Query_filtersByKbId() {
        Query query = VectorSearchService.bm25Query(7L, "研究意义", null);

        assertEquals(1, query.bool().filter().size());
        assertEquals("kbId", query.bool().filter().get(0).term().field());
        assertEquals(7L, query.bool().filter().get(0).term().value().longValue());
    }

    @Test
    void bm25Query_appendsSourceTypeFilterWhenProvided() {
        Query sourceFilter = Query.of(q -> q.term(t -> t.field("sourceType").value("CHUNK")));
        Query query = VectorSearchService.bm25Query(7L, "研究意义", sourceFilter);

        assertEquals(2, query.bool().filter().size());
        assertEquals("sourceType", query.bool().filter().get(1).term().field());
    }
}
