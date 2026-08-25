package com.ai.konwledgerepo.service.agent;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.AgentRequest;
import com.ai.konwledgerepo.dto.AgentResponse;
import com.ai.konwledgerepo.entity.Agent;
import com.ai.konwledgerepo.graph.AgentConfig;
import com.ai.konwledgerepo.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    private AgentRepository repo;
    private RedisCacheService cache;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repo = mock(AgentRepository.class);
        cache = mock(RedisCacheService.class);
        service = new AgentService(repo, cache, new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
    }

    @Test
    void getOrCreate_createsDefaultWhenMissing() {
        when(repo.findByKbId(1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Agent agent = service.getOrCreate(1L, "测试库");
        assertEquals(1L, agent.getKbId());
        assertEquals("默认 Agent", agent.getName());
        assertNotNull(agent.getSystemPrompt());
        assertEquals(5, agent.getTopK());
        assertEquals(1.2, agent.getBusinessWeight());
    }

    @Test
    void update_mergesNonNullFieldsOnly() {
        Agent existing = new Agent();
        existing.setId(9L);
        existing.setKbId(1L);
        existing.setName("旧名");
        existing.setSystemPrompt("旧提示词");
        existing.setTopK(5);
        when(repo.findByKbId(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse resp = service.update(1L, "测试库",
                new AgentRequest("新名", null, "新提示词", null, null, 0.3, null, null, null, null, null));
        assertEquals("新名", resp.name());
        assertEquals("新提示词", resp.systemPrompt());
        assertEquals(0.3, resp.verifyThreshold());
        // 未传字段保留原值（update 原地修改同一实体）
        assertEquals(5, resp.topK());
        assertEquals("新名", existing.getName());
    }

    @Test
    void toAgentConfig_fallsBackToDefaultsWhenNoAgent() {
        when(repo.findByKbId(2L)).thenReturn(Optional.empty());

        AgentConfig config = service.toAgentConfig(2L, "测试库", 3, 40);
        assertEquals("默认 Agent", config.name());
        assertEquals(5, config.topK());
        assertEquals(0.7, config.verifyThreshold());
        assertEquals(3, config.maxRetry(), "maxRetry 应回退全局默认");
        assertEquals(40, config.memoryWindow(), "memoryWindow 应回退全局默认");
        assertTrue(config.systemPrompt().contains("测试库"));
    }

    @Test
    void toAgentConfig_readsAgentValues() {
        Agent agent = new Agent();
        agent.setKbId(3L);
        agent.setName("专属 Agent");
        agent.setSystemPrompt("自定义");
        agent.setTopK(8);
        agent.setTopN(3);
        agent.setVerifyThreshold(0.7);
        agent.setMaxRetry(1);
        agent.setMemoryWindow(10);
        when(repo.findByKbId(3L)).thenReturn(Optional.of(agent));

        AgentConfig config = service.toAgentConfig(3L, "测试库", 3, 40);
        assertEquals("专属 Agent", config.name());
        assertEquals(8, config.topK());
        assertEquals(0.7, config.verifyThreshold());
        assertEquals(1, config.maxRetry());
        assertEquals(10, config.memoryWindow());
    }
}
