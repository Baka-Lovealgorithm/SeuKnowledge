package com.ai.konwledgerepo.service.knowledge;

import com.ai.konwledgerepo.dto.BusinessKnowledgeRequest;
import com.ai.konwledgerepo.dto.BusinessKnowledgeResponse;
import com.ai.konwledgerepo.entity.BusinessKnowledge;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.DocumentRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.service.knowledgebase.KnowledgeBaseService;
import com.ai.konwledgerepo.service.vector.SourceIndexer;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessKnowledgeServiceTest {

    private BusinessKnowledgeRepository repo;
    private KnowledgeBaseRepository kbRepo;
    private KnowledgeBaseService kbService;
    private TransactionOperations txOps;
    private BusinessKnowledgeService service;

    /** 测试用工作空间 id（WorkspaceAccess 为真实实例，归属校验真实生效） */
    private static final long WS_ID = 9L;

    @BeforeEach
    void setUp() {
        repo = mock(BusinessKnowledgeRepository.class);
        kbRepo = mock(KnowledgeBaseRepository.class);
        kbService = mock(KnowledgeBaseService.class);
        DocumentRepository docRepo = mock(DocumentRepository.class);
        SourceIndexer sourceIndexer = mock(SourceIndexer.class);
        txOps = mock(TransactionOperations.class);
        // TransactionOperations.execute 内联执行 callback（模拟真实事务行为）
        when(txOps.execute(any())).thenAnswer(inv -> {
            var callback = inv.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        // WorkspaceAccess 用真实实例
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setWorkspaceId(WS_ID);
        when(kbRepo.findById(1L)).thenReturn(Optional.of(kb));
        when(kbService.getEntity(1L)).thenReturn(kb);
        service = new BusinessKnowledgeService(repo, kbService, docRepo, sourceIndexer,
                new WorkspaceAccess(kbRepo, docRepo,
                        mock(com.ai.konwledgerepo.repository.KbAccessRepository.class),
                        mock(com.ai.konwledgerepo.repository.WorkspaceMemberRepository.class)),
                new ObjectMapper(), txOps);
    }

    private BusinessKnowledgeRequest req() {
        return new BusinessKnowledgeRequest("年度调休", List.of("调休"), "定义", "全体员工", null, null, null);
    }

    @Test
    void create_setsDraftAndVersionOne() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BusinessKnowledgeResponse resp = service.create(1L, req());
        assertEquals(1, resp.version());
        assertEquals("DRAFT", resp.status());
    }

    @Test
    void createDraft_softDeletesExistingSameTermDraft() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // 已存在同名未删 DRAFT：应被软删替换，避免重复草稿堆积
        BusinessKnowledge old = new BusinessKnowledge();
        old.setId(100L);
        old.setKbId(1L);
        old.setTerm("年度调休");
        old.setStatus("DRAFT");
        when(repo.findByKbIdAndTermAndDeletedFalseAndStatus(1L, "年度调休", "DRAFT"))
                .thenReturn(List.of(old));

        BusinessKnowledgeResponse resp = service.createDraft(1L, req());
        assertEquals("DRAFT", resp.status());
        assertTrue(old.getDeleted(), "旧同名 DRAFT 应被软删替换");
    }

    @Test
    void createDraft_noDraftMatch_createsNormally() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        // 已审核同名记录不在 DRAFT 查询内，不应被软删
        when(repo.findByKbIdAndTermAndDeletedFalseAndStatus(1L, "年度调休", "DRAFT"))
                .thenReturn(List.of());

        BusinessKnowledgeResponse resp = service.createDraft(1L, req());
        assertEquals("DRAFT", resp.status());
        assertEquals(1, resp.version());
    }

    @Test
    void update_retiresOldAndCreatesNewVersion() {
        BusinessKnowledge existing = new BusinessKnowledge();
        existing.setId(1L);
        existing.setKbId(1L);
        existing.setHistoryGroupId("g1");
        existing.setVersion(1);
        existing.setDeleted(false);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BusinessKnowledgeResponse resp = service.update(1L, req(), WS_ID);
        assertEquals(2, resp.version(), "编辑应生成 version+1 的新版本");
        assertEquals("DRAFT", resp.status(), "编辑后重新进入草稿待审核");
        assertTrue(existing.getDeleted(), "旧版本应软删保留");
    }

    @Test
    void rollback_activatesTargetVersion() {
        BusinessKnowledge current = new BusinessKnowledge();
        current.setId(2L);
        current.setKbId(1L);
        current.setHistoryGroupId("g1");
        current.setVersion(2);
        current.setDeleted(false);

        BusinessKnowledge target = new BusinessKnowledge();
        target.setId(1L);
        target.setKbId(1L);
        target.setHistoryGroupId("g1");
        target.setVersion(1);
        target.setDeleted(true);

        when(repo.findById(2L)).thenReturn(Optional.of(current));
        when(repo.findByHistoryGroupIdOrderByVersionDesc("g1")).thenReturn(List.of(current, target));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        BusinessKnowledgeResponse resp = service.rollback(2L, 1, WS_ID);
        assertEquals(1, resp.version(), "回退后返回目标版本");
        assertTrue(current.getDeleted(), "回退时当前版本软删");
        assertFalse(target.getDeleted(), "回退时目标版本重新激活");
    }

    @Test
    void approve_setsStatus() {
        BusinessKnowledge bk = new BusinessKnowledge();
        bk.setId(1L);
        bk.setKbId(1L);
        bk.setDeleted(false);
        when(repo.findById(1L)).thenReturn(Optional.of(bk));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("APPROVED", service.approve(1L, WS_ID).status());
    }

    @Test
    void getActive_softDeleted_throws() {
        BusinessKnowledge deleted = new BusinessKnowledge();
        deleted.setId(1L);
        deleted.setDeleted(true);
        when(repo.findById(1L)).thenReturn(Optional.of(deleted));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ai.konwledgerepo.common.BizException.class, () -> service.approve(1L, WS_ID));
    }

    @Test
    void createDraft_duplicateKey_retriesOnce() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        when(repo.findByKbIdAndTermAndDeletedFalseAndStatus(1L, "年度调休", "DRAFT"))
                .thenReturn(List.of()); // 首次查无 DRAFT

        // 首次 saveAndFlush 抛 DuplicateKey（并发插入），第二次成功
        when(repo.saveAndFlush(any())).thenThrow(new DuplicateKeyException("dup"))
                .thenAnswer(inv -> inv.getArgument(0));

        BusinessKnowledgeResponse resp = service.createDraft(1L, req());
        assertEquals("DRAFT", resp.status());
        // 两次 execute → dedup 查询两次 + saveAndFlush 两次（1抛+1成功）
        verify(repo, times(2)).findByKbIdAndTermAndDeletedFalseAndStatus(1L, "年度调休", "DRAFT");
        verify(repo, times(2)).saveAndFlush(any());
    }
}
