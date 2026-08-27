package com.ai.konwledgerepo.controller;

import com.ai.konwledgerepo.common.ApiResponse;
import com.ai.konwledgerepo.common.ErrorCodes;
import com.ai.konwledgerepo.common.LogContext;
import com.ai.konwledgerepo.common.SseStreamContext;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.dto.AskRequest;
import com.ai.konwledgerepo.dto.AskResponse;
import com.ai.konwledgerepo.dto.ChatMessageResponse;
import com.ai.konwledgerepo.dto.ChatSessionCreateRequest;
import com.ai.konwledgerepo.dto.ChatSessionResponse;
import com.ai.konwledgerepo.dto.RenameRequest;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.service.chat.AskGate;
import com.ai.konwledgerepo.service.chat.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * 会话与问答接口。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final AskGate askGate;
    private final long sseTimeoutMs;

    public ChatController(ChatService chatService, AskGate askGate, SeuQaProperties qaProps) {
        this.chatService = chatService;
        this.askGate = askGate;
        // SSE 超时须大于链路超时，留 60s 余量（默认 260s）
        this.sseTimeoutMs = (qaProps.qaTimeoutSeconds() + 60L) * 1000L;
    }

    @PostMapping("/session")
    public ApiResponse<ChatSessionResponse> createSession(@RequestBody @Valid ChatSessionCreateRequest request,
                                                          @RequestAttribute("userId") Long userId,
                                                          @RequestAttribute("workspaceId") Long workspaceId) {
        ChatSession session = chatService.createSession(request.kbId(), userId, workspaceId);
        return ApiResponse.ok(ChatSessionResponse.from(session));
    }

    @GetMapping("/session")
    public ApiResponse<List<ChatSessionResponse>> listSessions(@RequestAttribute("userId") Long userId,
                                                               @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(chatService.listSessions(userId, workspaceId).stream().map(ChatSessionResponse::from).toList());
    }

    @PutMapping("/session/{id}")
    public ApiResponse<ChatSessionResponse> rename(@PathVariable Long id,
                                                   @RequestBody @Valid RenameRequest request,
                                                   @RequestAttribute("userId") Long userId,
                                                   @RequestAttribute("workspaceId") Long workspaceId) {
        ChatSession session = chatService.rename(id, userId, request.title(), workspaceId);
        return ApiResponse.ok(ChatSessionResponse.from(session));
    }

    @DeleteMapping("/session/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable Long id,
                                           @RequestAttribute("userId") Long userId,
                                           @RequestAttribute("workspaceId") Long workspaceId) {
        chatService.deleteSession(id, userId, workspaceId);
        return ApiResponse.ok();
    }

    @GetMapping("/session/{id}/messages")
    public ApiResponse<List<ChatMessageResponse>> messages(@PathVariable Long id,
                                                           @RequestAttribute("userId") Long userId,
                                                           @RequestAttribute("workspaceId") Long workspaceId) {
        return ApiResponse.ok(chatService.messages(id, userId, workspaceId).stream().map(ChatMessageResponse::from).toList());
    }

    @PostMapping("/session/{id}/ask")
    public ApiResponse<AskResponse> ask(@PathVariable Long id,
                                        @RequestBody @Valid AskRequest request,
                                        @RequestAttribute("userId") Long userId,
                                        @RequestAttribute("workspaceId") Long workspaceId) {
        // sessionId 写入 MDC：问答链路各节点日志可一次 grep 串起（同步路径）
        return LogContext.withSession(id, () -> {
            String rejection = askGate.rejectReason(userId, request.question());
            if (rejection != null) {
                return ApiResponse.error(ErrorCodes.TOO_MANY_REQUESTS, rejection);
            }
            return ApiResponse.ok(chatService.ask(id, userId, request.question(), workspaceId));
        });
    }

    /** 流式问答（SSE）：答案逐 token 推送，末尾附 refs。 */
    @PostMapping(value = "/session/{id}/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@PathVariable Long id,
                                @RequestBody @Valid AskRequest request,
                                @RequestAttribute("userId") Long userId,
                                @RequestAttribute("workspaceId") Long workspaceId) {
        // sessionId 写入 MDC 后再提交异步任务：TaskDecorator 会把上下文复制到 @Async 工作线程
        return LogContext.withSession(id, () -> {
            String rejection = askGate.rejectReason(userId, request.question());
            if (rejection != null) {
                return sseError(rejection);
            }
            SseEmitter emitter = new SseEmitter(sseTimeoutMs);
            chatService.askStreamAsync(id, userId, request.question(), emitter, workspaceId);
            return emitter;
        });
    }

    /** 取消流式生成（幂等）：停止当前会话的问答流，保留部分答案。 */
    @PostMapping("/session/{id}/ask/cancel")
    public ApiResponse<Void> cancelAsk(@PathVariable Long id,
                                       @RequestAttribute("userId") Long userId,
                                       @RequestAttribute("workspaceId") Long workspaceId) {
        chatService.cancelAsk(id, userId, workspaceId);
        return ApiResponse.ok();
    }

    /** 限流/防重拒绝时返回携带 error 事件的 SSE 流，前端按 data.type === 'error' 处理 */
    private SseEmitter sseError(String message) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseStreamContext.event("error", message));
            emitter.complete();
        } catch (IOException ignored) {
            // 客户端已断开
        }
        return emitter;
    }
}
