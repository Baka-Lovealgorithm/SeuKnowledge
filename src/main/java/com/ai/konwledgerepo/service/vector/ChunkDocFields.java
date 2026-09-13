package com.ai.konwledgerepo.service.vector;

import com.ai.konwledgerepo.entity.SourceType;

import java.util.Map;

/**
 * ES chunk 索引字段契约（单一来源）。
 * 收敛 VectorIndexService（mapping）、VectorIngestionService（写入）、VectorSearchService（读取）三处的字段名。
 */
public final class ChunkDocFields {

    private ChunkDocFields() {
    }

    public static final String KB_ID = "kbId";
    public static final String DOC_ID = "docId";
    public static final String CHUNK_ID = "chunkId";
    public static final String DOC_NAME = "docName";
    public static final String PAGE_NUM = "pageNum";
    public static final String TITLE = "title";
    public static final String SOURCE_TYPE = "sourceType";
    public static final String CONTENT = "content";
    public static final String CONTENT_VECTOR = "contentVector";
    /** 清洗状态（P1 规则清洗）：null=未清洗 / KEEP=保留 / SUSPECT=可疑待人工 / FILTERED=丢弃 */
    public static final String CLEAN_STATUS = "cleanStatus";

    /** title 的可分词 multi-field 子字段（smartcn）：顶层 keyword 全串精确匹配对自然语言问句无效，
     * BM25 标题召回打在此子字段上。旧索引由 VectorIndexService 启动时原地补充。 */
    public static final String TITLE_TEXT = TITLE + ".text";
    /** docName 的可分词 multi-field 子字段（smartcn），同 {@link #TITLE_TEXT} */
    public static final String DOC_NAME_TEXT = DOC_NAME + ".text";

    /** 构建一份写入 ES 的文档（chunk 与结构化来源共用，sourceType 区分） */
    public static Map<String, Object> document(Long chunkId, Long docId, Long kbId, String docName,
                                               int pageNum, String title, String sourceType,
                                               String content, float[] contentVector) {
        return Map.of(
                CHUNK_ID, chunkId,
                DOC_ID, docId == null ? 0L : docId,
                KB_ID, kbId,
                DOC_NAME, docName == null ? "" : docName,
                PAGE_NUM, pageNum,
                TITLE, title == null ? "" : title,
                SOURCE_TYPE, sourceType,
                CONTENT, content,
                CONTENT_VECTOR, contentVector);
    }

    /** 构建一份写入 ES 的文档（chunk 专用：带清洗状态，供 DEFER 审核后索引/重索引） */
    public static Map<String, Object> chunkDocument(Long chunkId, Long docId, Long kbId, String docName,
                                                    int pageNum, String title, String content,
                                                    float[] contentVector, String cleanStatus) {
        return Map.of(
                CHUNK_ID, chunkId,
                DOC_ID, docId == null ? 0L : docId,
                KB_ID, kbId,
                DOC_NAME, docName == null ? "" : docName,
                PAGE_NUM, pageNum,
                TITLE, title == null ? "" : title,
                SOURCE_TYPE, SourceType.CHUNK.value(),
                CONTENT, content,
                CONTENT_VECTOR, contentVector,
                CLEAN_STATUS, cleanStatus == null ? "" : cleanStatus);
    }
}
