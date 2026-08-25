package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.common.AfterCommitExecutor;
import com.ai.konwledgerepo.common.BizException;
import com.ai.konwledgerepo.dto.ExtractTaskCreateRequest;
import com.ai.konwledgerepo.dto.ExtractTaskResponse;
import com.ai.konwledgerepo.dto.ExtractTaskResultResponse;
import com.ai.konwledgerepo.entity.ExtractTask;
import com.ai.konwledgerepo.entity.KnowledgeBase;
import com.ai.konwledgerepo.entity.ReviewStatus;
import com.ai.konwledgerepo.entity.TaskStatus;
import com.ai.konwledgerepo.repository.BusinessKnowledgeRepository;
import com.ai.konwledgerepo.repository.ExtractTaskRepository;
import com.ai.konwledgerepo.repository.KnowledgeBaseRepository;
import com.ai.konwledgerepo.repository.QaPairRepository;
import com.ai.konwledgerepo.service.knowledge.BusinessKnowledgeService;
import com.ai.konwledgerepo.service.knowledge.QaPairService;
import com.ai.konwledgerepo.service.workspace.WorkspaceAccess;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 抽取任务业务：创建并触发异步执行、列表、详情、结果预览。
 * 执行进度由 ExtractTaskExecutor 实时写入 Redis Hash（seuknowledge:task:{id}，TTL 24h），
 * 详情接口优先读 Redis 进度（RUNNING 期间实时），DB 每 N 个文档批量落库作为权威记录。
 */
@Service
public class ExtractTaskService {

    private final ExtractTaskRepository taskRepository;
    private final ExtractTaskExecutor executor;
    private final KnowledgeBaseRepository kbRepository;
    private final BusinessKnowledgeRepository bkRepository;
    private final QaPairRepository qaRepository;
    private final BusinessKnowledgeService bkService;
    private final QaPairService qaService;
    private final ObjectMapper objectMapper;
    private final AfterCommitExecutor afterCommitExecutor;
    private final TaskProgressStore progressStore;
    private final WorkspaceAccess workspaceAccess;

    public ExtractTaskService(ExtractTaskRepository taskRepository,
                              ExtractTaskExecutor executor,
                              KnowledgeBaseRepository kbRepository,
                              BusinessKnowledgeRepository bkRepository,
                              QaPairRepository qaRepository,
                              BusinessKnowledgeService bkService,
                              QaPairService qaService,
                              ObjectMapper objectMapper,
                              AfterCommitExecutor afterCommitExecutor,
                              TaskProgressStore progressStore,
                              WorkspaceAccess workspaceAccess) {
        this.taskRepository = taskRepository;
        this.executor = executor;
        this.kbRepository = kbRepository;
        this.bkRepository = bkRepository;
        this.qaRepository = qaRepository;
        this.bkService = bkService;
        this.qaService = qaService;
        this.objectMapper = objectMapper;
        this.afterCommitExecutor = afterCommitExecutor;
        this.progressStore = progressStore;
        this.workspaceAccess = workspaceAccess;
    }

    @Transactional
    public ExtractTaskResponse create(ExtractTaskCreateRequest request, Long userId, Long workspaceId) {
        workspaceAccess.requireKb(request.kbId(), workspaceId);
        ExtractTask task = new ExtractTask();
        task.setKbId(request.kbId());
        task.setDocIds(toJson(request.docIds()));
        task.setExtractType(request.extractType());
        task.setCreatedBy(userId);
        taskRepository.save(task);
        // 异步执行（独立 bean，@Async 代理生效）：挂到事务提交后，避免异步线程读不到未提交的任务
        afterCommitExecutor.runAfterCommit(() -> executor.run(task.getId()));
        return toResponse(task);
    }

    /** 任务列表（限当前工作空间，按知识库归属过滤） */
    public List<ExtractTaskResponse> list(Long workspaceId) {
        List<Long> kbIds = kbRepository.findByWorkspaceId(workspaceId).stream()
                .map(KnowledgeBase::getId)
                .toList();
        if (kbIds.isEmpty()) {
            return List.of();
        }
        return taskRepository.findByKbIdInOrderByIdDesc(kbIds).stream()
                .map(this::toResponse)
                .toList();
    }

    public ExtractTaskResponse get(Long id, Long workspaceId) {
        ExtractTask task = requireTaskInWorkspace(id, workspaceId);
        Map<Object, Object> progress = progressStore.read(id);
        if (progress.isEmpty()) {
            return toResponse(task);
        }
        return toResponse(task, progress);
    }

