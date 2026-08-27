package com.ai.konwledgerepo.graph;

import com.ai.konwledgerepo.entity.SourceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 证据统一渲染工具：将 {@link ChunkEvidence} 列表序列化为 JSON 数组字符串，
 * 供 AnswerCompose / AnswerVerify（阶段一）/ AnswerFaithfulness（阶段二）共用，
 * 消除三处文本渲染差异带来的评估偏置。
 * <p>
 * 输出 schema：{@code [{"index":1,"sourceType":"CHUNK","docName":"…","page":3,"title":"…","content":"…","new":false}]}
 * <ul>
 *   <li>index：1 起始，与引用编号 [n] 对齐</li>
 *   <li>sourceType：{@link SourceType#normalize} 归一化</li>
 *   <li>page：null 时输出 0</li>
 *   <li>title：空或 null 时取 docName</li>
 *   <li>new：markKeys 是否包含 {@code SourceType.dedupKey(sourceType, chunkId)}（无标记时始终 false）</li>
 * </ul>
 */
public final class EvidenceFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 无标记版本：所有条目的 new 字段均为 false */
    public static String toEvidenceJson(List<ChunkEvidence> chunks) throws JsonProcessingException {
        return toEvidenceJson(chunks, Set.of());
    }

    /**
     * 带本轮新增标记的版本：markKeys 中存在的条目 new=true。
     * @param chunks  证据列表
     * @param markKeys {@code SourceType.dedupKey(sourceType, chunkId)} 集合，非空时标记
     */
    public static String toEvidenceJson(List<ChunkEvidence> chunks, Set<String> markKeys) throws JsonProcessingException {
        boolean mark = markKeys != null && !markKeys.isEmpty();
        List<Map<String, Object>> array = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkEvidence c = chunks.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i + 1);
            item.put("sourceType", SourceType.normalize(c.sourceType()));
            item.put("docName", c.docName() == null ? "" : c.docName());
            item.put("page", c.pageNum() == null ? 0 : c.pageNum());
            item.put("title", c.title() == null || c.title().isBlank() ? c.docName() : c.title());
            item.put("content", c.content() == null ? "" : c.content());
            item.put("new", mark && markKeys.contains(SourceType.dedupKey(c.sourceType(), c.chunkId())));
            array.add(item);
        }
        return MAPPER.writeValueAsString(array);
    }

    private EvidenceFormatter() {
    }
}