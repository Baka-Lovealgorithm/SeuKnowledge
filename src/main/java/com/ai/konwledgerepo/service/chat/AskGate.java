package com.ai.konwledgerepo.service.chat;

import org.springframework.stereotype.Component;

/**
 * 问答入口的限流与防重复提交统一检查（同步 / 流式共用）：
 * 返回 null 表示放行，否则为拒绝原因文案。
 */
@Component
public class AskGate {

    private final RateLimitService rateLimitService;

    public AskGate(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /** 通过检查返回 null；被限流或防重拦截时返回对应拒绝文案 */
    public String rejectReason(Long userId, String question) {
        if (!rateLimitService.tryAcquireAsk(userId)) {
            return "请求过于频繁，请稍后再试";
        }
        if (!rateLimitService.tryDedup(userId, question)) {
            return "请勿重复提交相同问题";
        }
        return null;
    }
}
