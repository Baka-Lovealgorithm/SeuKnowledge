package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/**
 * ES 向量索引管理。启动时校验/创建 chunk 索引（kb_chunk）。
 *
 * 字段设计：kbId/docId/chunkId/docName/pageNum/title/sourceType 为过滤与引用元数据
 * （sourceType：CHUNK / BUSINESS / QA，多源召回按来源过滤与加权）；
 * content 为文本（可检索）；contentVector 为 dense_vector（余弦相似度）。
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
                    .mappings(m -> m
                            .properties(ChunkDocFields.KB_ID, p -> p.long_(l -> l))
                            .properties(ChunkDocFields.DOC_ID, p -> p.long_(l -> l))
                            .properties(ChunkDocFields.CHUNK_ID, p -> p.long_(l -> l))
                            .properties(ChunkDocFields.DOC_NAME, p -> p.keyword(k -> k))
                            .properties(ChunkDocFields.PAGE_NUM, p -> p.integer(i -> i))
                            .properties(ChunkDocFields.TITLE, p -> p.keyword(k -> k))
                            .properties(ChunkDocFields.SOURCE_TYPE, p -> p.keyword(k -> k))
                            // content 使用 ES 内置 smartcn 中文分词器（检索与索引一致），需 ES 安装 analysis-smartcn 插件
                            .properties(ChunkDocFields.CONTENT, p -> p.text(t -> t.analyzer("smartcn").searchAnalyzer("smartcn")))
                            .properties(ChunkDocFields.CONTENT_VECTOR, p -> p.denseVector(
                                    dv -> dv.dims(vectorDimensions).similarity(DenseVectorSimilarity.Cosine)))));
            log.info("ES 索引 {} 创建成功（向量维度 {}）", indexName, vectorDimensions);
            return true;
        } catch (Exception e) {
            log.warn("ES 索引创建失败（不影响应用启动，请检查 ES 连接与权限）：{}", e.getMessage());
            return false;
        }
    }

    /** 旧索引补充多源召回字段（sourceType / title / cleanStatus），字段已存在时幂等无副作用 */
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
    }
}
