package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.JsonLists;
import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.BusinessKnowledgeRequest;
import com.ai.konwledgerepo.service.knowledge.BusinessKnowledgeService;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 业务知识抽取器：继承 ChunkExtractor 模板，仅保留 prompt 键、字段映射与入库差异。
 */
@Component
public class BusinessKnowledgeExtractor extends ChunkExtractor<BusinessKnowledgeRequest> {

    private final BusinessKnowledgeService businessKnowledgeService;

    public BusinessKnowledgeExtractor(QaTracing qaTracing, PromptCatalog promptCatalog,
                                      BusinessKnowledgeService businessKnowledgeService, ExtractJsonParser jsonParser) {
        super(qaTracing, promptCatalog, jsonParser);
        this.businessKnowledgeService = businessKnowledgeService;
    }

    @Override
    protected String promptKey() { return "extract-business"; }

    @Override
    protected BusinessKnowledgeRequest mapItem(Map<String, Object> item, Long docId) {
        String term = Texts.str(item.get("term"));
        if (Texts.isBlank(term)) return null;
        return new BusinessKnowledgeRequest(term, JsonLists.asStringList(item.get("aliases")),
                Texts.strOrNull(item.get("definition")), Texts.strOrNull(item.get("scope")),
                Texts.strOrNull(item.get("example")), Texts.strOrNull(item.get("prohibitedRules")), docId);
    }

    @Override
    protected void saveDraft(Long kbId, BusinessKnowledgeRequest item) {
        businessKnowledgeService.createDraft(kbId, item);
    }
}