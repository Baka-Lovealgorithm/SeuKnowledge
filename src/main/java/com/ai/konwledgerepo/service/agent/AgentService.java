package com.ai.konwledgerepo.service.agent;

import com.ai.konwledgerepo.common.Defaults;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
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

    public AgentService(AgentRepository repository,
                        RedisCacheService redisCacheService,
                        SeuCacheProperties cacheProps) {
        this.repository = repository;
        this.redisCacheService = redisCacheService;
        this.agentTtl = Duration.ofSeconds(cacheProps.agentTtlSeconds());
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
            agent.setTopK(5);
            agent.setTopN(5);
            agent.setVerifyThreshold(0.7);
            agent.setMaxRetry(2);
            agent.setMemoryWindow(20);
            agent.setChunkWeight(1.0);
            agent.setBusinessWeight(1.2);
            agent.setQaWeight(1.2);
            Agent saved = repository.save(agent);
            evictAgentConfig(kbId);
            return saved;
        });
    }

    public AgentResponse getResponse(Long kbId, String kbName) {
        return toResponse(getOrCreate(kbId, kbName));
    }

    /** 更新 Agent 配置：null 字段保留原值，支持动态修改提示词与检索/记忆策略。 */
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
        if (request.topK() != null) {
            agent.setTopK(request.topK());
        }
        if (request.topN() != null) {
            agent.setTopN(request.topN());
        }
        if (request.verifyThreshold() != null) {
            agent.setVerifyThreshold(request.verifyThreshold());
        }
        if (request.maxRetry() != null) {
            agent.setMaxRetry(request.maxRetry());
        }
        if (request.memoryWindow() != null) {
            agent.setMemoryWindow(request.memoryWindow());
        }
        if (request.chunkWeight() != null) {
            agent.setChunkWeight(request.chunkWeight());
        }
        if (request.businessWeight() != null) {
            agent.setBusinessWeight(request.businessWeight());
        }
        if (request.qaWeight() != null) {
            agent.setQaWeight(request.qaWeight());
        }
        repository.save(agent);
        evictAgentConfig(kbId);
        return toResponse(agent);
    }

    /**
     * 供问答链路使用：解析为不可变配置快照（Redis 缓存优先，miss 回源 DB 回填）。
     * 未配置 Agent 或字段缺失时回退默认值（全局默认 maxRetry / memoryWindow 来自 application.yml）。
     * 缓存键仅含 kbId；Agent 更新/创建与知识库改名（KnowledgeBaseService）会失效缓存。
     */
    public AgentConfig toAgentConfig(Long kbId, String kbName, int defaultMaxRetry, int defaultMemoryWindow) {
        Optional<AgentConfig> cached = redisCacheService.get(RedisKeys.agent(kbId), AgentConfig.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        AgentConfig config = resolveToAgentConfig(kbId, kbName, defaultMaxRetry, defaultMemoryWindow);
        redisCacheService.set(RedisKeys.agent(kbId), config, agentTtl);
        return config;
    }

    private AgentConfig resolveToAgentConfig(Long kbId, String kbName, int defaultMaxRetry, int defaultMemoryWindow) {
        Agent agent = repository.findByKbId(kbId).orElse(null);
        if (agent == null) {
            return new AgentConfig(Defaults.DEFAULT_AGENT_NAME, defaultSystemPrompt(kbName), 5, 0.7,
                    defaultMaxRetry, defaultMemoryWindow);
        }
        return new AgentConfig(
                agent.getName(),
                blankTo(agent.getSystemPrompt(), defaultSystemPrompt(kbName)),
                nz(agent.getTopK(), 5),
                agent.getVerifyThreshold() == null ? 0.7 : agent.getVerifyThreshold(),
                nz(agent.getMaxRetry(), defaultMaxRetry),
                nz(agent.getMemoryWindow(), defaultMemoryWindow));
    }

    public static String defaultSystemPrompt(String kbName) {
        return "你是「" + kbName + "」知识库的智能问答助手。回答必须基于知识库证据，"
                + "不编造、不推测；证据不足时如实说明；与知识库无关的问题可简短闲聊，但不涉及知识库内容。";
    }

    private AgentResponse toResponse(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getKbId(), agent.getName(), agent.getDescription(),
                agent.getSystemPrompt(), agent.getTopK(), agent.getTopN(), agent.getVerifyThreshold(),
                agent.getMaxRetry(), agent.getMemoryWindow(), agent.getChunkWeight(),
                agent.getBusinessWeight(), agent.getQaWeight(), agent.getCreatedAt());
    }

    private static String blankTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static int nz(Integer v, int fallback) {
        return v == null || v <= 0 ? fallback : v;
    }
}
