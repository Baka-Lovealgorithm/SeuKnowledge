package com.ai.konwledgerepo.service.modelconfig;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.dto.ModelConfigRequest;
import com.ai.konwledgerepo.dto.ModelConfigResponse;
import com.ai.konwledgerepo.dto.ModelConfigTestResponse;
import com.ai.konwledgerepo.entity.ModelConfig;
import com.ai.konwledgerepo.model.ModelFactory;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.ModelConfigRepository;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置服务测试（多工作空间）：按空间 CRUD、跨空间按 id 越权拒绝、
 * 默认互斥限定空间内。
 */
class ModelConfigServiceTest {

    private static final long WS = 10L;

    private ModelConfigRepository repo;
    private ModelFactory factory;
    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        repo = mock(ModelConfigRepository.class);
        factory = mock(ModelFactory.class);
        // WorkspaceAccess 用真实实例（requireBelongs 不触库），跨空间越权用例走真实归属校验
        service = new ModelConfigService(repo, factory,
                new WorkspaceAccess(mock(KnowledgeBaseRepository.class), mock(DocumentRepository.class),
                        mock(com.ai.konwledgerepo.repository.KbAccessRepository.class),
                        mock(com.ai.konwledgerepo.repository.WorkspaceMemberRepository.class),
                        mock(com.ai.konwledgerepo.repository.GroupMemberRepository.class),
                        mock(com.ai.konwledgerepo.repository.KbGroupRepository.class)));
    }

    private ModelConfig cfg(long id, long workspaceId) {
        ModelConfig c = new ModelConfig();
        c.setId(id);
        c.setName("m" + id);
        c.setProvider("DASHSCOPE");
        c.setModelType("CHAT");
        c.setModelName("qwen-plus");
        c.setApiKey("env:alibaba_api_key");
        c.setWorkspaceId(workspaceId);
        c.setEnabled(true);
        c.setIsDefault(false);
        return c;
    }

    private ModelConfigRequest req(String name, String usage) {
        return new ModelConfigRequest(name, "DASHSCOPE", "CHAT", usage, "qwen-plus",
                "env:alibaba_api_key", null, BigDecimal.valueOf(0.7), 2048, false, true, false, null);
    }

    @Test
    void list_filtersByWorkspace() {
        when(repo.findByWorkspaceIdOrderByIdDesc(WS)).thenReturn(List.of(cfg(1L, WS)));
        List<ModelConfigResponse> list = service.list(WS);
        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).id());
        verify(repo).findByWorkspaceIdOrderByIdDesc(WS);
    }

    @Test
    void create_setsWorkspaceId() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ModelConfigResponse resp = service.create(req("测试模型", "GENERATE"), WS);
        assertEquals("测试模型", resp.name());
        verify(repo).save(any());
        verify(factory).evictCache();
    }

    @Test
    void create_verifyUsage_succeeds() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ModelConfigResponse resp = service.create(req("校验模型", "VERIFY"), WS);
        assertEquals("VERIFY", resp.usage());
        verify(repo).save(any());
    }

    @Test
    void create_rejectsUsageUnsupportedByType() {
        ModelConfigRequest bad = new ModelConfigRequest("x", "DASHSCOPE", "EMBEDDING", "GENERATE", "m",
                "env:test_key", null, null, null, false, true, false, null);
        assertThrows(BizException.class, () -> service.create(bad, WS));
        verify(repo, never()).save(any());
    }

    @Test
    void create_titleUsage_succeeds() {
        ModelConfigRequest titleReq = new ModelConfigRequest("标题模型", "DASHSCOPE", "TITLE", "TITLE",
                "qwen-turbo", "env:test_key", null, BigDecimal.valueOf(0.7), 2048, false, true, false, null);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ModelConfigResponse resp = service.create(titleReq, WS);
        assertEquals("TITLE", resp.modelType());
        assertEquals("TITLE", resp.usage());
        verify(repo).save(any());
    }

    @Test
    void create_routerUsage_succeeds() {
        ModelConfigRequest routerReq = new ModelConfigRequest("路由模型", "DASHSCOPE", "CHAT", "ROUTER",
                "qwen-turbo", "env:test_key", null, BigDecimal.valueOf(0.7), 2048, false, true, false, null);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ModelConfigResponse resp = service.create(routerReq, WS);
        assertEquals("CHAT", resp.modelType());
        assertEquals("ROUTER", resp.usage());
        verify(repo).save(any());
    }

    @Test
    void create_rejectsBlankApiKey() {
        ModelConfigRequest bad = new ModelConfigRequest("x", "DASHSCOPE", "CHAT", null, "m",
                "  ", null, null, null, false, true, false, null);
        assertThrows(BizException.class, () -> service.create(bad, WS));
        verify(repo, never()).save(any());
    }

    @Test
    void update_crossWorkspace_rejected() {
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, 99L)));
        assertThrows(BizException.class, () -> service.update(1L, req("改名", null), WS));
        verify(repo, never()).save(any());
    }

    @Test
    void delete_crossWorkspace_rejected() {
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, 99L)));
        assertThrows(BizException.class, () -> service.delete(1L, WS));
        verify(repo, never()).deleteById(any());
    }

    @Test
    void test_crossWorkspace_rejected() {
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, 99L)));
        assertThrows(BizException.class, () -> service.test(1L, WS));
    }

    @Test
    void test_returnsSuccessWhenConnectionOk() {
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, WS)));
        when(factory.testConnection(any())).thenReturn(true);
        ModelConfigTestResponse resp = service.test(1L, WS);
        assertEquals(true, resp.success());
        assertEquals("连接成功", resp.message());
    }

    @Test
    void test_returnsFailureWhenConnectionFails() {
        when(repo.findById(1L)).thenReturn(Optional.of(cfg(1L, WS)));
        when(factory.testConnection(any())).thenReturn(false);
        ModelConfigTestResponse resp = service.test(1L, WS);
        assertEquals(false, resp.success());
        assertEquals("连接失败，请检查 apiKey / baseUrl / 网络", resp.message());
    }

    @Test
    void update_clearsDefaultOnlyWithinWorkspace() {
        ModelConfig inWs = cfg(1L, WS);
        inWs.setIsDefault(true);
        when(repo.findById(1L)).thenReturn(Optional.of(inWs));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 同空间还有另一条默认，将被清除；其它空间的默认不受影响（clearDefault 仅查当前空间）
        when(repo.findByWorkspaceIdAndModelTypeAndEnabledTrueOrderByIdAsc(WS, "CHAT"))
                .thenReturn(List.of(inWs, cfg(3L, WS)));
        ModelConfigRequest defaultReq = new ModelConfigRequest("改默认", "DASHSCOPE", "CHAT", null,
                "qwen-plus", "env:alibaba_api_key", null, BigDecimal.valueOf(0.7), 2048, true, true, false, null);

        service.update(1L, defaultReq, WS);

        verify(repo).save(any());
        verify(repo).findByWorkspaceIdAndModelTypeAndEnabledTrueOrderByIdAsc(eq(WS), eq("CHAT"));
    }
}
