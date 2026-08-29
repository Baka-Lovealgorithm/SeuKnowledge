package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.entity.SourceType;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量化与 ES 写入服务：文档 chunk、业务知识、问答对统一写入 ES（kb_chunk 索引），
 * 通过 sourceType 字段区分来源（CHUNK / BUSINESS / QA）。
 * chunk 文档 _id 使用 chunk id；业务知识/问答对 _id 使用 "sourceType-entityId"。
 */
@Service
public class VectorIngestionService {

    private static final Logger log = LoggerFactory.getLogger(VectorIngestionService.class);

    private final ElasticsearchClient esClient;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final WorkspaceIdResolver workspaceIdResolver;
    private final ModelFactory modelFactory;
    private final String indexName;

    public VectorIngestionService(ElasticsearchClient esClient,
                                  ChunkRepository chunkRepository,
                                  DocumentRepository documentRepository,
                                  WorkspaceIdResolver workspaceIdResolver,
                                  ModelFactory modelFactory,
                                  SeuEsProperties esProps) {
        this.esClient = esClient;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.workspaceIdResolver = workspaceIdResolver;
        this.modelFactory = modelFactory;
        this.indexName = esProps.indexName();
    }

    /**
     * 为文档所有待向量化 chunk 生成向量并写入 ES。
     * embedding 失败时不阻断（chunk 保持 EMBEDDING，可重试），文档置为 ERROR。
     */
    public void ingest(Long docId) {
        Document doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        List<Chunk> chunks = chunkRepository.findByDocIdOrderBySeqAsc(docId).stream()
                .filter(c -> ChunkStatus.EMBEDDING.is(c.getStatus()))
                .toList();
        if (chunks.isEmpty()) {
            return;
        }
        EmbeddingModel embeddingModel;
        try {
            Long workspaceId = workspaceIdResolver.resolve(doc.getKbId());
            embeddingModel = modelFactory.getEmbeddingModelByUsage(ModelUsage.RETRIEVE.value(), workspaceId);
        } catch (Exception e) {
            log.warn("向量模型未配置，文档 {} 向量化暂缓: {}", docId, e.getMessage());
            return;
        }
        // DashScope 等供应商单次 embedding 请求有批大小上限（text-embedding-v3 服务端限制每批 ≤10 条），
        // 分批调用后按序拼接，避免大文档（>10 chunk）向量化失败。
        List<float[]> vectors;
        try {
            vectors = new ArrayList<>();
            int batchSize = 10;
            List<String> texts = embedTexts(chunks);
            for (int i = 0; i < texts.size(); i += batchSize) {
                List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
                vectors.addAll(embeddingModel.embed(batch));
            }
        } catch (Exception e) {
            log.error("chunk embedding 失败，文档 {}", docId, e);
            doc.setParseStatus(DocStatus.ERROR.value());
            doc.setErrorMsg("向量化失败: " + e.getMessage());
            documentRepository.save(doc);
            return;
        }
        boolean allIndexed = true;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            try {
                String esId = indexChunk(doc, chunk, vectors.get(i));
                chunk.setEsId(esId);
                chunk.setStatus(ChunkStatus.INDEXED.value());
            } catch (IOException e) {
                log.error("chunk {} 写入 ES 失败", chunk.getId(), e);
                chunk.setStatus(ChunkStatus.FAILED.value());
                allIndexed = false;
            }
            chunkRepository.save(chunk);
        }
        log.info("文档 {} 向量化完成（{} 块，全部成功={}）", docId, chunks.size(), allIndexed);
    }

    /**
     * 索引业务知识 / 问答对等结构化来源（sourceType = BUSINESS / QA），与 chunk 共用同一索引。
     * 返回是否写入成功；向量模型未配置或内容为空时不写入。
     */
    public boolean indexSource(String sourceType, Long entityId, Long kbId, Long docId, String docName,
                               int pageNum, String title, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            Long workspaceId = workspaceIdResolver.resolve(kbId);
            EmbeddingModel embeddingModel = modelFactory.getEmbeddingModelByUsage(ModelUsage.RETRIEVE.value(), workspaceId);
            float[] vector = embeddingModel.embed(embedText(title, content));
            esClient.index(i -> i
                    .index(indexName)
                    .id(sourceType + "-" + entityId)
                    .document(ChunkDocFields.document(
                            entityId, docId, kbId, docName, pageNum, title, sourceType, content, vector)));
            return true;
        } catch (Exception e) {
            log.warn("索引来源 {} id={} 失败: {}", sourceType, entityId, e.getMessage());
            return false;
        }
    }

    /** 按来源类型与实体 id 删除 ES 文档（审核拒绝/删除/版本回退时调用） */
    public void deleteBySource(String sourceType, Long entityId) {
        try {
            esClient.delete(d -> d.index(indexName).id(sourceType + "-" + entityId));
        } catch (Exception e) {
            log.warn("删除来源 {} id={} 失败: {}", sourceType, entityId, e.getMessage());
        }
    }

    /** 按文档删除 ES chunk（文档删除时调用） */
    public void deleteByDocId(Long docId) {
        try {
            esClient.deleteByQuery(d -> d.index(indexName)
                    .query(q -> q.term(t -> t.field("docId").value(docId))));
        } catch (IOException e) {
            log.error("删除 ES chunk 失败 docId={}", docId, e);
        }
    }

    /** 按知识库删除全部来源的 ES 文档（CHUNK / BUSINESS / QA，清空知识库内容时调用） */
    public void deleteByKbId(Long kbId) {
        try {
            esClient.deleteByQuery(d -> d.index(indexName)
                    .query(q -> q.term(t -> t.field("kbId").value(kbId))));
            log.info("删除 ES 知识库 {} 全部来源文档完成", kbId);
        } catch (IOException e) {
            log.error("删除 ES 知识库 {} 文档失败", kbId, e);
        }
    }

    private String indexChunk(Document doc, Chunk chunk, float[] vector) throws IOException {
        IndexResponse response = esClient.index(i -> i
                .index(indexName)
                .id(String.valueOf(chunk.getId()))
                .document(Map.of(
                        "chunkId", chunk.getId(),
                        "docId", chunk.getDocId(),
                        "kbId", chunk.getKbId(),
                        "docName", doc.getFileName(),
                        "pageNum", chunk.getPageNum() == null ? 0 : chunk.getPageNum(),
                        "title", chunk.getTitle() == null ? "" : chunk.getTitle(),
                        "content", chunk.getContent(),
                        "sourceType", SourceType.CHUNK.value(),
                        "contentVector", vector)));
        return response.id();
    }

    /**
     * 向量化输入文本：标题 + 正文拼接（标题为空时回退纯正文）。
     * 标题是强语义锚点（chunk 为小节标题、BUSINESS 为术语名、QA 为问题），
     * 拼接后 knn 向量检索可感知标题语义，与 BM25 的 title boost 对齐。
     * 仅影响向量生成，ES content 字段仍存原文（检索展示/重排输入不受影响）。
     */
    static String embedText(String title, String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (title == null || title.isBlank()) {
            return content;
        }
        return title + "\n" + content;
    }

    /** chunk 列表 → 向量化输入文本列表（顺序与入参一致），供批量 embedding 使用 */
    static List<String> embedTexts(List<Chunk> chunks) {
        return chunks.stream()
                .map(c -> embedText(c.getTitle(), c.getContent()))
                .toList();
    }
}
