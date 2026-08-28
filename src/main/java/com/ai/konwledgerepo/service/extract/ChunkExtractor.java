package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

/**
 * 抽取器模板：prompt → LLM → JSON 解析 → 字段映射 → 入库。
 * 子类提供 prompt 键、字段映射与入库回调。
 */
public abstract class ChunkExtractor<T> {

    protected final QaTracing qaTracing;
    protected final PromptCatalog promptCatalog;
    protected final ExtractJsonParser jsonParser;

    protected ChunkExtractor(QaTracing qaTracing, PromptCatalog promptCatalog, ExtractJsonParser jsonParser) {
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
        this.jsonParser = jsonParser;
    }

    /** 抽取单个 chunk，返回入库条数 */
    public int extract(ChatModel chat, Long kbId, Document doc, Chunk chunk) {
        String prompt = promptCatalog.get(promptKey()).formatted(chunk.getContent());
        String response = LlmTrace.call(qaTracing, chat, prompt);
        List<Map<String, Object>> items = jsonParser.parseArray(response);
        int count = 0;
        for (Map<String, Object> item : items) {
            T mapped = mapItem(item, doc.getId());
            if (mapped == null) continue;
            saveDraft(kbId, mapped);
            count++;
        }
        return count;
    }

    /** 提示词模板键（如 "extract-business"） */
    protected abstract String promptKey();

    /** 字段映射：从 JSON 条目构造领域对象，返回 null 表示跳过 */
    protected abstract T mapItem(Map<String, Object> item, Long docId);

    /** 入库 */
    protected abstract void saveDraft(Long kbId, T item);
}