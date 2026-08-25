package com.ai.konwledgerepo.service.document;

import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.DocStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.vector.VectorIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档异步解析执行器：提取文本 → 分块 → 写入 MySQL chunk → 向量化入 ES。
 * <p>
 * 独立 bean 承载 {@code @Async}，避免从 {@link DocumentService} 同 bean 内直接调用导致
 * 代理失效（自调用不生效）——修复"上传接口同步阻塞到解析完成、事务长时间不提交"问题。
 */
@Service
public class DocumentParseExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseExecutor.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentParserService parserService;
    private final VectorIngestionService vectorIngestionService;

    public DocumentParseExecutor(DocumentRepository documentRepository,
                                 ChunkRepository chunkRepository,
                                 DocumentParserService parserService,
                                 VectorIngestionService vectorIngestionService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.parserService = parserService;
        this.vectorIngestionService = vectorIngestionService;
    }

    /**
     * 异步解析：提取文本 → 分块 → 写入 MySQL chunk。
     * 由 DocumentService 在事务提交后触发，确保本线程能读到已提交的文档数据。
     */
    @Async
    public void parseAsync(Long docId) {
        Document doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            return;
        }
        doc.setParseStatus("PARSING");
        documentRepository.save(doc);
        try {
            List<ChunkPiece> pieces = parserService.parse(doc);
            int seq = 1;
            for (ChunkPiece piece : pieces) {
                Chunk chunk = new Chunk();
                chunk.setDocId(doc.getId());
                chunk.setKbId(doc.getKbId());
                chunk.setSeq(seq++);
                chunk.setContent(piece.content());
                chunk.setPageNum(piece.pageNum());
                chunk.setTitle(piece.title());
                chunkRepository.save(chunk);
            }
            doc.setParseStatus(DocStatus.SUCCESS.value());
            doc.setChunkCount(pieces.size());
            doc.setErrorMsg(null);
            documentRepository.save(doc);
            log.info("文档 {} 解析完成，分块 {} 个", doc.getFileName(), pieces.size());
            // 向量化写入 ES（依赖已配置的向量模型；未配置时保持 EMBEDDING 待后续接入）
            vectorIngestionService.ingest(docId);
        } catch (Exception e) {
            log.error("文档 {} 解析失败", doc.getFileName(), e);
            doc.setParseStatus(DocStatus.FAILED.value());
            doc.setErrorMsg(e.getMessage() == null ? null : Texts.truncate(e.getMessage(), 500));
            documentRepository.save(doc);
        }
    }
}
