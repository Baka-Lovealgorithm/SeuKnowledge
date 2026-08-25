package com.ai.konwledgerepo.common;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后执行器：在事务提交后执行动作；无活动事务时立即执行。
 * 收敛 DocumentService / ExtractTaskService 内重复的 runAfterCommit 实现，
 * 用于触发 @Async 后台任务，确保异步线程能读到已提交的数据。
 */
@Component
public class AfterCommitExecutor {

    public void runAfterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
