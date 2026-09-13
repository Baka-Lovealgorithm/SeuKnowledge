package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引 mapping 构建与子字段判断的单元测试（不触达 ES：ElasticsearchClient 父类非 public 无法 mock，
 * 故被测对象均为静态构建方法）。
 */
class VectorIndexServiceTest {

    @Test
    void newMappings_titleAndDocNameCarryAnalyzedTextSubField() {
        Map<String, Property> mappings = VectorIndexService.newMappings(1024);

        assertKeywordWithTextSubField(mappings.get(ChunkDocFields.TITLE));
        assertKeywordWithTextSubField(mappings.get(ChunkDocFields.DOC_NAME));
        assertEquals(9, mappings.size());
    }

    @Test
    void newMappings_contentIsSmartcnText() {
        Property content = VectorIndexService.newMappings(1024).get(ChunkDocFields.CONTENT);
        TextProperty text = content.text();
        assertNotNull(text);
        assertEquals("smartcn", text.analyzer());
        assertEquals("smartcn", text.searchAnalyzer());
    }

    @Test
    void newMappings_vectorKeepsConfiguredDimensionsAndCosine() {
        Property vector = VectorIndexService.newMappings(768).get(ChunkDocFields.CONTENT_VECTOR);
        assertNotNull(vector.denseVector());
        assertEquals(768, vector.denseVector().dims());
        assertEquals("cosine", vector.denseVector().similarity().jsonValue());
    }

    @Test
    void missingTextSubField_nullPropertyIsMissing() {
        assertTrue(VectorIndexService.missingTextSubField(null));
    }

    @Test
    void missingTextSubField_plainKeywordIsMissing() {
        Property plain = Property.of(p -> p.keyword(k -> k));
        assertTrue(VectorIndexService.missingTextSubField(plain));
    }

    @Test
    void missingTextSubField_nonKeywordPropertyIsMissing() {
        Property text = Property.of(p -> p.text(t -> t.analyzer("standard")));
        assertTrue(VectorIndexService.missingTextSubField(text));
    }

    @Test
    void missingTextSubField_keywordWithTextSubFieldIsPresent() {
        Property withSubField = VectorIndexService.keywordWithTextSubField();
        assertFalse(VectorIndexService.missingTextSubField(withSubField));
    }

    private static void assertKeywordWithTextSubField(Property property) {
        assertTrue(property.isKeyword());
        Property subField = property.keyword().fields().get(VectorIndexService.TEXT_SUB_FIELD);
        assertNotNull(subField, "必须挂可分词的 .text 子字段，否则 BM25 标题召回无效");
        assertEquals("smartcn", subField.text().analyzer());
        assertEquals("smartcn", subField.text().searchAnalyzer());
    }
}
