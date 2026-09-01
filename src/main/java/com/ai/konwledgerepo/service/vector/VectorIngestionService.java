package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.ai.konwledgerepo.config.props.SeuEsProperties;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.entity.ModelUsage;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量化与 ES 写入服务：文档 chunk、业务知识、问答对统一写入 ES（kb_chunk 索引），
 * 通过 sourceType 字段区分来源（CHUNK / BUSINESS / QA）。
 * chunk 文档 _id 使用 chunk id；业务知识/问答对 _id 使用 "sourceType-entityId"。
 */
@Service
public class VectorIngestionService {

    private static final Logger log = LoggerFactory.getLogger(VectorIngestionService.class);

    /** ES bulk 批量写入每批 chunk 数：大文档几百 chunk 时一次提交一批（每条约 1MB，100 条远低于 ES 默认 100MB 请求上限） */
    private static final int BULK_BATCH_SIZE = 100;

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
     * 异步向量化入口（精修确认后触发）：与解析流程一致，在独立线程（无外层事务）执行
     * {@link #ingest}，确保各 chunk 的 save 自行开事务并真实提交。
     * <p>
     * 背景：若在 {@code afterCommitExecutor.runAfterCommit} 回调内同步调用 ingest，
     * 回调时外层事务已提交但 {@code TransactionSynchronizationManager} 的资源尚未清理，
     * 后续 chunkRepository.save 会「加入」这个已提交事务而被静默丢弃（不抛异常，ES 已写、
     * 日志显示成功，但 MySQL esId 不回填）。@Async 换线程后无活动事务，修复该问题。
     */
    @Async
    public void ingestAsync(Long docId) {
        ingest(docId);
    }

    /**
     * 为文档所有待向量化 chunk 生成向量并写入 ES。
     * 清洗 DEFER 决策：clean_status=SUSPECT 的 chunk 暂不向量化（人工审核通过后由
     * {@link #reindexChunk} 单条索引）；embedding 失败时不阻断（chunk 保持 EMBEDDING，可重试），文档置为 ERROR。
     */
    public void ingest(Long docId) {
        Document doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        List<Chunk> chunks = chunkRepository.findByDocIdOrderBySeqAsc(docId).stream()
                .filter(c -> ChunkStatus.EMBEDDING.is(c.getStatus()))
                .filter(c -> !"SUSPECT".equals(c.getCleanStatus()))
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
        boolean allIndexed = indexChunksBulk(doc, chunks, vectors);
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

    /**
     * 批量写入 ES（BulkRequest）：按 {@link #BULK_BATCH_SIZE} 分块一次提交多条，替代逐条 index
     * （大文档几百 chunk 时从几百次 HTTP 往返降到几次）。逐 item 校验结果：
     * 失败（error 非空或 status≥300）的 chunk 置 FAILED，其余置 INDEXED 并回填 esId；
     * 整批 IOException 时该批全部置 FAILED。结束后统一 saveAll 落库。
     *
     * @return 是否全部成功（false 表示存在失败 chunk，不影响文档状态，与旧逐条语义一致）
     */
    private boolean indexChunksBulk(Document doc, List<Chunk> chunks, List<float[]> vectors) {
        boolean allIndexed = true;
        List<List<Chunk>> batches = partitionBySize(chunks, BULK_BATCH_SIZE);
        int offset = 0;
        for (List<Chunk> batch : batches) {
            BulkRequest.Builder bulk = new BulkRequest.Builder();
            for (int i = 0; i < batch.size(); i++) {
                Chunk chunk = batch.get(i);
                float[] vector = vectors.get(offset + i);
                bulk.operations(op -> op.index(idx -> idx
                        .index(indexName)
                        .id(String.valueOf(chunk.getId()))
                        .document(ChunkDocFields.chunkDocument(
                                chunk.getId(), chunk.getDocId(), chunk.getKbId(), doc.getFileName(),
                                chunk.getPageNum() == null ? 0 : chunk.getPageNum(),
                                chunk.getTitle() == null ? "" : chunk.getTitle(),
                                chunk.getContent(), vector, chunk.getCleanStatus()))));
            }
            try {
                BulkResponse response = esClient.bulk(bulk.build());
                List<BulkResponseItem> items = response.items();
                for (int i = 0; i < batch.size(); i++) {
                    Chunk chunk = batch.get(i);
                    BulkResponseItem item = items.get(i);
                    if (item.error() != null || item.status() >= 300) {
                        log.error("chunk {} 写入 ES 失败（bulk item status={}）", chunk.getId(), item.status());
                        chunk.setStatus(ChunkStatus.FAILED.value());
                        allIndexed = false;
                    } else {
                        chunk.setEsId(String.valueOf(chunk.getId()));
                        chunk.setStatus(ChunkStatus.INDEXED.value());
                    }
                }
            } catch (IOException e) {
                log.error("chunk 批量写入 ES 失败（批 {} 条）", batch.size(), e);
                for (Chunk chunk : batch) {
                    chunk.setStatus(ChunkStatus.FAILED.value());
                }
                allIndexed = false;
            }
            offset += batch.size();
        }
        chunkRepository.saveAll(chunks);
        return allIndexed;
    }

    /**
     * 按 batchSize 将列表分块（末块可能不足），返回子列表视图集合，保持元素顺序与引用。
     * package-private 供单元测试验证分块边界。
     */
    static List<List<Chunk>> partitionBySize(List<Chunk> chunks, int batchSize) {
        List<List<Chunk>> batches = new ArrayList<>();
        for (int from = 0; from < chunks.size(); from += batchSize) {
            batches.add(chunks.subList(from, Math.min(from + batchSize, chunks.size())));
        }
        return batches;
    }

    private String indexChunk(Document doc, Chunk chunk, float[] vector) throws IOException {
        IndexResponse response = esClient.index(i -> i
                .index(indexName)
                .id(String.valueOf(chunk.getId()))
                .document(ChunkDocFields.chunkDocument(
                        chunk.getId(), chunk.getDocId(), chunk.getKbId(), doc.getFileName(),
                        chunk.getPageNum() == null ? 0 : chunk.getPageNum(),
                        chunk.getTitle() == null ? "" : chunk.getTitle(),
                        chunk.getContent(), vector, chunk.getCleanStatus())));
        return response.id();
    }

    /**
     * 单 chunk 向量化（人工审核触发）：重新 embedding 并 upsert ES，回填 esId 且 status→INDEXED。
     * 用于 DEFER 决策下 SUSPECT chunk 审核通过（keep/edit）后的索引，以及人工误删后的恢复。
     * embedding 失败时抛 BizException（chunk 保持原状态，可由调用方决定是否重试）。
     */
    public void reindexChunk(Chunk chunk) {
        if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
            throw new IllegalArgumentException("chunk 内容为空，无法向量化");
        }
        Document doc = documentRepository.findById(chunk.getDocId()).orElse(null);
        if (doc == null) {
            throw new IllegalStateException("文档不存在 docId=" + chunk.getDocId());
        }
        try {
            Long workspaceId = workspaceIdResolver.resolve(chunk.getKbId());
            EmbeddingModel embeddingModel = modelFactory.getEmbeddingModelByUsage(ModelUsage.RETRIEVE.value(), workspaceId);
            float[] vector = embeddingModel.embed(embedText(chunk.getTitle(), chunk.getContent()));
            String esId = indexChunk(doc, chunk, vector);
            chunk.setEsId(esId);
            chunk.setStatus(ChunkStatus.INDEXED.value());
            chunkRepository.save(chunk);
            log.info("chunk {} 已单条向量化（cleanStatus={}）", chunk.getId(), chunk.getCleanStatus());
        } catch (Exception e) {
            log.error("chunk {} 单条向量化失败: {}", chunk.getId(), e.getMessage());
            throw new com.ai.konwledgerepo.common.BizException("向量化失败: " + e.getMessage());
        }
    }

    /** 按 chunkId 删除 ES 文档（人工审核 drop / 误删恢复前调用）；不存在时静默容错 */
    public void deleteByChunkId(Long chunkId) {
        try {
            esClient.delete(d -> d.index(indexName).id(String.valueOf(chunkId)));
        } catch (Exception e) {
            log.warn("删除 ES chunk {} 失败（容错）: {}", chunkId, e.getMessage());
        }
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
