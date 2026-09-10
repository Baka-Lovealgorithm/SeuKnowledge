package com.ai.konwledgerepo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 答案反馈请求：rating=UP/DOWN 采集，NONE 撤销（四列一并清空）。
 * <p>
 * reason 允许为空——用户可以跳过原因直接点踩，采集率优先于字段完整度。
 */
public record FeedbackRequest(

        @NotBlank(message = "评价不能为空")
        @Pattern(regexp = "UP|DOWN|NONE", message = "评价仅支持 UP / DOWN / NONE(撤销)")
        String rating,

        /**
         * 点踩原因码。白名单必须与 {@link com.ai.konwledgerepo.entity.FeedbackReason} 逐值对齐——
         * 漏值的表现是「前端能选、服务层合法、这里 400」（同 ModelConfigRequest#usage 的 CHITCHAT 教训）。
         */
        @Pattern(regexp = "NOT_ON_TARGET|OUTDATED|WRONG_CITATION|TOO_VERBOSE|OTHER",
                message = "原因仅支持 NOT_ON_TARGET / OUTDATED / WRONG_CITATION / TOO_VERBOSE / OTHER")
        String reason,

        @Size(max = 200, message = "补充说明不超过 200 字")
        String note) {
}
