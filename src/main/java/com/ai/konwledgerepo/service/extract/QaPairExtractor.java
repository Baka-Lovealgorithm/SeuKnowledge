package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.QaPairRequest;
import com.ai.konwledgerepo.entity.Chunk;
import com.ai.konwledgerepo.entity.Document;
import com.ai.konwledgerepo.service.knowledge.QaPairService;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 问答对抽取器：对单个 chunk 调 LLM 抽取问题/答案，
 * 经 QaPairService.createDraft 入库（同 KB 相同问题 DRAFT 自动替换，避免重复草稿堆积）。
 */
@Component
public class QaPairExtractor {

    private final QaTracing qaTracing;
    private final PromptCatalog promptCatalog;
    private final QaPairService qaPairService;
    private final ExtractJsonParser jsonParser;

    public QaPairExtractor(QaTracing qaTracing,
                           PromptCatalog promptCatalog,
                           QaPairService qaPairService,
                           ExtractJsonParser jsonParser) {
        this.qaTracing = qaTracing;
        this.promptCatalog = promptCatalog;
        this.qaPairService = qaPairService;
        this.jsonParser = jsonParser;
    }

    /** 抽取单个 chunk 的问答对，返回入库条数 */
    public int extract(ChatModel chat, Long kbId, Document doc, Chunk chunk) {
        String prompt = promptCatalog.get("extract-qa").formatted(chunk.getContent());
        // 带链路追踪的 LLM 调用：生成 generation span 并按 text 类累计 token
        String response = LlmTrace.call(qaTracing, chat, prompt);
        List<Map<String, Object>> items = jsonParser.parseArray(response);
        int count = 0;
        for (Map<String, Object> item : items) {
            String question = Texts.str(item.get("question"));
            String answer = Texts.str(item.get("answer"));
            if (Texts.isBlank(question) || Texts.isBlank(answer)) {
                continue;
            }
            // createDraft：同 KB 相同问题 DRAFT 自动替换，避免重复草稿堆积
            qaPairService.createDraft(kbId, new QaPairRequest(question, answer, doc.getId()));
            count++;
        }
        return count;
    }
}
