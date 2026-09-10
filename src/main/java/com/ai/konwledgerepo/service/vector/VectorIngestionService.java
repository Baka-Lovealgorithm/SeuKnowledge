package com.ai.konwledgerepo.service.vector;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
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

    /** {@code kb_document.error_msg} 列宽 varchar(500) */
    private static final int ERROR_MSG_MAX = 500;

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
     * 异步向量化入口（精修确认后、文档列表「重建向量」后触发）：与解析流程一致，在独立线程（无外层事务）执行
     * {@link #ingest}，确保各 chunk 的 save 自行开事务并真实提交。
     * <p>
     * 背景：若在 {@code afterCommitExecutor.runAfterCommit} 回调内同步调用 ingest，
     * 回调时外层事务已提交但 {@code TransactionSynchronizationManager} 的资源尚未清理，
     * 后续 chunkRepository.save 会「加入」这个已提交事务而被静默丢弃（不抛异常，ES 已写、
     * 日志显示成功，但 MySQL esId 不回填）。@Async 换线程后无活动事务，修复该问题。
     * <p>
     * 为什么显式指定 {@code vectorTaskExecutor}：不带限定符时本方法落在通用 {@code applicationTaskExecutor}
     * （core 8），会和分钟级的抽取任务同池排队——此时文档已 {@code curateStatus=null + SUCCESS}、
     * 已离开精修队列，却还没有任何向量，用户看到的就是"确认完成之后一片成功但全都检索不到"。
     * 独立小池把这段不可见窗口的长度与抽取吞吐解耦。
     */
    @Async("vectorTaskExecutor")
    public void ingestAsync(Long docId) {
        ingest(docId);
    }

    /**
     * 为文档所有待向量化 chunk 生成向量并写入 ES。
     * 清洗 DEFER 决策：clean_status=SUSPECT 的 chunk 暂不向量化（人工审核通过后由
     * {@link #reindexChunk} 单条索引）；embedding 失败时不阻断（chunk 保持 EMBEDDING，可重试），文档置为 ERROR。
     * <p>
     * <b>每条出口都会回写文档级向量态</b>（{@link #applyVectorState}）：此前"模型未配置"与"ES 部分写入失败"
     * 只写日志，文档停在 SUCCESS、chunk 停在 EMBEDDING/FAILED，且无任何补偿任务——用户只能靠全量重解析自救。
     * 现在这两种情况都会落到 errorMsg 与列表的向量计数上，并可用「重建向量」原地修复。
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
            // 没有待向量化块：仍回写一次，清掉历史失败摘要（并把此前因向量化失败的 ERROR 复位）
            applyVectorState(doc);
            return;
        }
        EmbeddingModel embeddingModel;
        try {
            Long workspaceId = workspaceIdResolver.resolve(doc.getKbId());
            embeddingModel = modelFactory.getEmbeddingModelByUsage(ModelUsage.RETRIEVE.value(), workspaceId);
        } catch (Exception e) {
            log.warn("向量模型未配置，文档 {} 向量化暂缓: {}", docId, e.getMessage());
            noteVectorState(doc, "向量模型未配置，分块尚未向量化；配置好检索模型后点「重建向量」即可，无需重新解析");
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
            doc.setErrorMsg(truncate("向量化失败: " + e.getMessage()
                    + "（修好模型/网络后可点「重建向量」，无需重新解析）", ERROR_MSG_MAX));
            documentRepository.save(doc);
            return;
        }
        boolean allIndexed = indexChunksBulk(doc, chunks, vectors);
        // 收尾回写：bulk 的逐条失败只落在 chunk 上，这里把它汇总到文档，避免"SUCCESS 但召不回"
        applyVectorState(doc);
        log.info("文档 {} 向量化完成（{} 块，全部成功={}）", docId, chunks.size(), allIndexed);
    }

    /**
     * 向量化收尾：按 chunk 实际状态汇总，回写文档级可见信息。
     * <ul>
     *   <li>全部追平 → errorMsg 置空；若此前是本类写的 ERROR（向量失败），复位为 SUCCESS。</li>
     *   <li>仍有 pending/failed → 写摘要 errorMsg（含可用「重建向量」的指引），<b>不改 parseStatus</b>：
     *       解析确实成功了，状态机取值不新增，避免牵动 retry CAS 与抽取守卫的判定。</li>
     * </ul>
     */
    void applyVectorState(Document doc) {
        int indexed = 0;
        int pending = 0;
        int failed = 0;
        int awaitingReview = 0;
        for (Chunk c : chunkRepository.findByDocIdOrderBySeqAsc(doc.getId())) {
            boolean suspect = "SUSPECT".equals(c.getCleanStatus());
            if (suspect) {
                awaitingReview++;
            }
            if (ChunkStatus.FILTERED.is(c.getStatus()) || suspect) {
                continue; // 按设计不进向量库，不计入分母
            }
            if (ChunkStatus.INDEXED.is(c.getStatus())) {
                indexed++;
            } else if (ChunkStatus.FAILED.is(c.getStatus())) {
                failed++;
            } else if (ChunkStatus.EMBEDDING.is(c.getStatus())) {
                pending++;
            }
        }
        String summary = vectorStateSummary(indexed, pending, failed, awaitingReview);
        doc.setErrorMsg(summary);
        if (summary == null && DocStatus.ERROR.is(doc.getParseStatus())) {
            // ERROR 在本类之外无人写入（解析失败走 FAILED），故向量追平即可安全复位
            doc.setParseStatus(DocStatus.SUCCESS.value());
        }
        saveQuietly(doc, doc.getId());
    }

    /**
     * 收尾回写用 best-effort 保存：chunk 状态才是权威事实（列表向量计数与 ES 都按它走），
     * 文档摘要只是给人看的注脚。若因并发（如同时有人改初洗 md 触发 @Version 递增）而乐观锁失败，
     * 绝不能让异常冒泡出去——在自动链路上，parseAsync 的 catch 会把一次成功的解析误判成 FAILED。
     */
    private void saveQuietly(Document doc, Long docId) {
        try {
            documentRepository.save(doc);
        } catch (Exception e) {
            log.warn("文档 {} 向量态摘要回写失败（不影响 chunk 权威状态，可刷新列表后重试）: {}", docId, e.getMessage());
        }
    }

    /** 汇总 chunk 计数 → 文档级向量摘要；返回 null 表示完全健康（应清空 errorMsg）。包级纯函数，供单测。 */
    static String vectorStateSummary(int indexed, int pending, int failed, int awaitingReview) {
        if (pending == 0 && failed == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("向量未完成：已索引 ").append(indexed)
                .append('/').append(indexed + pending + failed);
        if (failed > 0) {
            sb.append("，失败 ").append(failed);
        }
        if (pending > 0) {
            sb.append("，待向量化 ").append(pending);
        }
        if (awaitingReview > 0) {
            sb.append("；另有 ").append(awaitingReview).append(" 块待人工审核");
        }
        sb.append("；可在文档列表点「重建向量」修复，无需重新解析");
        return sb.toString();
    }

    /** 直接给文档写一条向量态说明（向量化被短路跳过时用；不触碰 parseStatus） */
    private void noteVectorState(Document doc, String message) {
        doc.setErrorMsg(truncate(message, ERROR_MSG_MAX));
        documentRepository.save(doc);
    }

    /** errorMsg 列宽 varchar(500)：异常信息可能很长，截断避免落库时二次抛完整性异常 */
    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "…";
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

    /**
     * 按文档删除 ES 分块（删除/重试文档时调用）。
     * <p>
     * BUSINESS / QA 与 chunk 共用索引且也携带 docId，因此不能只按 docId 删除。
     * 这里仅匹配 CHUNK；同时匹配没有 sourceType 的旧版索引数据，以兼容既有知识库。
     * <p>
     * ⚠️ {@code catch (IOException)} 是有意的窄口径，<b>别顺手"统一"成 catch (Exception)</b>：
     * ES 返回错误码时抛的 {@code ElasticsearchException} 是 RuntimeException，让它冒泡是有意的——
     * 删不干净等于向量残留，而召回侧只读 ES {@code _source}（{@code VectorSearchService.rrfMerge}
     * 不回查 MySQL），残留会被当作有效证据引用已删除的文档内容。宁可报接口错误，也不要静默出错答案。
     * 已知遗留：连接类失败（IOException）仍然只告警，同样可能残留幽灵向量，目前无逐文档补偿入口，
     * 兜底可用「清空知识库内容」（按 kbId 全来源删除）。
     */
    public void deleteByDocId(Long docId) {
        try {
            esClient.deleteByQuery(deleteByDocIdRequest(indexName, docId));
        } catch (IOException e) {
            log.error("删除 ES chunk 失败 docId={}", docId, e);
        }
    }

    /**
     * 按文档删除 ES chunk 的请求（纯函数，供单测钉住口径）。
     * <p>
     * 为什么单独抽出：{@code ElasticsearchClient} 的父类非 public，Mockito 无法 mock，
     * 直接调 {@code esClient.deleteByQuery(fn)} 的请求内容在单测里看不见，
     * 抽出后 {@code conflicts} 这类参数才有回归锁。
     * <p>
     * {@code conflicts=Proceed}：同文件两处 updateByQuery 都设了，删除原本漏设——并发向量化时
     * delete_by_query 会以 409 整体失败（而删除本就该幂等）。
     */
    static DeleteByQueryRequest deleteByDocIdRequest(String indexName, Long docId) {
        return DeleteByQueryRequest.of(b -> b.index(indexName)
                .conflicts(Conflicts.Proceed)
                .query(documentChunkQuery(docId)));
    }

    /**
     * 文档删除后解除 ES 中业务知识/问答对的来源 id，但保留 docName 和向量内容。
     * docId=0 与 {@link ChunkDocFields#document} 对空来源的既有映射一致，无需重建索引或重新 embedding。
     * ES 暂时不可用时仅告警：MySQL 已解除关联，结构化知识本身仍保留；后续编辑/审核会按新状态覆盖索引。
     */
    public void detachSourceDocumentInIndex(Long docId) {
        try {
            esClient.updateByQuery(u -> u
                    .index(indexName)
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .query(structuredSourceQuery(docId))
                    .script(s -> s.source("ctx._source." + ChunkDocFields.DOC_ID + " = 0")));
            log.info("已解除 ES 结构化知识的来源文档关联：docId={}", docId);
        } catch (Exception e) {
            log.warn("解除 ES 结构化知识来源关联失败 docId={}（MySQL 已处理，知识与向量均保留）: {}",
                    docId, e.getMessage());
        }
    }

    static Query documentChunkQuery(Long docId) {
        return Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field(ChunkDocFields.DOC_ID).value(docId)))
                .filter(f -> f.bool(types -> types
                        .should(s -> s.term(t -> t.field(ChunkDocFields.SOURCE_TYPE)
                                .value(SourceType.CHUNK.value())))
                        .should(s -> s.bool(legacy -> legacy
                                .mustNot(n -> n.exists(e -> e.field(ChunkDocFields.SOURCE_TYPE)))))
                        .minimumShouldMatch("1")))));
    }

    static Query structuredSourceQuery(Long docId) {
        return Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field(ChunkDocFields.DOC_ID).value(docId)))
                .filter(f -> f.bool(types -> types
                        .should(s -> s.term(t -> t.field(ChunkDocFields.SOURCE_TYPE)
                                .value(SourceType.BUSINESS.value())))
                        .should(s -> s.term(t -> t.field(ChunkDocFields.SOURCE_TYPE)
                                .value(SourceType.QA.value())))
                        .minimumShouldMatch("1")))));
    }

    /**
     * 按知识库删除全部来源的 ES 文档（CHUNK / BUSINESS / QA，清空知识库内容时调用）。
     * catch 窄口径的理由见 {@link #deleteByDocId}。
     */
    public void deleteByKbId(Long kbId) {
        try {
            esClient.deleteByQuery(deleteByKbIdRequest(indexName, kbId));
            log.info("删除 ES 知识库 {} 全部来源文档完成", kbId);
        } catch (IOException e) {
            log.error("删除 ES 知识库 {} 文档失败", kbId, e);
        }
    }

    /** 见 {@link #deleteByDocIdRequest}：同样补 {@code conflicts=Proceed}，否则并发写入下清空会被 409 打断 */
    static DeleteByQueryRequest deleteByKbIdRequest(String indexName, Long kbId) {
        return DeleteByQueryRequest.of(b -> b.index(indexName)
                .conflicts(Conflicts.Proceed)
                .query(q -> q.term(t -> t.field(ChunkDocFields.KB_ID).value(kbId))));
    }

    /**
     * 文档改名后同步 ES 内的冗余引用名（按 docId 命中，chunk / 业务知识 / 问答对一次改全）。
     * <p>
     * 为什么必须同步：{@code docName} 参与 BM25 打分（{@code docName^0.5}）与 rerank 输入，
     * 也是问答证据引用的展示名；不同步会出现"列表已改名、答案还在引用旧名"。
     * 用 update_by_query + painless 只改这一个字段，避免为此重新 embedding（真金白银的模型调用）。
     * <p>
     * 失败仅告警不抛出：MySQL 侧文件名是权威且已提交，改名不该因 ES 抖动回滚；
     * 后续「重建向量」或再次改名会重新覆盖该字段。历史会话的引用快照（kb_chat_message.refs）不回改——
     * 那是当时答案的取证记录。
     */
    public void renameInIndex(Long docId, String newDocName) {
        try {
            esClient.updateByQuery(u -> u
                    .index(indexName)
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .query(q -> q.term(t -> t.field(ChunkDocFields.DOC_ID).value(docId)))
                    .script(s -> s
                            .source("ctx._source." + ChunkDocFields.DOC_NAME + " = params.newName")
                            .params("newName", JsonData.of(newDocName))));
            log.info("已同步 ES 引用名：docId={} → {}", docId, newDocName);
        } catch (Exception e) {
            log.warn("同步 ES 引用名失败 docId={}（MySQL 已改名，可稍后「重建向量」或再次改名覆盖）: {}",
                    docId, e.getMessage());
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
                BulkResponse response = callBulk(bulk.build());
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
            } catch (Exception e) {
                // 必须宽到 Exception：ES "连不上"是 IOException（走这里），但 ES 返回错误码
                // （429/503/映射冲突 400 等）抛的是 ElasticsearchException（RuntimeException）。
                // 少兜住这一类，异常会冒出 ingest → @Async ingestAsync 只落一行日志，
                // 连本方法 javadoc 承诺的"每条出口都回写文档级向量态"都被跳过：
                // 文档 SUCCESS、errorMsg 空、chunk 永停 EMBEDDING、列表连"向量未完成"都不显示。
                // 这里当作整批失败处理，用户仍可用「重建向量」原地补救。
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
     * ES bulk 调用点。单独成方法只为一处可测：{@code ElasticsearchClient} 的父类非 public，
     * Mockito 无法 mock，本类的 ES 错误分支以前完全写不出回归用例（传 null client 就直接 NPE）。
     * 测试覆盖本方法即可分别注入 IOException（连不上）与 ElasticsearchException（错误码）两类失败。
     */
    BulkResponse callBulk(BulkRequest request) throws IOException {
        return esClient.bulk(request);
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
