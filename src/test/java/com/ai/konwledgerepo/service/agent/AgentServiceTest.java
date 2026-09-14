package com.ai.konwledgerepo.service.agent;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.config.props.SeuQaProperties;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    /** messageWindow=20（近窗上限 10 轮）；全局兜底：maxRetry=3 / recentRounds=5 / interval=4 */
    private static final SeuQaProperties QA_PROPS =
            new SeuQaProperties(20, 5, 4, 3, 32, 30, true, false, false, 0.4, false, 60, 200);

    private AgentRepository repo;
    private RedisCacheService cache;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repo = mock(AgentRepository.class);
        cache = mock(RedisCacheService.class);
        service = new AgentService(repo, cache,
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400), QA_PROPS);
    }

    @Test
    void getOrCreate_createsDefaultWhenMissing() {
        when(repo.findByKbId(1L)).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Agent agent = service.getOrCreate(1L, "测试库");
        assertEquals(1L, agent.getKbId());
        assertEquals("默认 Agent", agent.getName());
        assertNotNull(agent.getSystemPrompt());
        assertEquals(5, agent.getRecentRounds(), "新建 Agent 取全局默认近窗轮数");
        assertEquals(4, agent.getSummaryIntervalRounds(), "新建 Agent 取全局默认压缩间隔");
    }

    @Test
    void update_mergesNonNullFieldsOnly() {
        Agent existing = new Agent();
        existing.setId(9L);
        existing.setKbId(1L);
        existing.setName("旧名");
        existing.setSystemPrompt("旧提示词");
        when(repo.findByKbId(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentResponse resp = service.update(1L, "测试库",
                new AgentRequest("新名", null, "新提示词", 0.3, null, null, null));
        assertEquals("新名", resp.name());
        assertEquals("新提示词", resp.systemPrompt());
        assertEquals(0.3, resp.verifyThreshold());
        assertEquals("新名", existing.getName());
    }

    // ===== 记忆策略大小关系校验（防空洞） =====

    @Test
    void update_rejectsIntervalGreaterThanRecentRounds() {
        Agent existing = new Agent();
        existing.setKbId(1L);
        when(repo.findByKbId(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BizException e = assertThrows(BizException.class, () -> service.update(1L, "测试库",
                new AgentRequest(null, null, null, null, null, 2, 5)),
                "间隔 5 轮 > 近窗 2 轮 应被拒绝");
        assertTrue(e.getMessage().contains("压缩间隔"), e.getMessage());
    }

    @Test
    void update_rejectsRecentRoundsOverWindowLimit() {
        Agent existing = new Agent();
        existing.setKbId(1L);
        when(repo.findByKbId(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BizException e = assertThrows(BizException.class, () -> service.update(1L, "测试库",
                new AgentRequest(null, null, null, null, null, 11, 1)),
                "近窗 11 轮 > messageWindow/2 = 10 应被拒绝");
        assertTrue(e.getMessage().contains("最近对话轮数最多"), e.getMessage());
    }

    @Test
    void update_partiallyProvidedFields_stillValidateEffectivePair() {
        Agent existing = new Agent();
        existing.setKbId(1L);
        existing.setRecentRounds(2);
        existing.setSummaryIntervalRounds(2);
        when(repo.findByKbId(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 只改近窗为 1（间隔沿用原值 2）→ 生效组合 2 > 1，应被拒绝
        BizException e = assertThrows(BizException.class, () -> service.update(1L, "测试库",
                new AgentRequest(null, null, null, null, null, 1, null)));
        assertTrue(e.getMessage().contains("压缩间隔"), e.getMessage());
    }

    // ===== 快照解析与兜底 =====

    @Test
    void toAgentConfig_fallsBackToDefaultsWhenNoAgent() {
        when(repo.findByKbId(2L)).thenReturn(Optional.empty());

        AgentConfig config = service.toAgentConfig(2L, "测试库");
        assertEquals("默认 Agent", config.name());
        assertEquals(0.7, config.verifyThreshold());
        assertEquals(3, config.maxRetry(), "maxRetry 应回退全局默认");
        assertEquals(5, config.recentRounds(), "近窗轮数应回退全局默认");
        assertEquals(4, config.summaryIntervalRounds(), "压缩间隔应回退全局默认");
        assertTrue(config.systemPrompt().contains("测试库"));
    }

    @Test
    void toAgentConfig_readsAgentValues() {
        Agent agent = new Agent();
        agent.setKbId(3L);
        agent.setName("专属 Agent");
        agent.setSystemPrompt("自定义");
        agent.setVerifyThreshold(0.7);
        agent.setMaxRetry(1);
        agent.setRecentRounds(6);
        agent.setSummaryIntervalRounds(2);
        when(repo.findByKbId(3L)).thenReturn(Optional.of(agent));

        AgentConfig config = service.toAgentConfig(3L, "测试库");
        assertEquals("专属 Agent", config.name());
        assertEquals(0.7, config.verifyThreshold());
        assertEquals(1, config.maxRetry());
        assertEquals(6, config.recentRounds());
        assertEquals(2, config.summaryIntervalRounds());
    }

    @Test
    void getResponse_returnsEffectiveValuesForLegacyRow() {
        Agent legacy = new Agent();
        legacy.setId(5L);
        legacy.setKbId(5L);
        legacy.setName("存量 Agent");
        legacy.setRecentRounds(null);            // 新列在存量行为 null
        legacy.setSummaryIntervalRounds(null);
        when(repo.findByKbId(5L)).thenReturn(Optional.of(legacy));

        AgentResponse resp = service.getResponse(5L, "测试库");
        assertEquals(5, resp.recentRounds(), "null 应回显全局默认（而非 null 让前端自兜底）");
        assertEquals(4, resp.summaryIntervalRounds());
        assertEquals(2, resp.maxRetry(), "实体字段自带默认值 2（非 null，不走全局兜底 3）");
    }

    @Test
    void toAgentConfig_clampsDirtyRowToInvariant() {
        Agent agent = new Agent();
        agent.setKbId(4L);
        agent.setRecentRounds(20);           // 旧「条数」语义的脏值
        agent.setSummaryIntervalRounds(20);  // 且间隔 > 上限
        when(repo.findByKbId(4L)).thenReturn(Optional.of(agent));

        AgentConfig config = service.toAgentConfig(4L, "测试库");
        assertEquals(10, config.recentRounds(), "近窗收敛到 messageWindow/2");
        assertEquals(10, config.summaryIntervalRounds(), "间隔不得大于近窗");
    }
}
