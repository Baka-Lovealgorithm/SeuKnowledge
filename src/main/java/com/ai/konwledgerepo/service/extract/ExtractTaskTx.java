package com.ai.konwledgerepo.service.extract;

import com.ai.konwledgerepo.entity.ExtractTask;
import com.ai.konwledgerepo.entity.TaskStatus;
import com.ai.konwledgerepo.repository.ExtractTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抽取任务的事务边界组件（独立 bean，@Transactional 代理生效）。
 * 抽取执行器在任务启动前经此 bean 以悲观锁（SELECT ... FOR UPDATE）
 * 原子抢占 RUNNING 状态，防止同一任务被并发执行。
 * <p>
 * 使用方：{@link ExtractTaskExecutor}。
 */
@Component
public class ExtractTaskTx {

    private static final Logger log = LoggerFactory.getLogger(ExtractTaskTx.class);

    private final ExtractTaskRepository taskRepository;

    public ExtractTaskTx(ExtractTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * 以悲观锁抢占任务执行权：行不存在或已在 RUNNING → 返回 null；
     * 否则置 RUNNING + save 并返回实体（detached，字段齐全）。
     */
    @Transactional
    public ExtractTask startRun(Long taskId) {
        ExtractTask task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            log.debug("startRun 跳过：任务 {} 不存在", taskId);
            return null;
        }
        if (TaskStatus.RUNNING.is(task.getStatus())) {
            log.debug("startRun 跳过：任务 {} 已在执行中", taskId);
            return null;
        }
        task.setStatus(TaskStatus.RUNNING.value());
        taskRepository.save(task);
        log.info("startRun 成功：任务 {} 进入 RUNNING", taskId);
        return task;
    }
}