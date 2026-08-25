package com.ai.konwledgerepo.service.knowledgebase;

import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.config.props.SeuCacheProperties;
import com.ai.konwledgerepo.dto.KbCreateRequest;
import com.ai.konwledgerepo.dto.KbResponse;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    private KnowledgeBaseRepository kbRepo;
    private DocumentRepository docRepo;
    private RedisCacheService cache;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        kbRepo = mock(KnowledgeBaseRepository.class);
        docRepo = mock(DocumentRepository.class);
        cache = mock(RedisCacheService.class);
        service = new KnowledgeBaseService(kbRepo, docRepo, cache, new WorkspaceAccess(kbRepo, docRepo),
                new SeuCacheProperties(300, 600, 600, 300, 300, 60, 60, 600, 86400));
    }

    @Test
    void create_setsDraftStatusAndUser() {
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(docRepo.countByKbId(any())).thenReturn(0L);

        KbResponse resp = service.create(new KbCreateRequest("测试库", "描述"), 1L, 10L);
        assertEquals("测试库", resp.name());
        assertEquals("DRAFT", resp.status());
    }

    @Test
    void updateStatus_invalidStatus_throws() {
        assertThrows(BizException.class, () -> service.updateStatus(1L, "BAD", 10L));
    }

    @Test
    void updateStatus_validStatus() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setWorkspaceId(10L);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KbResponse resp = service.updateStatus(1L, "AVAILABLE", 10L);
        assertEquals("AVAILABLE", resp.status());
    }

    @Test
    void updateStatus_otherWorkspace_throws() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setWorkspaceId(99L);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));

        assertThrows(BizException.class, () -> service.updateStatus(1L, "AVAILABLE", 10L));
    }

    @Test
    void delete_archivesInsteadOfRemoving() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setWorkspaceId(10L);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));
        when(kbRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.delete(1L, 10L);
        assertTrue(kb.getArchived(), "删除应为归档（软删除）");
    }

    @Test
    void getEntity_notFound_throws() {
        when(kbRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.getEntity(99L));
    }
}
