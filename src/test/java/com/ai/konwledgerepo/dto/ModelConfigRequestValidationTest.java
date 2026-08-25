package com.ai.konwledgerepo.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelConfigRequest 用途绑定白名单校验：VERIFY 必须可绑定（CHAT 校验模型），
 * 未知用途必须被 @Pattern 拒绝。回归 bug：新增配置无法为 VERIFY 绑定用途。
 */
class ModelConfigRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
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

    @Test
    void usage_allSupportedAccepted() {
        for (String u : new String[]{"EXTRACT", "GENERATE", "RETRIEVE", "VISION", "RERANK", "VERIFY", "TITLE", "ROUTER"}) {
            ModelConfigRequest req = new ModelConfigRequest("n", "DASHSCOPE", "CHAT", u,
                    "m", "env:test_key", null, null, null, false, true, false, null);
            Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req);
            assertTrue(vs.isEmpty(), () -> "用途 " + u + " 应合法，实际: " + violations(vs));
        }
    }

    @Test
    void usage_unknownRejected() {
        ModelConfigRequest req = new ModelConfigRequest("n", "DASHSCOPE", "CHAT", "FOO",
                "m", "env:test_key", null, null, null, false, true, false, null);
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req);
        assertTrue(vs.stream().anyMatch(v -> v.getPropertyPath().toString().equals("usage")),
                () -> "期望 usage 校验失败，实际: " + violations(vs));
    }

    @Test
    void usage_titleAcceptedOnTitleType() {
        ModelConfigRequest req = new ModelConfigRequest("标题模型", "DASHSCOPE", "TITLE", "TITLE",
                "qwen-turbo", "env:test_key", null, null, null, false, true, false, null);
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req);
        assertTrue(vs.isEmpty(), () -> "TITLE 类型 + TITLE 用途应合法，实际: " + violations(vs));
    }

    @Test
    void usage_blankAllowedAsGeneric() {
        ModelConfigRequest req = new ModelConfigRequest("n", "DASHSCOPE", "CHAT", null,
                "m", "env:test_key", null, null, null, false, true, false, null);
        Set<ConstraintViolation<ModelConfigRequest>> vs = validator.validate(req);
        assertTrue(vs.isEmpty(), () -> "用途留空(通用)应合法，实际: " + violations(vs));
    }
}
