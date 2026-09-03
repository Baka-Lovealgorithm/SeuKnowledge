package com.ai.konwledgerepo.common;

import com.ai.konwledgerepo.service.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板目录：启动时从 classpath:prompts/*.txt 加载（默认值与兜底），
 * 有 {@link PromptTemplateService}（Spring 注入）时以 DB 为真相源：
 * 经 service 走 Redis 缓存 → DB，miss 回退 classpath，保证种子未导入 / 新模板未播种时链路不挂。
 * <p>模板占位符使用命名变量 {@code {{name}}}（支持默认值 {@code {{name|默认}}}），
 * 渲染统一走 {@link #render(String, Map)}；无参构造仅加载 classpath（测试用）。
 */
@Component
public class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);

    /** 命名占位符：{{name}} 或 {{name|默认值}}；name 允许字母数字下划线 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*(?:\\|([^}]*))?\\s*}}");

    private final Map<String, String> classpathTemplates = new HashMap<>();
    private final PromptTemplateService promptTemplateService;

    public PromptCatalog() {
        this(null);
    }

    @Autowired
    public PromptCatalog(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
        loadClasspath();
        if (promptTemplateService != null) {
            promptTemplateService.seedFromClasspath();
        }
    }

    /** 加载 classpath:prompts/*.txt 全部模板（key = 文件名去 .txt） */
    private void loadClasspath() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:prompts/*.txt");
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(".txt")) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    classpathTemplates.put(name.substring(0, name.length() - 4),
                            new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            log.info("提示词模板加载完成：{} 个", classpathTemplates.size());
        } catch (IOException e) {
            throw new IllegalStateException("提示词模板加载失败", e);
        }
    }

    /** 取 classpath 默认模板（静态：供种子/重置/预览复用） */
    public static String classpathTemplate(String key) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try (InputStream in = resolver.getResource("classpath:prompts/" + key + ".txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 按模板名取模板：DB（Redis 缓存）优先，miss 回退 classpath；均无则抛异常 */
    public String get(String name) {
        if (promptTemplateService != null) {
            String db = promptTemplateService.getContent(name);
            if (db != null) {
                return db;
            }
        }
        String cp = classpathTemplates.get(name);
        if (cp == null) {
            throw new IllegalStateException("提示词模板不存在: prompts/" + name + ".txt");
        }
        return cp;
    }

    /** 渲染：取模板后按命名占位符替换（{{name}} / {{name|默认}}） */
    public String render(String name, Map<String, String> vars) {
        return renderText(get(name), vars);
    }

    /** 静态渲染：模板文本 + 变量 map → 渲染结果（缺变量替换为空串或默认值） */
    public static String renderText(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String defaultValue = matcher.group(2);
            String value = vars == null ? null : vars.get(name);
            String replacement;
            if (value != null) {
                replacement = value;
            } else if (defaultValue != null) {
                replacement = defaultValue.trim();
            } else {
                replacement = "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
