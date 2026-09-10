package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.FeedbackReason;
import com.ai.konwledgerepo.entity.MessageFeedback;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FeedbackRequest 白名单校验。
 * <p>
 * 与 {@code ModelConfigRequestValidationTest} 同一动机：DTO 的 {@code @Pattern} 白名单必须与枚举逐值对齐。
 * 当年 CHITCHAT 漏在 @Pattern 里的表现是「前端能选、服务层合法、保存 400」——
 * 点踩原因以后加值时，这个测试会先把漏改的那一侧照出来。
 */
class FeedbackRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static Set<ConstraintViolation<FeedbackRequest>> validate(String rating, String reason, String note) {
        return validator.validate(new FeedbackRequest(rating, reason, note));
    }

    private static String describe(Set<ConstraintViolation<FeedbackRequest>> vs) {
        return vs.stream().map(v -> v.getPropertyPath() + "=" + v.getMessage()).collect(Collectors.joining("; "));
    }

    /** 原因枚举每个值都必须能提交（枚举加值而 @Pattern 漏改时这里先红） */
    @Test
    void reason_everyFeedbackReasonValueAccepted() {
        for (FeedbackReason r : FeedbackReason.values()) {
            Set<ConstraintViolation<FeedbackRequest>> vs = validate("DOWN", r.name(), null);
            assertTrue(vs.isEmpty(), () -> "原因 " + r.name() + " 应合法，实际: " + describe(vs));
        }
    }

    /** 评价枚举每个值 + 撤销值 NONE 都要能提交 */
    @Test
    void rating_everyFeedbackValueAndNoneAccepted() {
        for (MessageFeedback f : MessageFeedback.values()) {
            Set<ConstraintViolation<FeedbackRequest>> vs = validate(f.name(), null, null);
            assertTrue(vs.isEmpty(), () -> "评价 " + f.name() + " 应合法，实际: " + describe(vs));
        }
        assertTrue(validate(MessageFeedback.NONE_REQUEST, null, null).isEmpty(), "NONE(撤销) 应合法");
    }

    /** 原因可留空：用户点踩后跳过原因也要能存下来，否则采集率会被必填卡死 */
    @Test
    void reason_nullAllowed() {
        assertTrue(validate("DOWN", null, null).isEmpty(), "原因留空应合法（允许跳过）");
    }

    @Test
    void reason_unknownRejected() {
        Set<ConstraintViolation<FeedbackRequest>> vs = validate("DOWN", "NOT_A_REASON", null);
        assertFalse(vs.isEmpty(), "非法原因码应被拒");
        assertTrue(vs.stream().anyMatch(v -> "reason".equals(v.getPropertyPath().toString())),
                () -> "期望 reason 校验失败，实际: " + describe(vs));
    }

    @Test
    void rating_unknownRejected() {
        assertFalse(validate("MAYBE", null, null).isEmpty(), "评价取值应被限制为 UP/DOWN/NONE");
    }

    @Test
    void rating_blankRejected() {
        assertFalse(validate(" ", null, null).isEmpty(), "空评价应被 @NotBlank 拦下");
    }

    @Test
    void note_over200Rejected() {
        Set<ConstraintViolation<FeedbackRequest>> vs = validate("DOWN", "OTHER", "x".repeat(201));
        assertTrue(vs.stream().anyMatch(v -> "note".equals(v.getPropertyPath().toString())),
                () -> "201 字备注应被 @Size(max=200) 拒，实际: " + describe(vs));
    }
}
