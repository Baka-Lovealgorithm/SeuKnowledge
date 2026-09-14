package com.ai.konwledgerepo.service.agent;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
import com.ai.konwledgerepo.dto.AgentRequest;
import com.ai.konwledgerepo.dto.AgentResponse;
import com.ai.konwledgerepo.entity.Agent;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * Agent 配置业务：知识库绑定的默认 Agent 的获取/创建/更新，以及问答链路配置快照解析。
 * toAgentConfig 结果缓存于 Redis（seuknowledge:agent:{kbId}，TTL 600s），
 * Agent 更新/创建后失效，保证问答链路每次提问不重复查询 DB。
 */
@Service
public class AgentService {

    private final AgentRepository repository;
    private final RedisCacheService redisCacheService;
    private final Duration agentTtl;
    /** 记忆/重试策略兜底默认（Agent 行缺失或字段为 null 时使用，来自 seuknowledge.qa.*） */
    private final int defaultMaxRetry;
    private final int defaultRecentRounds;
    private final int defaultSummaryIntervalRounds;
    /** 近窗轮数上限：由全局记忆窗口（条）换算，保证 cachedHistory 取回足够原文 */
    private final int maxRecentRounds;

    public AgentService(AgentRepository repository,
                        RedisCacheService redisCacheService,
                        SeuCacheProperties cacheProps,
                        SeuQaProperties qaProps) {
        this.repository = repository;
        this.redisCacheService = redisCacheService;
        this.agentTtl = Duration.ofSeconds(cacheProps.agentTtlSeconds());
        this.defaultMaxRetry = Math.max(0, qaProps.maxRetry());
        this.defaultRecentRounds = Math.max(1, qaProps.recentRounds());
        this.defaultSummaryIntervalRounds = Math.max(1, qaProps.summaryIntervalRounds());
        this.maxRecentRounds = Math.max(1, qaProps.messageWindow() / 2);
    }

    /** 失效知识库的 Agent 配置快照缓存（Agent 创建/更新后调用） */
    private void evictAgentConfig(Long kbId) {
        if (kbId != null) {
            redisCacheService.delete(RedisKeys.agent(kbId));
        }
    }

    /** 获取知识库绑定的默认 Agent；不存在则按默认模板自动创建（知识库与 Agent 一一绑定）。 */
    @Transactional
    public Agent getOrCreate(Long kbId, String kbName) {
        return repository.findByKbId(kbId).orElseGet(() -> {
            Agent agent = new Agent();
            agent.setKbId(kbId);
            agent.setName(Defaults.DEFAULT_AGENT_NAME);
            agent.setDescription("知识库「" + kbName + "」的默认问答 Agent");
            agent.setSystemPrompt(defaultSystemPrompt(kbName));
            agent.setVerifyThreshold(0.7);
            agent.setMaxRetry(2);
            agent.setRecentRounds(defaultRecentRounds);
            agent.setSummaryIntervalRounds(defaultSummaryIntervalRounds);
            Agent saved = repository.save(agent);
            evictAgentConfig(kbId);
            return saved;
        });
    }

    public AgentResponse getResponse(Long kbId, String kbName) {
        return toResponse(getOrCreate(kbId, kbName));
    }

    /** 更新 Agent 配置：null 字段保留原值，支持动态修改提示词与答案/记忆策略。 */
    @Transactional
    public AgentResponse update(Long kbId, String kbName, AgentRequest request) {
        Agent agent = getOrCreate(kbId, kbName);
        if (request.name() != null && !request.name().isBlank()) {
            agent.setName(request.name());
        }
        if (request.description() != null) {
            agent.setDescription(request.description());
        }
        if (request.systemPrompt() != null) {
            agent.setSystemPrompt(request.systemPrompt());
        }
        if (request.verifyThreshold() != null) {
            agent.setVerifyThreshold(request.verifyThreshold());
        }
        if (request.maxRetry() != null) {
            agent.setMaxRetry(request.maxRetry());
        }
        if (request.recentRounds() != null) {
            agent.setRecentRounds(request.recentRounds());
        }
        if (request.summaryIntervalRounds() != null) {
            agent.setSummaryIntervalRounds(request.summaryIntervalRounds());
        }
        // 用「生效后」的值校验：update 是部分更新，未传的字段沿用原值，只改一个也要保证关系成立
        validateMemoryPolicy(nz(agent.getRecentRounds(), defaultRecentRounds),
                nz(agent.getSummaryIntervalRounds(), defaultSummaryIntervalRounds));
        repository.save(agent);
        evictAgentConfig(kbId);
        return toResponse(agent);
    }

