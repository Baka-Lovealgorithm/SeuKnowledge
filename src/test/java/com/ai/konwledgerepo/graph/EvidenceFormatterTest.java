package com.ai.konwledgerepo.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 证据统一渲染测试：JSON 结构、字段回退、标记、空列表。
 */
class EvidenceFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChunkEvidence ev(long chunkId, String docName, String title, String content, String sourceType, Integer pageNum) {
        return new ChunkEvidence(chunkId, 100L + chunkId, 1L, docName, pageNum, sourceType, title, content, 0.9);
    }

    @Test
    void singleChunk_allFieldsPresent() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(11L, "报销制度.pdf", "报销流程", "第一步：填写申请表", "CHUNK", 3)));
        JsonNode arr = MAPPER.readTree(json);
        assertEquals(1, arr.size());
        JsonNode item = arr.get(0);
        assertEquals(1, item.get("index").asInt());
        assertEquals("CHUNK", item.get("sourceType").asText());
        assertEquals("报销制度.pdf", item.get("docName").asText());
        assertEquals(3, item.get("page").asInt());
        assertEquals("报销流程", item.get("title").asText());
        assertEquals("第一步：填写申请表", item.get("content").asText());
        assertFalse(item.get("new").asBoolean());
    }

    @Test
    void nullPageNum_outputsZero() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(11L, "doc.pdf", "标题", "内容", "CHUNK", null)));
        JsonNode arr = MAPPER.readTree(json);
        assertEquals(0, arr.get(0).get("page").asInt());
    }

    @Test
    void blankTitle_fallsBackToDocName() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(11L, "doc.pdf", "  ", "内容", "CHUNK", 1)));
        JsonNode item = MAPPER.readTree(json).get(0);
        assertEquals("doc.pdf", item.get("title").asText());
    }

    @Test
    void nullTitle_fallsBackToDocName() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(11L, "doc.pdf", null, "内容", "CHUNK", 1)));
        JsonNode item = MAPPER.readTree(json).get(0);
        assertEquals("doc.pdf", item.get("title").asText());
    }

    @Test
    void indexIsOneBased() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(1L, "a.pdf", "A", "ca", "CHUNK", 1),
                ev(2L, "b.pdf", "B", "cb", "CHUNK", 2),
                ev(3L, "c.pdf", "C", "cc", "BUSINESS", null)));
        JsonNode arr = MAPPER.readTree(json);
        assertEquals(3, arr.size());
        assertEquals(1, arr.get(0).get("index").asInt());
        assertEquals(2, arr.get(1).get("index").asInt());
        assertEquals(3, arr.get(2).get("index").asInt());
        assertEquals("BUSINESS", arr.get(2).get("sourceType").asText());
    }

    @Test
    void markKeys_newFieldTrue() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(11L, "a.pdf", "A", "ca", "CHUNK", 1),
                ev(12L, "b.pdf", "B", "cb", "CHUNK", 2)),
                Set.of("CHUNK:11"));
        JsonNode arr = MAPPER.readTree(json);
        assertTrue(arr.get(0).get("new").asBoolean());
        assertFalse(arr.get(1).get("new").asBoolean());
    }

    @Test
    void emptyList_returnsEmptyArray() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of());
        assertEquals("[]", json);
    }

    @Test
    void contentWithSpecialChars_jsonEscaped() throws Exception {
        String json = EvidenceFormatter.toEvidenceJson(List.of(
                ev(1L, "doc.pdf", "标题", "包含\"引号\"与\n换行", "CHUNK", 1)));
        JsonNode item = MAPPER.readTree(json).get(0);
        assertEquals("包含\"引号\"与\n换行", item.get("content").asText());
    }
}