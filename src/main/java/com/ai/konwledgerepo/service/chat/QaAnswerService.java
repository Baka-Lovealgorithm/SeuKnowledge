package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.Texts;
import com.ai.konwledgerepo.dto.AskResponse;
import org.springframework.stereotype.Service;

/**
 * 同步问答域（非流式 ask）：委托 {@link QaExecutionService} 执行核心链路，返回 AskResponse。
 */
@Service
public class QaAnswerService {

    private final QaExecutionService executionService;

    public QaAnswerService(QaExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 同步提问：执行问答状态图，持久化用户/助手消息，返回答案与证据。
     * 会话级互斥（由 QaExecutionService 统一管理）。
     */
    public AskResponse ask(Long sessionId, Long userId, String question, Long workspaceId) {
        try {
            QaExecutionService.QaAskResult result = executionService.execute(
                    sessionId, userId, question, workspaceId, null);
            return new AskResponse(result.answer(), result.refs(), result.intent(), result.messageId());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(Texts.friendlyError(e.getMessage()));
        }
    }
}