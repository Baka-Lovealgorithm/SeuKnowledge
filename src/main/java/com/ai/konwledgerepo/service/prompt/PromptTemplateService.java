package com.ai.konwledgerepo.service.prompt;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.PromptCatalog;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.PromptTemplateResponse;
import com.ai.konwledgerepo.entity.PromptTemplate;
import com.ai.konwledgerepo.repository.PromptTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提示词模板业务（平台级）：启动种子导入 + 列表/更新/重置 + Redis 缓存。
 * <p>种子：classpath:prompts/*.txt 幂等导入 PLATFORM 层（key 已存在则跳过，保护用户修改）；
 * 更新/重置后失效 Redis 缓存，问答链路下次读取立即生效（无需重启）。
 * <p>读取：Redis 缓存优先（seuknowledge:prompt:{key}，TTL 复用 agentTtl），miss 回源 DB 回填；
 * 与 AgentService.toAgentConfig 同一套缓存模式。
 * <p>scope 扩展：当前仅 PLATFORM（全局一套），scope_type/scope_id 预留多团队覆盖。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    /** 平台层作用域标识 */
    public static final String SCOPE_PLATFORM = "PLATFORM";

    private final PromptTemplateRepository repository;
    private final RedisCacheService redisCacheService;
    private final Duration promptTtl;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public PromptTemplateService(PromptTemplateRepository repository,
                                 RedisCacheService redisCacheService,
                                 SeuCacheProperties cacheProps) {
        this.repository = repository;
        this.redisCacheService = redisCacheService;
        this.promptTtl = Duration.ofSeconds(cacheProps.agentTtlSeconds());
    }

    /**
     * 启动种子：把 classpath:prompts/*.txt 幂等导入 DB（key+scope 已存在则跳过）。
     * PromptCatalog 构造时调用，保证查询前种子已就绪。
     * 任何异常（含 DB 暂不可用）仅告警不阻断启动——classpath 兜底仍可服务。
     */
    public void seedFromClasspath() {
        try {
            Resource[] resources = resolver.getResources("classpath:prompts/*.txt");
            List<String> seeded = new ArrayList<>();
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(".txt")) {
                    continue;
                }
                String key = name.substring(0, name.length() - 4);
                if (repository.existsByKeyAndScopeTypeAndScopeId(key, SCOPE_PLATFORM, null)) {
                    continue;
                }
                String content;
                try (InputStream in = resource.getInputStream()) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                PromptTemplate tpl = new PromptTemplate();
                tpl.setKey(key);
                tpl.setScopeType(SCOPE_PLATFORM);
                tpl.setContent(content);
                tpl.setVersion(1);
                repository.save(tpl);
                seeded.add(key);
            }
            if (!seeded.isEmpty()) {
                log.info("提示词模板种子导入完成：{} 个（{}）", seeded.size(), String.join(", ", seeded));
            }
        } catch (Exception e) {
            log.warn("提示词模板种子导入失败（将回退 classpath 兜底）: {}", e.getMessage());
        }
    }

    /**
     * 取平台层模板原文（Redis 缓存优先，miss 回源 DB 回填）。
     * 返回 null 表示 DB 无该 key（调用方回退 classpath）。
     */
    public String getContent(String key) {
        Optional<String> cached = redisCacheService.getString(RedisKeys.promptTemplate(key));
        if (cached.isPresent()) {
            return cached.get();
        }
        Optional<String> db = repository.findByKeyAndScopeTypeAndScopeId(key, SCOPE_PLATFORM, null)
                .map(PromptTemplate::getContent);
        db.ifPresent(content -> redisCacheService.setString(RedisKeys.promptTemplate(key), content, promptTtl));
        return db.orElse(null);
    }

    public List<PromptTemplateResponse> list() {
        return repository.findByScopeTypeOrderByKeyAsc(SCOPE_PLATFORM).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 更新模板内容：version+1 + 记录修改人 + 失效缓存（即刻生效，无需重启） */
    @Transactional
    public PromptTemplateResponse update(String key, String content, Long userId) {
        PromptTemplate tpl = requirePlatform(key);
        tpl.setContent(content);
        tpl.setVersion(tpl.getVersion() == null ? 2 : tpl.getVersion() + 1);
        tpl.setUpdatedBy(userId);
        repository.save(tpl);
        evict(key);
        return toResponse(tpl);
    }

    /** 重置为 classpath 默认：恢复出厂模板并失效缓存（DB 无该 key 时尝试重新播种） */
    @Transactional
    public PromptTemplateResponse resetToDefault(String key) {
        PromptTemplate tpl = repository.findByKeyAndScopeTypeAndScopeId(key, SCOPE_PLATFORM, null)
                .orElseGet(() -> {
                    PromptTemplate created = new PromptTemplate();
                    created.setKey(key);
                    created.setScopeType(SCOPE_PLATFORM);
                    created.setVersion(0);
                    return repository.save(created);
                });
        String defaultContent = PromptCatalog.classpathTemplate(key);
        if (defaultContent == null) {
            throw new BizException("classpath 无该模板默认值: prompts/" + key + ".txt");
        }
        tpl.setContent(defaultContent);
        tpl.setVersion(tpl.getVersion() == null ? 1 : tpl.getVersion() + 1);
        tpl.setUpdatedBy(null);
        repository.save(tpl);
        evict(key);
        return toResponse(tpl);
    }

    /** 渲染预览：基于模板原文 + 变量渲染（不改库，纯预览） */
    public String preview(String key, Map<String, String> variables) {
        String content = getContent(key);
        if (content == null) {
            content = PromptCatalog.classpathTemplate(key);
        }
        if (content == null) {
            throw new BizException("提示词模板不存在: prompts/" + key + ".txt");
        }
        return PromptCatalog.renderText(content, variables == null ? Map.of() : variables);
    }

    private PromptTemplate requirePlatform(String key) {
        return repository.findByKeyAndScopeTypeAndScopeId(key, SCOPE_PLATFORM, null)
                .orElseThrow(() -> new BizException("提示词模板不存在: " + key));
    }

    private void evict(String key) {
        redisCacheService.delete(RedisKeys.promptTemplate(key));
    }

    private PromptTemplateResponse toResponse(PromptTemplate tpl) {
        return new PromptTemplateResponse(tpl.getKey(), tpl.getScopeType(), tpl.getScopeId(),
                tpl.getContent(), tpl.getVersion(), tpl.getUpdatedBy(),
                tpl.getCreatedAt(), tpl.getUpdatedAt());
    }
}
