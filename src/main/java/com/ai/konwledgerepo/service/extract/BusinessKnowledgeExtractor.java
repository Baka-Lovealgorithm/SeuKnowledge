package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.BusinessKnowledgeRequest;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.knowledge.BusinessKnowledgeService;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 业务知识抽取器：对单个 chunk 调 LLM 抽取术语/别名/定义等，
 * 经 BusinessKnowledgeService.createDraft 入库（同 KB 同名 DRAFT 自动替换，避免重复草稿堆积）。
 */
@Component
public class BusinessKnowledgeExtractor {

    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;
    private final BusinessKnowledgeService businessKnowledgeService;
    private final ExtractJsonParser jsonParser;

    public BusinessKnowledgeExtractor(QaTracing qaTracing,
                                      PromptCatalog promptCatalog,
                                      BusinessKnowledgeService businessKnowledgeService,
                                      ExtractJsonParser jsonParser) {
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
        this.businessKnowledgeService = businessKnowledgeService;
        this.jsonParser = jsonParser;
    }

    /** 抽取单个 chunk 的业务知识，返回入库条数 */
    public int extract(ChatModel chat, Long kbId, Document doc, Chunk chunk) {
        String prompt = promptCatalog.get("extract-business").formatted(chunk.getContent());
        // 带链路追踪的 LLM 调用：生成 generation span 并按 text 类累计 token
        String response = LlmTrace.call(qaTracing, chat, prompt);
        List<Map<String, Object>> items = jsonParser.parseArray(response);
        int count = 0;
        for (Map<String, Object> item : items) {
            String term = Texts.str(item.get("term"));
            if (Texts.isBlank(term)) {
                continue;
            }
            BusinessKnowledgeRequest req = new BusinessKnowledgeRequest(
                    term, listOf(item.get("aliases")),
                    Texts.strOrNull(item.get("definition")), Texts.strOrNull(item.get("scope")),
                    Texts.strOrNull(item.get("example")), Texts.strOrNull(item.get("prohibitedRules")),
                    doc.getId());
            // createDraft：同 KB 同名 DRAFT 自动替换，避免重复草稿堆积
            businessKnowledgeService.createDraft(kbId, req);
            count++;
        }
        return count;
    }

    private List<String> listOf(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return new ArrayList<>();
    }
}
