package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.dto.AskResponse;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.graph.QaContext;
import com.ai.konwledgerepo.graph.QaContextKey;
import com.ai.konwledgerepo.graph.QaGraphRunner;
import com.ai.konwledgerepo.service.agent.AgentService;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同步问答域（非流式 ask）：执行问答状态图，结果经 {@link ChatMessageStore} 落库
 * （事务边界在落库层，LLM/检索调用不占事务）。与流式 ask 行为一致：每次均以实时上下文生成答案，
 * 不做相同问题缓存（同一问题在不同上下文下答案不同，缓存会引入上下文串扰）。
 */
@Service
public class QaAnswerService {

    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final ChatMessageStore messageStore;
    private final KnowledgeBaseService kbService;
    private final AgentService agentService;
    private final QaGraphRunner qaGraphRunner;
    private final SessionTitleService titleService;
    private final int maxRetry;
    private final int messageWindow;

    public QaAnswerService(ChatSessionService sessionService,
                           ChatHistoryService historyService,
                           ChatMessageStore messageStore,
                           KnowledgeBaseService kbService,
                           AgentService agentService,
                           QaGraphRunner qaGraphRunner,
                           SessionTitleService titleService,
                           SeuQaProperties qaProps) {
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.messageStore = messageStore;
        this.kbService = kbService;
        this.agentService = agentService;
        this.qaGraphRunner = qaGraphRunner;
        this.titleService = titleService;
        this.maxRetry = qaProps.maxRetry();
        this.messageWindow = qaProps.messageWindow();
    }

    /**
     * 提问：执行问答状态图，持久化用户消息与助手消息，返回答案与证据。
     * 每次均以实时上下文（会话历史）完整执行链路，不做相同问题缓存——同一问题在不同
     * 上下文下答案不同，缓存会引入上下文串扰。
     */
    public AskResponse ask(Long sessionId, Long userId, String question, Long workspaceId) {
        ChatSession session = sessionService.getSession(sessionId, userId, workspaceId);
        KnowledgeBase kb = kbService.getEntityCached(session.getKbId());
        if (!kb.isActive()) {
            throw new BizException("知识库已停用，无法问答");
        }

        AgentConfig agent = agentService.toAgentConfig(kb.getId(), kb.getName(), maxRetry, messageWindow);
        List<String> history = historyService.recentHistory(sessionId, agent.memoryWindow());

        // 标题异步生成（与链路并行，不阻塞落库）
        titleService.submitAutoTitle(session, question, workspaceId);

        QaContext.QaInput input = new QaContext.QaInput(
                kb.getId(), kb.getName(), sessionId, question, history, agent.maxRetry(), agent, workspaceId);
        OverAllState result;
        try {
            result = qaGraphRunner.run(input);
        } catch (Exception e) {
            throw new BizException("问答处理失败，请检查文本/向量模型配置是否可用: " + Texts.truncate(e.getMessage(), 200));
        }

        String answer = result.value(QaContextKey.CHAT_ONLY_ANSWER)
                .map(String::valueOf)
                .or(() -> result.value(QaContextKey.ANSWER).map(String::valueOf))
                .orElse(Defaults.QA_FALLBACK_ANSWER);
        String refs = result.value(QaContextKey.REFS).map(String::valueOf).orElse("[]");
        String intent = result.value(QaContextKey.INTENT).map(String::valueOf).orElse(null);

        messageStore.persistAnswer(session, userId, question, answer, refs, workspaceId);
        return new AskResponse(answer, refs, intent);
    }
}
