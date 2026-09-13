package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ES 向量索引管理。启动时校验/创建 chunk 索引（kb_chunk）。
 *
 * 字段设计：kbId/docId/chunkId/docName/pageNum/title/sourceType 为过滤与引用元数据
 * （sourceType：CHUNK / BUSINESS / QA，多源召回按来源过滤与加权）；
 * content 为文本（可检索）；contentVector 为 dense_vector（余弦相似度）。
 * title / docName 顶层为 keyword（全串精确匹配），另挂可分词的 {@code .text} 子字段
 * （smartcn）供 BM25 标题/文档名召回——旧索引启动时自动原地补充子字段并回填存量文档。
 */
@Service
public class VectorIndexService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final ElasticsearchClient esClient;
    private final String indexName;
    private final int vectorDimensions;

    public VectorIndexService(ElasticsearchClient esClient, SeuEsProperties esProps) {
        this.esClient = esClient;
        this.indexName = esProps.indexName();
        this.vectorDimensions = esProps.vectorDimensions();
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureIndex();
    }

    /**
     * 确保索引存在；不存在则创建。ES 不可用时仅告警，不阻断应用启动。
     * 对已存在的旧索引补充新增字段（sourceType / title），幂等执行。
     */
    public boolean ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (exists) {
                ensureExtraFields();
                log.info("ES 索引 {} 已存在", indexName);
                return true;
            }
            esClient.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m.properties(newMappings(vectorDimensions))));
            log.info("ES 索引 {} 创建成功（向量维度 {}）", indexName, vectorDimensions);
            return true;
        } catch (Exception e) {
            log.warn("ES 索引创建失败（不影响应用启动，请检查 ES 连接与权限）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 旧索引补充：多源召回字段（sourceType / title / cleanStatus）+ 可分词子字段（title.text / docName.text），幂等执行。
     * 子字段追加成功后回填一次存量文档（update_by_query 原样重写，向量不变、零 embedding 成本）；
     * 子字段已存在则跳过，避免每次启动重复全量 reindex。失败仅告警——此时 BM25 查询对未映射子字段
     * 自动忽略，退化为只查 content，与历史行为一致。
     */
    private void ensureExtraFields() {
        try {
            esClient.indices().putMapping(p -> p
                    .index(indexName)
                    .properties(ChunkDocFields.TITLE, pr -> pr.keyword(k -> k))
                    .properties(ChunkDocFields.SOURCE_TYPE, pr -> pr.keyword(k -> k))
                    .properties(ChunkDocFields.CLEAN_STATUS, pr -> pr.keyword(k -> k)));
        } catch (Exception e) {
            log.warn("ES 索引补充字段失败（不影响存量检索）：{}", e.getMessage());
        }
        ensureAnalyzedSubFields();
    }

    /** 子字段名：{@code title.text} / {@code docName.text} */
    static final String TEXT_SUB_FIELD = "text";

    /** smartcn 分词的 text 属性定义（与 content 的检索口径一致） */
    private static TextProperty analyzedText() {
        return TextProperty.of(t -> t.analyzer("smartcn").searchAnalyzer("smartcn"));
    }

    /** keyword 顶层 + 可分词 {@code .text} 子字段的完整属性（新建索引与旧索引补充共用同一份定义） */
    static Property keywordWithTextSubField() {
        return Property.of(p -> p.keyword(k -> k.fields(TEXT_SUB_FIELD, f -> f.text(analyzedText()))));
    }

    /**
     * 新建索引的完整 mapping properties（静态包内可见，供单元测试断言）。
     * title / docName 为 keyword + {@code .text}（smartcn）子字段；content 为 smartcn 分词 text
     * （需 ES 安装 analysis-smartcn 插件）；contentVector 为 dense_vector 余弦相似度。
     */
    static Map<String, Property> newMappings(int vectorDimensions) {
        return Map.of(
                ChunkDocFields.KB_ID, Property.of(p -> p.long_(l -> l)),
                ChunkDocFields.DOC_ID, Property.of(p -> p.long_(l -> l)),
                ChunkDocFields.CHUNK_ID, Property.of(p -> p.long_(l -> l)),
                ChunkDocFields.DOC_NAME, keywordWithTextSubField(),
                ChunkDocFields.PAGE_NUM, Property.of(p -> p.integer(i -> i)),
                ChunkDocFields.TITLE, keywordWithTextSubField(),
                ChunkDocFields.SOURCE_TYPE, Property.of(p -> p.keyword(k -> k)),
                ChunkDocFields.CONTENT, Property.of(p -> p.text(analyzedText())),
                ChunkDocFields.CONTENT_VECTOR, Property.of(p -> p.denseVector(
                        dv -> dv.dims(vectorDimensions).similarity(DenseVectorSimilarity.Cosine))));
    }

    /**
     * 检查旧索引是否已挂 {@code title.text} / {@code docName.text} 子字段；缺哪个补哪个，
     * 补完回填一次存量文档。全都有则直接返回（幂等）。
     */
    private void ensureAnalyzedSubFields() {
        boolean titleMissing;
        boolean docNameMissing;
        try {
            // indexName 可能是别名：getMapping 响应按具体索引名做键，故取唯一条目而非按键名取
            Map<String, IndexMappingRecord> result = esClient.indices()
                    .getMapping(g -> g.index(indexName))
                    .result();
            if (result.isEmpty()) {
                log.warn("ES 索引 mapping 为空，跳过子字段补充（不影响存量检索）");
                return;
            }
            Map<String, Property> props = result.values().iterator().next().mappings().properties();
            titleMissing = missingTextSubField(props.get(ChunkDocFields.TITLE));
            docNameMissing = missingTextSubField(props.get(ChunkDocFields.DOC_NAME));
        } catch (Exception e) {
            log.warn("ES 索引 mapping 读取失败，跳过子字段补充（不影响存量检索）：{}", e.getMessage());
            return;
        }
        if (!titleMissing && !docNameMissing) {
            return;
        }
        try {
            esClient.indices().putMapping(p -> {
                p.index(indexName);
                if (titleMissing) {
                    p.properties(ChunkDocFields.TITLE,
                            pr -> pr.keyword(keywordWithTextSubField().keyword()));
                }
                if (docNameMissing) {
                    p.properties(ChunkDocFields.DOC_NAME,
                            pr -> pr.keyword(keywordWithTextSubField().keyword()));
                }
                return p;
            });
        } catch (Exception e) {
            log.warn("ES 索引补充分词子字段失败（标题/文档名暂不参与 BM25 召回，不影响存量检索）：{}", e.getMessage());
            return;
        }
        try {
            long updated = esClient.updateByQuery(u -> u
                            .index(indexName)
                            .conflicts(Conflicts.Proceed)
                            .refresh(true))
                    .updated();
            log.info("ES 索引已补充分词子字段（title.text={} / docName.text={}），回填存量文档 {} 条",
                    titleMissing, docNameMissing, updated);
        } catch (Exception e) {
            log.warn("ES 子字段回填 update_by_query 失败（可重跑或参考 ES_TITLE_RECALL_UPGRADE.md 手工执行）：{}", e.getMessage());
        }
    }

    /** keyword 属性缺失或未挂 {@code .text} 子字段时返回 true（包内可见供单元测试；非 keyword 类型同样视为缺失） */
    static boolean missingTextSubField(Property property) {
        if (property == null || !property.isKeyword()) {
            return true;
        }
        Map<String, Property> fields = property.keyword().fields();
        return fields == null || fields.get(TEXT_SUB_FIELD) == null;
    }
}
