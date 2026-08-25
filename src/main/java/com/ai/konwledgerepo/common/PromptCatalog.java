package com.ai.konwledgerepo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 提示词模板目录：启动时从 classpath:prompts/*.txt 加载，
 * 收敛散落在 graph 节点 / 抽取执行器 / 服务中的内联提示词文本块。
 * 模板内占位符使用 String.formatted 约定（%s 按序填充）。
 */
@Component
public class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);

    private final Map<String, String> templates = new HashMap<>();

    public PromptCatalog() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath:prompts/*.txt");
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(".txt")) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    templates.put(name.substring(0, name.length() - 4),
                            new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            log.info("提示词模板加载完成：{} 个", templates.size());
        } catch (IOException e) {
            throw new IllegalStateException("提示词模板加载失败", e);
        }
    }

    /** 按模板名（文件名去 .txt）取模板；不存在时抛 IllegalStateException */
    public String get(String name) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalStateException("提示词模板不存在: prompts/" + name + ".txt");
        }
        return template;
    }
}
