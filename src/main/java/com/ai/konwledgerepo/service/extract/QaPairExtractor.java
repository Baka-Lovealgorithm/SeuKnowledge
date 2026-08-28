package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.QaPairRequest;
import com.ai.konwledgerepo.service.knowledge.QaPairService;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 问答对抽取器：继承 ChunkExtractor 模板，仅保留 prompt 键、字段映射与入库差异。
 */
@Component
public class QaPairExtractor extends ChunkExtractor<QaPairRequest> {

    private final QaPairService qaPairService;

    public QaPairExtractor(QaTracing qaTracing, PromptCatalog promptCatalog,
                           QaPairService qaPairService, ExtractJsonParser jsonParser) {
        super(qaTracing, promptCatalog, jsonParser);
        this.qaPairService = qaPairService;
    }

    @Override
    protected String promptKey() { return "extract-qa"; }

    @Override
    protected QaPairRequest mapItem(Map<String, Object> item, Long docId) {
        String question = Texts.str(item.get("question"));
        String answer = Texts.str(item.get("answer"));
        if (Texts.isBlank(question) || Texts.isBlank(answer)) return null;
        return new QaPairRequest(question, answer, docId);
    }

    @Override
    protected void saveDraft(Long kbId, QaPairRequest item) {
        qaPairService.createDraft(kbId, item);
    }
}