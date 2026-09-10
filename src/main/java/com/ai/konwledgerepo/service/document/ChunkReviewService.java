package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.ChunkReviewResponse;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkReviewLog;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.ChunkReviewLogRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 文档精修：对 P1 规则清洗打标的待审核（SUSPECT）chunk 提供
 * 保留/编辑/删除/回退待审核/批量操作。
 * <p>
 * 术语（全项目统一，勿再引入旧名）：
 * <b>初洗</b>=解析后人工确认分段（文档级，PREVIEWING，可编辑整篇 md 重分块）；
 * <b>精修</b>=初洗接受后按文件复核 chunk（文档级，ACCEPTED，可编辑/删除/合并/保留）；
 * 本类处理的是<b>精修</b>动作，也是普通文档（未经初洗门）SUSPECT 分块复核的统一入口。
 * 旧称「清洗人工审核 / 清洗复核 / 人工策展」均已废弃。
 * <p>
 * DEFER 决策：SUSPECT chunk 审核前不进 ES（VectorIngestionService.ingest 已跳过）；
 * 审核通过（keep/edit）后由本服务触发单 chunk 向量化（{@link VectorIngestionService#reindexChunk}）。
 * 已审核回退待审核（unkeep）时若已进 ES 则移出（恢复 DEFER）。
 * 所有动作写入 {@link ChunkReviewLog} 审计（编辑前后内容留痕）。
 * <p>
 * 红线：只允许改 content/title，pageNum/docId/seq/chunkId 不可变（证据展示溯源稳定）。
 */
@Service
public class ChunkReviewService {

    private static final Logger log = LoggerFactory.getLogger(ChunkReviewService.class);

    public static final String CLEAN_SUSPECT = "SUSPECT";
    public static final String CLEAN_KEEP = "KEEP";
    public static final String CLEAN_FILTERED = "FILTERED";

    /** 审计日志留痕内容的最大长度（超长截断，避免单条日志行过大） */
    private static final int AUDIT_CONTENT_MAX = 10000;

    /**
     * 该 chunk 是否处于「已审核」状态、可被 {@link #unkeep} 回退为待审核。
     * <p>
     * 判据：clean_status 既非 SUSPECT（刚被打标、仍待人工决断）也非 FILTERED（已丢弃、无回退意义），
     * 即 KEEP 或 null。**null 视为已审核是刻意的**：规则清洗只给命中的 chunk 打标，
     * 未命中的正常块 clean_status 为 null，本就不需要人工介入。
     * <p>
     * 注意与 {@code DocumentCurateService.confirm} 的区别：confirm 只拦 SUSPECT，
     * 因为 FILTERED 在（已丢弃）语义上不影响向量化落地，而回退动作对 FILTERED 无意义。
     * 两处<b>都不得</b>表述为"仅 KEEP 才算已审核"——那会让读到注释的人给正常块加上多余的 KEEP 强校验。
     */
    public static boolean isReviewed(String cleanStatus) {
        return !CLEAN_SUSPECT.equals(cleanStatus) && !CLEAN_FILTERED.equals(cleanStatus);
    }

    private final ChunkRepository chunkRepository;
    private final ChunkReviewLogRepository reviewLogRepository;
    private final DocumentRepository documentRepository;
    private final VectorIngestionService vectorIngestionService;

    public ChunkReviewService(ChunkRepository chunkRepository,
                              ChunkReviewLogRepository reviewLogRepository,
                              DocumentRepository documentRepository,
                              VectorIngestionService vectorIngestionService) {
        this.chunkRepository = chunkRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.documentRepository = documentRepository;
        this.vectorIngestionService = vectorIngestionService;
    }

    /**
     * 知识库级 SUSPECT 审核队列（跨文档聚合，按 docId+seq 排序）。
     * 队列仅含 clean_status=SUSPECT 的 chunk（DEFER 下均未向量化）。
     */
    public List<ChunkReviewResponse> suspectQueue(Long kbId) {
        List<Chunk> chunks = chunkRepository.findByKbIdAndCleanStatusOrderByDocIdAscSeqAsc(kbId, CLEAN_SUSPECT);
        if (chunks.isEmpty()) {
            return List.of();
        }
        Set<Long> docIds = chunks.stream().map(Chunk::getDocId).collect(Collectors.toSet());
        Map<Long, String> docNames = documentRepository.findAllById(docIds).stream()
                .collect(Collectors.toMap(Document::getId, Document::getFileName));
        return chunks.stream()
                .map(c -> ChunkReviewResponse.of(c, docNames.getOrDefault(c.getDocId(), "")))
                .toList();
    }

    /**
     * 保留：clean_status SUSPECT→KEEP 并触发单 chunk 向量化（DEFER 后首次进 ES）。
     * 非 SUSPECT chunk 幂等返回（已处理过，不重复操作）。
     * 初洗/精修流程中的文档（PREVIEWING/ACCEPTED）拒绝在此处理（须在精修页按文件处理，确认前不触 ES）。
     */
    @Transactional
    public ChunkReviewResponse keep(Long chunkId, Long userId) {
        Chunk chunk = requireChunk(chunkId);
        requireNotCurating(chunk.getDocId());
        if (!CLEAN_SUSPECT.equals(chunk.getCleanStatus())) {
            log.info("chunk {} 非 SUSPECT（cleanStatus={}），保留动作跳过", chunkId, chunk.getCleanStatus());
            return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
        }
        chunk.setCleanStatus(CLEAN_KEEP);
        chunkRepository.save(chunk);
        vectorIngestionService.reindexChunk(chunk);
        recordLog(chunk, "keep", null, null, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /**
     * 编辑并保留：更新 content/title，clean_status→KEEP 并重新向量化（只做一次，无"先进后改"浪费）。
     * 编辑后内容为空 → 拒绝（防止产生不可检索的废块）。
     */
    @Transactional
    public ChunkReviewResponse edit(Long chunkId, String content, String title, Long userId) {
        Chunk chunk = requireChunk(chunkId);
        requireNotCurating(chunk.getDocId());
        if (content == null || content.trim().isEmpty()) {
            throw new BizException("编辑后内容不能为空");
        }
        if (!CLEAN_SUSPECT.equals(chunk.getCleanStatus())) {
            throw new BizException("仅 SUSPECT 状态可编辑（当前 " + chunk.getCleanStatus() + "）");
        }
        String before = chunk.getContent();
        chunk.setContent(content);
        chunk.setTitle(title);
        chunk.setCleanStatus(CLEAN_KEEP);
        chunkRepository.save(chunk);
        vectorIngestionService.reindexChunk(chunk);
        recordLog(chunk, "edit", before, content, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /**
     * 删除：clean_status/status→FILTERED 并从 ES 移除（记录保留在 MySQL，供"查看全文"历史证据兜底）。
     * 非 SUSPECT chunk 幂等返回。
     */
    @Transactional
    public ChunkReviewResponse drop(Long chunkId, Long userId) {
        Chunk chunk = requireChunk(chunkId);
        requireNotCurating(chunk.getDocId());
        if (!CLEAN_SUSPECT.equals(chunk.getCleanStatus())) {
            log.info("chunk {} 非 SUSPECT（cleanStatus={}），删除动作跳过", chunkId, chunk.getCleanStatus());
            return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
        }
        String before = chunk.getContent();
        chunk.setCleanStatus(CLEAN_FILTERED);
        chunk.setStatus(ChunkStatus.FILTERED.value());
        chunkRepository.save(chunk);
        vectorIngestionService.deleteByChunkId(chunkId);
        recordLog(chunk, "drop", before, null, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /**
     * 已审核回退待审核：clean_status（KEEP 或正常 null）→SUSPECT，并移出 ES（恢复 DEFER：
     * SUSPECT 不进向量库，需重新保留才会再次向量化）。
     * 仅已审核分块可回退（判据见 {@link #isReviewed}，KEEP 与 null 都算已审核）；
     * 初洗/精修流程中的文档拒绝在此处理。
     */
    @Transactional
    public ChunkReviewResponse unkeep(Long chunkId, Long userId) {
        Chunk chunk = requireChunk(chunkId);
        requireNotCurating(chunk.getDocId());
        if (!isReviewed(chunk.getCleanStatus())) {
            throw new BizException("仅已审核（KEEP 或正常分块）可回退待审核（当前 " + chunk.getCleanStatus() + "）");
        }
        String before = chunk.getCleanStatus(); // 留痕真实前值：可能是 KEEP，也可能是 null
        chunk.setCleanStatus(CLEAN_SUSPECT);
        chunkRepository.save(chunk);
        if (chunk.getEsId() != null) {
            vectorIngestionService.deleteByChunkId(chunkId);
        }
        recordLog(chunk, "unkeep", before, CLEAN_SUSPECT, userId);
        return ChunkReviewResponse.of(chunk, docName(chunk.getDocId()));
    }

    /**
     * 批量审核：逐条 keep/drop，返回每条的结果（部分失败不整体回滚，失败项 reason 说明）。
     * 编辑类批量操作不在此接口（编辑需逐条提供内容，走单个 edit）。
     */
    @Transactional
    public List<BatchItemResult> batch(List<Long> ids, String action, Long userId) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (!"keep".equals(action) && !"drop".equals(action)) {
            throw new BizException("批量动作仅支持 keep / drop");
        }
        List<BatchItemResult> results = new ArrayList<>(ids.size());
        for (Long id : ids) {
            try {
                Chunk chunk = requireChunk(id);
                ChunkReviewResponse resp = "keep".equals(action) ? keep(id, userId) : drop(id, userId);
                results.add(new BatchItemResult(id, true, resp.cleanReason()));
            } catch (Exception e) {
                log.warn("批量 {} chunk {} 失败: {}", action, id, e.getMessage());
                results.add(new BatchItemResult(id, false, e.getMessage()));
            }
        }
        return results;
    }

    /** 批量动作单项结果 */
    public record BatchItemResult(Long chunkId, boolean success, String reason) {
    }

    private Chunk requireChunk(Long chunkId) {
        return chunkRepository.findById(chunkId)
                .orElseThrow(() -> new BizException("chunk 不存在: " + chunkId));
    }

    /** 初洗/精修流程中的文档（PREVIEWING/ACCEPTED）拒绝在常规精修接口处理（须走精修页，确认前不触 ES） */
    private void requireNotCurating(Long docId) {
        Document doc = documentRepository.findById(docId).orElse(null);
        if (doc != null && DocumentCurateService.isCurating(doc)) {
            throw new BizException("该文档处于初洗/精修流程（" + doc.getCurateStatus()
                    + "），请在精修页处理；确认向量化后可在此复核剩余待审核分块");
        }
    }

    private String docName(Long docId) {
        return documentRepository.findById(docId).map(Document::getFileName).orElse("");
    }

    /** 审计留痕：动作 + 编辑前后内容（TEXT 截断防超大字段；keep/drop 传对应内容） */
    private void recordLog(Chunk chunk, String action, String before, String after, Long userId) {
        try {
            ChunkReviewLog logEntry = new ChunkReviewLog();
            logEntry.setChunkId(chunk.getId());
            logEntry.setDocId(chunk.getDocId());
            logEntry.setAction(action);
            logEntry.setBeforeContent(Texts.truncateOrNull(before, AUDIT_CONTENT_MAX));
            logEntry.setAfterContent(Texts.truncateOrNull(after, AUDIT_CONTENT_MAX));
            logEntry.setUserId(userId);
            reviewLogRepository.save(logEntry);
        } catch (Exception e) {
            log.warn("审核审计写入失败（不影响主操作）chunk={} action={}: {}", chunk.getId(), action, e.getMessage());
        }
    }

    private static String truncate(String s) {
        return Texts.truncateOrNull(s, AUDIT_CONTENT_MAX);
    }
}
