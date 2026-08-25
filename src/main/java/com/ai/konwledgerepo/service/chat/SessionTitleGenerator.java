package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.graph.JudgeOptions;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.tracing.LlmTrace;
import com.ai.konwledgerepo.tracing.QaTracing;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 会话标题生成：首次提问后用大模型把问题提炼成简短标题（参照 DeepSeek 起名方式）；
 * 模型调用失败或未配置可用模型时回退为截取首问前 20 字。
 * 调用选项复用 {@link JudgeOptions}（按模型配置关闭 thinking + 限制输出），
 * 避免 deepseek 系 reasoning token 膨胀导致标题生成偶发数十秒。
 */
@Component
public class SessionTitleGenerator {

    /** 标题最大长度（超过截断并加省略号） */
    public static final int MAX_TITLE_LEN = 20;

    /** 标题输出上限：标题 ≤12 字，关思考后内容仅几个字，100 token 足够且防失控 */
    private static final int MAX_TITLE_TOKENS = 100;

    private static final String PROMPT_TEMPLATE = """
            你是对话标题生成器。根据用户提出的第一个问题，提炼出一个简洁、贴切的中文标题，不超过 12 个字。
            只输出标题本身，不要引号、句号或任何解释。

            问题：{question}
            """;

    private final ModelFactory modelFactory;
    private final QaTracing qaTracing;

    public SessionTitleGenerator(ModelFactory modelFactory, QaTracing qaTracing) {
        this.modelFactory = modelFactory;
        this.qaTracing = qaTracing;
    }

    /**
     * 生成会话标题：优先 LLM 提炼，异常/空结果时回退截取首问。
     *
     * @param question    首次提问内容
     * @param workspaceId 当前工作空间（模型解析按空间隔离）
     */
    public String generate(String question, Long workspaceId) {
        if (question == null || question.isBlank()) {
            return "新会话";
        }
        String fallback = truncate(question);
        try {
            ChatModel chat = modelFactory.getTitleChatModel(workspaceId);
            ModelConfig cfg = modelFactory.resolveTitleChatConfig(workspaceId);
            String text = LlmTrace.call(qaTracing, chat, PROMPT_TEMPLATE.replace("{question}", question),
                    JudgeOptions.of(chat, cfg, MAX_TITLE_TOKENS));
            if (text == null || text.isBlank()) {
                return fallback;
            }
            return truncate(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 截取短标题：取首行、合并空白、去掉首尾引号，超长截断加省略号。
     * 作为 LLM 提炼失败/不可用时的兜底，也用于旧会话一次性自动命名。
     */
    public static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "新会话";
        }
        String t = text.strip();
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl);
        }
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[\"'“”「」『』]+|[\"'“”「」『』]+$", "");
        return t.length() <= MAX_TITLE_LEN ? t : t.substring(0, MAX_TITLE_LEN) + "…";
    }
}