    /**
     * 重试失败任务：仅重新抽取上次失败的文档（无失败记录时回退全量），
     * 重抽前自动软删这些文档下旧的 DRAFT 草稿；已审核的记录不受影响。
     */
    @Transactional
    public ExtractTaskResponse retry(Long id, Long workspaceId) {
        ExtractTask task = requireTaskInWorkspace(id, workspaceId);
        if (TaskStatus.RUNNING.is(task.getStatus()) || TaskStatus.PENDING.is(task.getStatus())) {
            throw new BizException("任务正在执行中，无法重试");
        }
        if (TaskStatus.SUCCESS.is(task.getStatus())) {
            throw new BizException("任务已成功，无需重试");
        }
        List<Long> allDocIds = parseDocIds(task.getDocIds());
        List<Long> failedDocIds = parseDocIds(task.getFailedDocIds());
        List<Long> target = failedDocIds.isEmpty()
                ? allDocIds
                : allDocIds.stream().filter(failedDocIds::contains).toList();
        if (target.isEmpty()) {
            throw new BizException("没有可重试的失败文档");
        }
        task.setStatus(TaskStatus.PENDING.value());
        taskRepository.save(task);
        // 异步执行（独立 bean，@Async 代理生效）：挂到事务提交后，避免异步线程读不到未提交的任务
        afterCommitExecutor.runAfterCommit(() -> executor.run(task.getId(), target));
        return toResponse(task);
    }

    /** 结果预览：任务涉及文档抽取出的 DRAFT 草稿（限当前工作空间） */
    public ExtractTaskResultResponse results(Long id, Long workspaceId) {
        ExtractTask task = requireTaskInWorkspace(id, workspaceId);
        List<Long> docIds = parseDocIds(task.getDocIds());
        return new ExtractTaskResultResponse(
                bkRepository.findBySourceDocIdInAndDeletedFalseAndStatus(docIds, ReviewStatus.DRAFT.value()).stream()
                        .map(bkService::toResponse)
                        .toList(),
                qaRepository.findBySourceDocIdInAndDeletedFalseAndStatus(docIds, ReviewStatus.DRAFT.value()).stream()
                        .map(qaService::toResponse)
                        .toList());
    }

    private ExtractTask getEntity(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new BizException("抽取任务不存在"));
    }

    /** 校验任务存在且其知识库属于当前工作空间（防跨空间按任务 id 越权） */
    private ExtractTask requireTaskInWorkspace(Long id, Long workspaceId) {
        ExtractTask task = getEntity(id);
        workspaceAccess.requireKb(task.getKbId(), workspaceId);
        return task;
    }

    private ExtractTaskResponse toResponse(ExtractTask task) {
        return new ExtractTaskResponse(
                task.getId(), task.getKbId(), parseDocIds(task.getDocIds()), task.getExtractType(),
                task.getStatus(), task.getTotalDocs(), task.getProcessedDocs(), task.getProgress(),
                task.getResultSummary(), task.getErrorLog(), parseDocIds(task.getFailedDocIds()),
                task.getDurationMs(), task.getTokenInput(), task.getTokenOutput(), task.getTokenTotal(),
                task.getCreatedAt(), task.getFinishedAt());
    }

    /** 合并 Redis 实时进度生成响应：Hash 中存在的字段覆盖 DB 值（DB 为权威，缺省回退） */
    private ExtractTaskResponse toResponse(ExtractTask task, Map<Object, Object> progress) {
        String failedJson = str(progress.get(TaskProgressStore.H_FAILED), task.getFailedDocIds());
        return new ExtractTaskResponse(
                task.getId(), task.getKbId(), parseDocIds(task.getDocIds()), task.getExtractType(),
                str(progress.get(TaskProgressStore.H_STATUS), task.getStatus()),
                intOf(progress.get(TaskProgressStore.H_TOTAL), task.getTotalDocs()),
                intOf(progress.get(TaskProgressStore.H_PROCESSED), task.getProcessedDocs()),
                intOf(progress.get(TaskProgressStore.H_PROGRESS), task.getProgress()),
                str(progress.get(TaskProgressStore.H_RESULT), task.getResultSummary()),
                str(progress.get(TaskProgressStore.H_ERROR), task.getErrorLog()),
                parseDocIds(failedJson),
                longOf(progress.get(TaskProgressStore.H_DURATION), task.getDurationMs()),
                longOf(progress.get(TaskProgressStore.H_TOKEN_IN), task.getTokenInput()),
                longOf(progress.get(TaskProgressStore.H_TOKEN_OUT), task.getTokenOutput()),
                longOf(progress.get(TaskProgressStore.H_TOKEN_TOTAL), task.getTokenTotal()),
                task.getCreatedAt(),
                parseTime(progress.get(TaskProgressStore.H_FINISHED), task.getFinishedAt()));
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intOf(Object value, Integer fallback) {
        if (value == null) {
            return fallback == null ? 0 : fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback == null ? 0 : fallback;
        }
    }

    private static Long longOf(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDateTime parseTime(Object value, LocalDateTime fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private List<Long> parseDocIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<Long> docIds) {
        try {
            return objectMapper.writeValueAsString(docIds);
        } catch (Exception e) {
            throw new BizException("文档参数序列化失败");
        }
    }
}
