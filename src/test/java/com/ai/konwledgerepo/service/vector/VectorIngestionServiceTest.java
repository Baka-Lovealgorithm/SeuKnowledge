package com.ai.konwledgerepo.service.vector;

import com.ai.konwledgerepo.config.props.SeuEsProperties;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.ChunkStatus;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.ChunkRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.service.knowledgebase.WorkspaceIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化服务测试：标题拼接进入 embedding 输入（knn 检索感知标题）。
 * 说明：ElasticsearchClient 继承自非 public 父类，Mockito 无法 stub 其方法，
 * 故 ES 写入路径不 mock ES client（成功路径由 {@link #embedText} / {@link #embedTexts}
 * 纯逻辑用例覆盖），错误/短路路径（不触达 ES）用 mock 验证。
 */
class VectorIngestionServiceTest {

    private ChunkRepository chunkRepository;
    private DocumentRepository documentRepository;
    private WorkspaceIdResolver workspaceIdResolver;
    private ModelFactory modelFactory;
    private EmbeddingModel embeddingModel;
    private VectorIngestionService service;

    @BeforeEach
    void setUp() {
        // esClient 无法被 Mockito mock（父类非 public），传 null：被测试方法在 ES 写入前即返回/抛错，不触达
        chunkRepository = mock(ChunkRepository.class);
        documentRepository = mock(DocumentRepository.class);
        workspaceIdResolver = mock(WorkspaceIdResolver.class);
        modelFactory = mock(ModelFactory.class);
        embeddingModel = mock(EmbeddingModel.class);
        service = new VectorIngestionService(null, chunkRepository, documentRepository,
                workspaceIdResolver, modelFactory, new SeuEsProperties("kb_chunk", 1024));
        when(workspaceIdResolver.resolve(any(Long.class))).thenReturn(10L);
        when(modelFactory.getEmbeddingModelByUsage(anyString(), anyLong())).thenReturn(embeddingModel);
    }

    // ===== embedText：标题拼接核心逻辑 =====

    @Test
    void embedText_titleAndContent_concatenated() {
        assertEquals("3.2 配置 QoS\n正文内容", VectorIngestionService.embedText("3.2 配置 QoS", "正文内容"));
    }

    @Test
    void embedText_blankTitle_returnsContentAlone() {
        assertEquals("正文内容", VectorIngestionService.embedText("  ", "正文内容"));
    }

    @Test
    void embedText_nullTitle_returnsContentAlone() {
        assertEquals("正文内容", VectorIngestionService.embedText(null, "正文内容"));
    }

    @Test
    void embedText_blankContent_returnsContent() {
        assertNull(VectorIngestionService.embedText("标题", null));
        assertEquals("", VectorIngestionService.embedText("标题", ""));
    }

    // ===== embedTexts：chunk 批量映射（替代 ingest 成功路径的文本构造验证）=====

    @Test
    void embedTexts_mixedTitles_mapsEachChunk() {
        List<Chunk> chunks = List.of(
                chunk(1L, "3.2 配置 QoS", "正文一"),
                chunk(2L, null, "正文二"),
                chunk(3L, "  ", "正文三"));

        List<String> texts = VectorIngestionService.embedTexts(chunks);

        assertEquals(3, texts.size());
        assertEquals("3.2 配置 QoS\n正文一", texts.get(0), "带标题 chunk 应拼接标题进向量化文本");
        assertEquals("正文二", texts.get(1), "null 标题 chunk 应回退纯正文");
        assertEquals("正文三", texts.get(2), "空白标题 chunk 应回退纯正文");
    }

    // ===== ingest：embedding 失败路径（不触达 ES）=====

    @Test
    void ingest_embeddingFailure_marksDocumentError() {
        Chunk c = chunk(1L, "标题", "正文");
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc()));
        when(chunkRepository.findByDocIdOrderBySeqAsc(1L)).thenReturn(List.of(c));
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("embed 失败"));

        service.ingest(1L);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertEquals("ERROR", captor.getValue().getParseStatus(), "embedding 失败应将文档置为 ERROR");
    }

    // ===== indexSource：内容为空短路（不触达 ES）=====

    @Test
    void indexSource_blankContent_skipsEmbedding() {
        boolean ok = service.indexSource("BUSINESS", 7L, 1L, null, "test.md", 0, "标题", "  ");

        assertEquals(false, ok, "内容为空不应写入");
        verify(embeddingModel, never()).embed(anyString());
    }

    // ===== 构造辅助 =====

    private Chunk chunk(long id, String title, String content) {
        Chunk c = new Chunk();
        c.setId(id);
        c.setDocId(1L);
        c.setKbId(1L);
        c.setTitle(title);
        c.setContent(content);
        c.setStatus(ChunkStatus.EMBEDDING.value());
        return c;
    }

    private Document doc() {
        Document d = new Document();
        d.setId(1L);
        d.setKbId(1L);
        d.setFileName("test.md");
        return d;
    }
}