    /**
     * 记忆策略校验（跨字段，注解表达不了）：
     * <ol>
     *   <li>近窗轮数 ≤ 全局记忆窗口/2 —— 否则 cachedHistory 取不回足够原文，配了也不生效（静默失真）；</li>
     *   <li>压缩间隔轮数 ≤ 近窗轮数 —— 摘要覆盖到「上次压缩时刻」，两次压缩之间最多积压
     *       {@code 间隔-1} 条消息，必须落在近窗内；间隔大于近窗会让中间的消息既不入摘要也不入近窗（空洞）。</li>
     * </ol>
     */
    void validateMemoryPolicy(int recentRounds, int summaryIntervalRounds) {
        if (recentRounds > maxRecentRounds) {
            throw new BizException("最近对话轮数最多 " + maxRecentRounds
                    + " 轮（受全局记忆窗口上限 " + (maxRecentRounds * 2) + " 条约束）");
        }
        if (summaryIntervalRounds > recentRounds) {
            throw new BizException("压缩间隔轮数不能大于最近对话轮数：当前间隔 " + summaryIntervalRounds
                    + " 轮 > 近窗 " + recentRounds + " 轮，两次压缩之间的对话会既不入摘要也不入近窗而丢失");
        }
    }

    /**
     * 供问答链路使用：解析为不可变配置快照（Redis 缓存优先，miss 回源 DB 回填）。
     * 未配置 Agent 或字段缺失时回退默认值（全局默认 maxRetry / recentRounds / summaryIntervalRounds 来自 application.yml）。
     * 缓存键仅含 kbId；Agent 更新/创建与知识库改名（KnowledgeBaseService）会失效缓存。
     */
    public AgentConfig toAgentConfig(Long kbId, String kbName) {
        Optional<AgentConfig> cached = redisCacheService.get(RedisKeys.agent(kbId), AgentConfig.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        AgentConfig config = resolveToAgentConfig(kbId, kbName);
        redisCacheService.set(RedisKeys.agent(kbId), config, agentTtl);
        return config;
    }

    /**
     * 取该知识库生效的压缩间隔轮数。
     * 供只有 kbId、拿不到完整快照的路径使用（如流式中断落库）——不走 {@link #toAgentConfig}，
     * 因为那里若 kbName 不完整，会把错误的 systemPrompt 写进 Agent 快照缓存。
     * 优先复用已有快照缓存，miss 时按 DB 行计算且**不写缓存**。
     */
    public int effectiveSummaryIntervalRounds(Long kbId) {
        return redisCacheService.get(RedisKeys.agent(kbId), AgentConfig.class)
                .map(AgentConfig::summaryIntervalRounds)
                .orElseGet(() -> {
                    Agent agent = repository.findByKbId(kbId).orElse(null);
                    if (agent == null) {
                        return defaultSummaryIntervalRounds;
                    }
                    int recent = Math.min(Math.max(1,
                            nz(agent.getRecentRounds(), defaultRecentRounds)), maxRecentRounds);
                    return Math.min(Math.max(1,
                            nz(agent.getSummaryIntervalRounds(), defaultSummaryIntervalRounds)), recent);
                });
    }

    /**
     * 生效近窗轮数：DB 值可能为 null（存量行从未写过新列）或脏值（旧「条数」语义），
     * 统一兜底 + 上限收敛，保证读取侧也不违反不变式。
     */
    private int effectiveRecentRounds(Agent agent) {
        return Math.min(Math.max(1, nz(agent.getRecentRounds(), defaultRecentRounds)), maxRecentRounds);
    }

    /** 生效压缩间隔轮数：兜底后不得大于生效近窗轮数 */
    private int effectiveIntervalRounds(Agent agent) {
        return Math.min(Math.max(1, nz(agent.getSummaryIntervalRounds(), defaultSummaryIntervalRounds)),
                effectiveRecentRounds(agent));
    }

    private AgentConfig resolveToAgentConfig(Long kbId, String kbName) {
        Agent agent = repository.findByKbId(kbId).orElse(null);
        if (agent == null) {
            return new AgentConfig(Defaults.DEFAULT_AGENT_NAME, defaultSystemPrompt(kbName), 0.7,
                    defaultMaxRetry, defaultRecentRounds, defaultSummaryIntervalRounds);
        }
        return new AgentConfig(
                agent.getName(),
                blankTo(agent.getSystemPrompt(), defaultSystemPrompt(kbName)),
                agent.getVerifyThreshold() == null ? 0.7 : agent.getVerifyThreshold(),
                nz(agent.getMaxRetry(), defaultMaxRetry),
                effectiveRecentRounds(agent),
                effectiveIntervalRounds(agent));
    }

    public static String defaultSystemPrompt(String kbName) {
        return "你是「" + kbName + "」知识库的智能问答助手。回答必须基于知识库证据，"
                + "不编造、不推测；证据不足时如实说明；与知识库无关的问题可简短闲聊，但不涉及知识库内容。";
    }

    private AgentResponse toResponse(Agent agent) {
        // 回显「生效值」而非原始存储值：存量行新列为 null，前端不应靠自己的默认值兜底
        return new AgentResponse(agent.getId(), agent.getKbId(), agent.getName(), agent.getDescription(),
                agent.getSystemPrompt(), agent.getVerifyThreshold(),
                nz(agent.getMaxRetry(), defaultMaxRetry),
                effectiveRecentRounds(agent), effectiveIntervalRounds(agent),
                agent.getCreatedAt());
    }

    private static String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static int nz(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
