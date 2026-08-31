package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文档解析的事务边界组件（独立 bean，@Transactional 代理生效）。
 * 解析 worker 在长文本解析（无事务）的前后经此 bean 衔接状态迁移，
 * 保证「状态机 + chunk 批量插入」在同一事务内完成，且利用悲观锁
 * （SELECT ... FOR UPDATE）与 delete/retry 的操作串行化。
 * <p>
 * 使用方：{@link DocumentParseExecutor}（@Async 方法自身不挂事务）。
 */
@Component
public class DocumentParseTx {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseTx.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;

    public DocumentParseTx(DocumentRepository documentRepository, ChunkRepository chunkRepository) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    /**
     * 解析启动守卫：从 PENDING 迁入 PARSING。
     * 对文档行加悲观锁（FOR UPDATE），行不存在或状态非 PENDING → 返回 empty。
     * 返回实体为 detached 状态，字段齐全，可供解析器使用（无需二次查询）。
     */
    @Transactional
    public Optional<Document> startParse(Long docId) {
        Document doc = documentRepository.findByIdForUpdate(docId).orElse(null);
        if (doc == null) {
            log.debug("startParse 跳过：文档 {} 不存在", docId);
            return Optional.empty();
        }
        if (!DocStatus.PENDING.is(doc.getParseStatus())) {
            log.debug("startParse 跳过：文档 {} 状态={}（非 PENDING）", docId, doc.getParseStatus());
            return Optional.empty();
        }
        doc.setParseStatus(DocStatus.PARSING.value());
        documentRepository.save(doc);
        log.debug("startParse 成功：文档 {} 进入 PARSING", docId);
        return Optional.of(doc);
    }

    /**
     * 解析成功收尾：事务内重读文档（悲观锁），存在则批量写入 chunk 并置 SUCCESS。
     * 返回 true 表示已落库成功（调用方可继续触发向量化）；false 表示文档已被删除
     * 或不存在（静默中止，不产生孤儿 chunk）。
     * <p>
     * 旧签名（无清洗）：全部 chunk 正常落库（EMBEDDING）。
     */
    @Transactional
    public boolean finalizeSuccess(Long docId, List<ChunkPiece> pieces) {
        return finalizeSuccess(docId, pieces, List.of());
    }

    /**
     * 解析成功收尾（含 P1 清洗结果）：
     * <ul>
     *   <li>kept 中的 chunk → status=EMBEDDING（其中 SUSPECT 判定项附加 clean_status=SUSPECT + clean_reason，照常向量化）；</li>
     *   <li>outcomes 中 AUTO-DROP 的 chunk → status=FILTERED + clean_status=FILTERED + clean_reason（记录保留但永不向量化）。</li>
     * </ul>
     */
    @Transactional
    public boolean finalizeSuccess(Long docId, List<ChunkPiece> kept, List<DocumentCleanService.CleanOutcome> outcomes) {
        Document doc = documentRepository.findByIdForUpdate(docId).orElse(null);
        if (doc == null) {
            log.info("finalizeSuccess 中止：文档 {} 已被删除", docId);
            return false;
        }
        // 引用相等匹配（ChunkPiece 是 record，kept 与 outcomes 持有同一对象引用）
        java.util.Map<ChunkPiece, DocumentCleanService.CleanOutcome> outcomeByPiece =
                new java.util.IdentityHashMap<>();
        for (DocumentCleanService.CleanOutcome o : outcomes) {
            outcomeByPiece.put(o.piece(), o);
        }
        List<Chunk> chunks = new ArrayList<>(kept.size() + outcomes.size());
        int seq = 1;
        for (ChunkPiece piece : kept) {
            Chunk chunk = new Chunk();
            chunk.setDocId(docId);
            chunk.setKbId(doc.getKbId());
            chunk.setSeq(seq++);
            chunk.setContent(piece.content());
            chunk.setPageNum(piece.pageNum());
            chunk.setTitle(piece.title());
            chunk.setStatus(com.ai.konwledgerepo.entity.ChunkStatus.EMBEDDING.value());
            DocumentCleanService.CleanOutcome o = outcomeByPiece.get(piece);
            if (o != null && o.disposition() == DocumentCleanService.Disposition.SUSPECT) {
                chunk.setCleanStatus("SUSPECT");
                chunk.setCleanReason(o.reason());
            }
            chunks.add(chunk);
        }
        for (DocumentCleanService.CleanOutcome o : outcomes) {
            if (o.disposition() != DocumentCleanService.Disposition.AUTO_DROP) {
                continue; // SUSPECT/KEEP 已随 kept 落库
            }
            ChunkPiece piece = o.piece();
            Chunk chunk = new Chunk();
            chunk.setDocId(docId);
            chunk.setKbId(doc.getKbId());
            chunk.setSeq(seq++);
            chunk.setContent(piece.content());
            chunk.setPageNum(piece.pageNum());
            chunk.setTitle(piece.title());
            chunk.setStatus(com.ai.konwledgerepo.entity.ChunkStatus.FILTERED.value());
            chunk.setCleanStatus("FILTERED");
            chunk.setCleanReason(o.reason());
            chunks.add(chunk);
        }
        chunkRepository.saveAll(chunks);
        doc.setParseStatus(DocStatus.SUCCESS.value());
        doc.setChunkCount(chunks.size());
        doc.setErrorMsg(null);
        documentRepository.save(doc);
        log.info("finalizeSuccess：文档 {} 解析完成，{} 个 chunk 已落库（AUTO-DROP {}）",
                docId, chunks.size(),
                outcomes.stream().filter(o -> o.disposition() == DocumentCleanService.Disposition.AUTO_DROP).count());
        return true;
    }

    /**
     * 解析失败收尾：事务内重读文档（悲观锁），存在则置 FAILED。
     * 行不存在时静默返回（文档已被删除）。
     */
    @Transactional
    public void finalizeFailure(Long docId, String error) {
        Document doc = documentRepository.findByIdForUpdate(docId).orElse(null);
        if (doc == null) {
            log.debug("finalizeFailure 跳过：文档 {} 已被删除", docId);
            return;
        }
        doc.setParseStatus(DocStatus.FAILED.value());
        String truncated = com.ai.konwledgerepo.common.Texts.truncate(error, 500);
        doc.setErrorMsg(truncated);
        documentRepository.save(doc);
        log.warn("finalizeFailure：文档 {} 解析失败，原因={}", docId, truncated);
    }
}