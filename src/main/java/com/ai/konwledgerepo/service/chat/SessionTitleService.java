package com.ai.konwledgerepo.service.chat;

import com.ai.konwledgerepo.common.RedisCacheService;
import com.ai.konwledgerepo.common.RedisKeys;
import com.ai.konwledgerepo.entity.ChatSession;
import com.ai.konwledgerepo.repository.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 会话标题异步生成服务：判定 → SETNX 防重 → 异步生成 → 条件补写。
 * <p>
 * 标题生成从落库事务中彻底剥离：同步/流式路径统一在链路外侧提交标题任务，
 * 落库不再碰标题；任务完成后独立短事务补写，条件更新（WHERE title_auto=1）原子防覆盖手动 rename。
 * <p>
 * Redis 不可用时 fail-closed 跳过标题生成（列表浏览 backfillDefaultTitles 已有截断兜底）。
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);
    private static final Duration GEN_TTL = Duration.ofSeconds(120);

    private final ChatSessionRepository sessionRepository;
    private final ChatSessionService sessionService;
    private final SessionTitleGenerator titleGenerator;
    private final RedisCacheService redisCacheService;
    private final Executor titleExecutor;

    public SessionTitleService(ChatSessionRepository sessionRepository,
                               ChatSessionService sessionService,
                               SessionTitleGenerator titleGenerator,
                               RedisCacheService redisCacheService,
                               @Qualifier("applicationTaskExecutor") Executor titleExecutor) {
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
        this.titleGenerator = titleGenerator;
        this.redisCacheService = redisCacheService;
        this.titleExecutor = titleExecutor;
    }

    /**
     * 提交自动标题任务：判定 → SETNX 防重 → 异步生成 → 条件补写。
     * 调用方在链路外侧（问前）调用，不阻塞链路。
     * 注意：session 为快照，应取锁前实体——首次提问时 titleAuto=true 且标题默认。
     */
    public void submitAutoTitle(ChatSession session, String question, Long workspaceId) {
        if (!sessionService.needAutoTitle(session)) {
            return;
        }
        Boolean ok = redisCacheService.setIfAbsent(RedisKeys.titleGen(session.getId()), "1", GEN_TTL);
        if (ok == null || !ok) {
            return; // Redis down 或已提交过
        }
        Long sessionId = session.getId();
        Long userId = session.getUserId();
        CompletableFuture.runAsync(() -> {
            try {
                String title = titleGenerator.generate(question, workspaceId);
                applyAutoTitle(sessionId, title, userId, workspaceId);
            } catch (Exception e) {
                log.warn("标题生成失败 sessionId={}: {}", sessionId, e.getMessage());
            } finally {
                redisCacheService.delete(RedisKeys.titleGen(sessionId));
            }
        }, titleExecutor);
    }

    @Transactional
    public void applyAutoTitle(Long sessionId, String title, Long userId, Long workspaceId) {
        int updated = sessionRepository.updateTitleIfAuto(sessionId, title);
        if (updated > 0) {
            sessionService.evictSessionList(userId, workspaceId);
            log.debug("标题补写成功 sessionId={} title={}", sessionId, title);
        } else {
            log.debug("标题补写跳过（已手动重命名） sessionId={}", sessionId);
        }
    }
}