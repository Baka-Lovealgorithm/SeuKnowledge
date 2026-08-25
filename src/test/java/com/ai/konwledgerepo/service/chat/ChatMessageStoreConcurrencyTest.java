package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.entity.ChatMessage;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.repository.ChatMessageRepository;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话落库并发安全集成测试（真实 MySQL + Redis，不依赖外部 LLM/ES）：
 * <ul>
 *   <li>同一会话并发 {@link ChatMessageStore#persistAnswer}：悲观锁串行化保证 messageCount 无丢失更新、消息顺序严格 USER→ASSISTANT 交替；</li>
 *   <li>跨会话并发：行锁互不阻塞，各自独立落库。</li>
 * </ul>
 * 前置：本机 MySQL(3306) 与 Redis(6379) 可用；测试数据用例内自建、用例后清理。
 */
@SpringBootTest
class ChatMessageStoreConcurrencyTest {

    /** 测试专用用户/空间/库，避免与真实数据混淆（本测试不触知识库归属校验） */
    private static final Long TEST_USER_ID = 999L;
    private static final Long TEST_KB_ID = 1L;
    private static final Long TEST_WS_ID = 1L;

    @Autowired
    private ChatMessageStore messageStore;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> createdSessionIds = new CopyOnWriteArrayList<>();

    /** 清理测试数据：deleteBySessionId/deleteById 为删除操作，需在事务内执行 */
    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (Long sessionId : createdSessionIds) {
                messageRepository.deleteBySessionId(sessionId);
                sessionRepository.deleteById(sessionId);
            }
        });
        createdSessionIds.clear();
    }

    // ===== 用例 =====

    /** 2 线程并发落库同一会话：messageCount 无丢失更新，消息顺序严格交替 */
    @Test
    void twoThreadsSameSession_noLostUpdateAndStrictOrder() throws Exception {
        ChatSession session = newSession();

        runConcurrent(2, idx -> messageStore.persistAnswer(
                session, TEST_USER_ID, "并发问题" + idx, "并发答案" + idx, "[]", TEST_WS_ID));

        ChatSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(4, reloaded.getMessageCount(), "2 次并发问答后 messageCount 应为 4（无丢失更新）");
        assertNotNull(reloaded.getLastMessageAt(), "lastMessageAt 应已更新");

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByIdAsc(session.getId());
        assertEquals(4, messages.size(), "应落库 4 条消息");
        for (int i = 0; i < messages.size(); i++) {
            String expected = (i % 2 == 0) ? "USER" : "ASSISTANT";
            assertEquals(expected, messages.get(i).getRole(),
                    "消息顺序应严格 USER→ASSISTANT 交替（位置 " + i + "）");
        }
    }

    /** 4 线程并发落库同一会话：更激进验证无丢失更新 */
    @Test
    void fourThreadsSameSession_noLostUpdate() throws Exception {
        ChatSession session = newSession();

        runConcurrent(4, idx -> messageStore.persistAnswer(
                session, TEST_USER_ID, "并发问题" + idx, "并发答案" + idx, "[]", TEST_WS_ID));

        ChatSession reloaded = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(8, reloaded.getMessageCount(), "4 次并发问答后 messageCount 应为 8（无丢失更新）");
        assertEquals(8, messageRepository.countBySessionId(session.getId()), "应落库 8 条消息");
    }

    /** 不同会话并发落库：行锁互不阻塞，各自独立正确 */
    @Test
    void concurrentDifferentSessions_remainIndependent() throws Exception {
        ChatSession sessionA = newSession();
        ChatSession sessionB = newSession();

        runConcurrent(2, idx -> {
            ChatSession target = (idx % 2 == 0) ? sessionA : sessionB;
            messageStore.persistAnswer(target, TEST_USER_ID, "问题" + idx, "答案" + idx, "[]", TEST_WS_ID);
        });

        assertEquals(2, sessionRepository.findById(sessionA.getId()).orElseThrow().getMessageCount(),
                "会话 A 应独立累计 2 条");
        assertEquals(2, sessionRepository.findById(sessionB.getId()).orElseThrow().getMessageCount(),
                "会话 B 应独立累计 2 条");
    }

    // ===== 工具 =====

    /** 新建并落库一个空会话（messageCount=0） */
    private ChatSession newSession() {
        ChatSession s = new ChatSession();
        s.setKbId(TEST_KB_ID);
        s.setUserId(TEST_USER_ID);
        s.setTitle("并发测试会话");
        s.setTitleAuto(Boolean.TRUE);
        s.setMessageCount(0);
        s.setLastMessageAt(LocalDateTime.now());
        ChatSession saved = sessionRepository.save(s);
        createdSessionIds.add(saved.getId());
        return saved;
    }

    /** tasks 个线程同时起跑执行 action，等待全部完成；异常汇总抛出 */
    private void runConcurrent(int tasks, ThrowingConsumer<Integer> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int i = 0; i < tasks; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        action.accept(idx);
                    } catch (Throwable t) {
                        failure.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发任务应全部就绪");
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发落库应在超时前完成");
            if (failure.get() != null) {
                throw new AssertionError("并发落库出现异常: " + failure.get(), failure.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
