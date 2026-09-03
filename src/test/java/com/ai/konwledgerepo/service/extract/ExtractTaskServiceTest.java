package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.ExtractTaskCreateRequest;
import com.ai.konwledgerepo.dto.ExtractTaskResponse;
import com.ai.konwledgerepo.entity.ExtractTask;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ExtractTaskRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledge.BusinessKnowledgeService;
import com.ai.konwledgerepo.service.knowledge.QaPairService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractTaskServiceTest {

    private static final long WS = 10L;

    private ExtractTaskRepository taskRepository;
    private ExtractTaskExecutor executor;
    private KnowledgeBaseRepository kbRepository;
    private ExtractTaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ExtractTaskRepository.class);
        executor = mock(ExtractTaskExecutor.class);
        kbRepository = mock(KnowledgeBaseRepository.class);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setWorkspaceId(WS);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(kb));
        service = new ExtractTaskService(taskRepository, executor, kbRepository,
                mock(BusinessKnowledgeRepository.class), mock(QaPairRepository.class),
                mock(BusinessKnowledgeService.class), mock(QaPairService.class), new ObjectMapper(),
                new AfterCommitExecutor(),
                new TaskProgressStore(mock(RedisCacheService.class),
                        new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400)),
                new WorkspaceAccess(kbRepository, mock(com.ai.konwledgerepo.repository.DocumentRepository.class),
                        mock(com.ai.konwledgerepo.repository.KbAccessRepository.class),
                        mock(com.ai.konwledgerepo.repository.WorkspaceMemberRepository.class),
                        mock(com.ai.konwledgerepo.repository.GroupMemberRepository.class),
                        mock(com.ai.konwledgerepo.repository.KbGroupRepository.class)));
    }

    private ExtractTask task(long id, String status, String docIdsJson, String failedDocIdsJson) {
        ExtractTask t = new ExtractTask();
        t.setId(id);
        t.setKbId(1L);
        t.setExtractType("BOTH");
        t.setStatus(status);
        t.setDocIds(docIdsJson);
        t.setFailedDocIds(failedDocIdsJson);
        return t;
    }

    @Test
    void retry_usesOnlyFailedDocs() {
        ExtractTask t = task(9L, "FAILED", "[1,2,3]", "[2,3]");
        when(taskRepository.findById(9L)).thenReturn(Optional.of(t));

        ExtractTaskResponse resp = service.retry(9L, WS);

        assertEquals("PENDING", resp.status());
        verify(executor).run(eq(9L), eq(List.of(2L, 3L)));
    }

    @Test
    void retry_fallsBackToAllDocsWhenNoFailedRecord() {
        ExtractTask t = task(9L, "PARTIAL_FAILED", "[1,2,3]", null);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(t));

        service.retry(9L, WS);

        verify(executor).run(eq(9L), eq(List.of(1L, 2L, 3L)));
    }

    @Test
    void retry_rejectsRunningTask() {
        ExtractTask t = task(9L, "RUNNING", "[1,2]", null);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(t));

        BizException ex = assertThrows(BizException.class, () -> service.retry(9L, WS));
        assertEquals("任务正在执行中，无法重试", ex.getMessage());
        verify(executor, never()).run(eq(9L), anyList());
    }

    @Test
    void retry_rejectsSuccessTask() {
        ExtractTask t = task(9L, "SUCCESS", "[1,2]", null);
        when(taskRepository.findById(9L)).thenReturn(Optional.of(t));

        BizException ex = assertThrows(BizException.class, () -> service.retry(9L, WS));
        assertEquals("任务已成功，无需重试", ex.getMessage());
        verify(executor, never()).run(eq(9L), anyList());
    }

    @Test
    void retry_rejectsWhenFailedDocsNotInTask() {
        ExtractTask t = task(9L, "FAILED", "[1,2]", "[99]");
        when(taskRepository.findById(9L)).thenReturn(Optional.of(t));

        BizException ex = assertThrows(BizException.class, () -> service.retry(9L, WS));
        assertEquals("没有可重试的失败文档", ex.getMessage());
        verify(executor, never()).run(eq(9L), anyList());
    }

    @Test
    void retry_crossWorkspaceTask_rejected() {
        // 任务知识库属于空间 99，当前空间 10 无权重试（防跨空间按任务 id 越权）
        ExtractTask t = task(9L, "FAILED", "[1,2]", "[2]");
        KnowledgeBase other = new KnowledgeBase();
        other.setId(1L);
        other.setWorkspaceId(99L);
        when(kbRepository.findById(1L)).thenReturn(Optional.of(other));
        when(taskRepository.findById(9L)).thenReturn(Optional.of(t));

        assertThrows(BizException.class, () -> service.retry(9L, WS));
        verify(executor, never()).run(eq(9L), anyList());
    }

    @Test
    void create_keepsOriginalBehavior() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBase()));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractTaskResponse resp = service.create(
                new ExtractTaskCreateRequest(1L, List.of(5L, 6L), "BUSINESS"), 2L, WS);

        assertEquals("BUSINESS", resp.extractType());
        assertEquals(List.of(5L, 6L), resp.docIds());
        verify(executor).run(any());
    }
}
