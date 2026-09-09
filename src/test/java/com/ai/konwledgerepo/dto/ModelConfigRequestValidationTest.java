package com.ai.konwledgerepo.dto;

import com.ai.konwledgerepo.entity.ModelUsage;
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
 * ModelConfigRequest 类型/用途白名单校验。
 * <p>
 * 两个回归点：
 * <ul>
 *   <li><b>用途白名单必须覆盖 {@code ModelUsage} 全部值</b>——曾经漏了 CHITCHAT，导致前端能选「闲聊」
 *       但保存必被 {@code @Pattern} 拒（服务层 {@code ModelType.validUsage} 是允许的，白名单在 DTO 层）；</li>
 *   <li><b>标题不再是模型类型</b>——类型只剩 CHAT / EMBEDDING / VISION / RERANK，
 *       会话标题改为 CHAT + 用途 TITLE（历史 {@code model_type='TITLE'} 行由解析链兜底，见
 *       {@code ModelConfigResolver#resolveTitleConfigId}）。</li>
 * </ul>
 */
class ModelConfigRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static ModelConfigRequest req(String modelType, String usage) {
        return new ModelConfigRequest("n", "DASHSCOPE", modelType, usage,
                "m", "env:test_key", null, null, null, false, true, false, null);
    }

    private static String violations(Set<ConstraintViolation<ModelConfigRequest>> vs) {
        return vs.stream().map(v -> v.getPropertyPath() + "=" + v.getMessage()).collect(Collectors.joining("; "));
    }

    @Test
    void usage_verifyAccepted() {
        ModelConfigRequest req = new ModelConfigRequest("校验模型", "DASHSCOPE", "CHAT", "VERIFY",
                "deepseek-v4-flash", "env:test_key", null, null, null, false, true, false, null);
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req);
        assertTrue(vs.isEmpty(), () -> "期望无校验失败，实际: " + violations(vs));
    }

    /**
     * 用途白名单与 {@link ModelUsage} 枚举逐一对齐：枚举加值而 DTO 的 {@code @Pattern} 漏改时，
     * 这里会红——正是当初 CHITCHAT 存不进去的原因（前端能选、服务层合法、DTO 拒绝）。
     */
    @Test
    void usage_everyModelUsageValueAccepted() {
        for (ModelUsage u : ModelUsage.values()) {
            Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("CHAT", u.value()));
            assertTrue(vs.isEmpty(), () -> "用途 " + u.value() + " 应合法，实际: " + violations(vs));
        }
    }

    @Test
    void usage_chitchatAccepted() {
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("CHAT", "CHITCHAT"));
        assertTrue(vs.isEmpty(), () -> "闲聊 CHITCHAT 应可绑定（回归：曾漏出 @Pattern 白名单），实际: " + violations(vs));
    }

    @Test
    void usage_titleAcceptedOnChatType() {
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("CHAT", "TITLE"));
        assertTrue(vs.isEmpty(), () -> "CHAT + 用途 TITLE 应合法（标题已并入文本模型），实际: " + violations(vs));
    }

    @Test
    void usage_unknownRejected() {
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("CHAT", "FOO"));
        assertTrue(vs.stream().anyMatch(v -> v.getPropertyPath().toString().equals("usage")),
                () -> "期望 usage 校验失败，实际: " + violations(vs));
    }

    /** 历史类型：TITLE 不再由 API 产出（存量行靠解析链兜底，编辑保存即自愈） */
    @Test
    void modelType_legacyTitleRejected() {
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("TITLE", "TITLE"));
        assertFalse(vs.isEmpty(), "modelType=TITLE 应被拒绝");
        assertTrue(vs.stream().anyMatch(v -> v.getPropertyPath().toString().equals("modelType")),
                () -> "期望 modelType 校验失败，实际: " + violations(vs));
    }

    @Test
    void modelType_fourContractTypesAccepted() {
        for (String type : new String[]{"CHAT", "EMBEDDING", "VISION", "RERANK"}) {
            Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req(type, null));
            assertTrue(vs.isEmpty(), () -> "类型 " + type + " 应合法，实际: " + violations(vs));
        }
    }

    @Test
    void modelType_unknownRejected() {
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("ASR", null));
        assertTrue(vs.stream().anyMatch(v -> v.getPropertyPath().toString().equals("modelType")),
                () -> "期望 modelType 校验失败，实际: " + violations(vs));
    }

    @Test
    void usage_blankAllowedAsGeneric() {
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req("CHAT", null));
        assertTrue(vs.isEmpty(), () -> "用途留空(通用)应合法，实际: " + violations(vs));
    }
}
