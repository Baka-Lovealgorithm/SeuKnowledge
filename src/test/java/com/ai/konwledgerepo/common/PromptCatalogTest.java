package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.service.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提示词目录测试：命名占位符渲染（{{name}} / {{name|默认}}）、
 * 无参构造（classpath 兜底，测试场景）、有参构造（DB 优先）与缺失模板报错。
 */
class PromptCatalogTest {

    @Test
    void render_replacesNamedPlaceholders() {
        String template = "你是助手。问题：{{question}} 摘要：{{summary|无}}";
        String out = PromptCatalog.renderText(template, Map.of("question", "如何报销？"));
        assertEquals("你是助手。问题：如何报销？ 摘要：无", out);
    }

    @Test
    void render_missingVariableWithoutDefault_becomesEmpty() {
        String template = "【{{missing}}】你好";
        assertEquals("【】你好", PromptCatalog.renderText(template, Map.of()));
    }

    @Test
    void render_nullTemplate_returnsEmpty() {
        assertEquals("", PromptCatalog.renderText(null, Map.of()));
    }

    @Test
    void render_nullVars_treatedAsEmpty() {
        String template = "a{{x}}b";
        assertEquals("ab", PromptCatalog.renderText(template, Map.of()));
    }

    @Test
    void noArgConstructor_loadsClasspathTemplates() {
        PromptCatalog catalog = new PromptCatalog();
        // 种子模板应能通过 classpath 兜底加载（测试环境无 Spring/DB）
        String tpl = catalog.get("chat-only");
        assertNotNull(tpl);
        assertTrue(tpl.contains("{{question}}"), "模板应使用命名占位符: " + tpl);
    }

    @Test
    void renderWithService_prefersDbTemplate() {
        PromptTemplateService service = mock(PromptTemplateService.class);
        when(service.getContent("chat-only")).thenReturn("DB 模板：{{question}}");
        PromptCatalog catalog = new PromptCatalog(service);
        String out = catalog.render("chat-only", Map.of("question", "你好"));
        assertEquals("DB 模板：你好", out);
    }

    @Test
    void renderWithService_dbMiss_fallsBackToClasspath() {
        PromptTemplateService service = mock(PromptTemplateService.class);
        when(service.getContent("chat-only")).thenReturn(null);
        PromptCatalog catalog = new PromptCatalog(service);
        String out = catalog.render("chat-only", Map.of("question", "你好"));
        assertTrue(out.contains("你好"), "classpath 兜底应仍可渲染: " + out);
    }

    @Test
    void get_unknownTemplate_throws() {
        PromptCatalog catalog = new PromptCatalog();
        assertThrows(IllegalStateException.class, () -> catalog.get("no-such-template"));
    }
}
