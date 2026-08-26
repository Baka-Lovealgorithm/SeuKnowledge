package com.ai.konwledgerepo.common;

/**
 * Redis key 统一命名规范：seuknowledge:{域}:{标识}。
 * 所有缓存键必须带 TTL（见 RedisCacheService.set），防止陈旧数据与内存膨胀。
 */
public final class RedisKeys {

    public static final String PREFIX = "seuknowledge:";

    /** 登录 token → userId（String） */
    public static String token(String token) {
        return PREFIX + "token:" + token;
    }

    /** 用户 → 工作空间成员列表快照（List&lt;MemberContext&gt; JSON） */
    public static String memberList(Long userId) {
        return PREFIX + "member:list:" + userId;
    }

    /** 用户成员缓存失效 pattern（角色/归属变更后按模式删除全部空间条目） */
    public static String memberListPattern(Long userId) {
        return PREFIX + "member:list:" + userId + "*";
    }

    /** 工作空间+模型类型+用途 → 解析出的 configId（String） */
    public static String modelResolve(Long workspaceId, String modelType, String usage) {
        return PREFIX + "model:resolve:" + workspaceId + ":" + modelType + ":"
                + (usage == null || usage.isBlank() ? "none" : usage);
    }

    /** 工作空间+模型类型 → 默认 configId（String） */
    public static String modelDefault(Long workspaceId, String modelType) {
        return PREFIX + "model:default:" + workspaceId + ":" + modelType;
    }

    /** 模型配置快照（ModelConfigSnapshot JSON） */
    public static String modelConfig(Long id) {
        return PREFIX + "model:cfg:" + id;
    }

    /** Agent 配置快照（AgentConfig JSON） */
    public static String agent(Long kbId) {
        return PREFIX + "agent:" + kbId;
    }

    /** 知识库快照（KbSnapshot JSON，仅问答热路径字段） */
    public static String kb(Long id) {
        return PREFIX + "kb:" + id;
    }

    /** 工作空间知识库列表（List&lt;KbResponse&gt; JSON） */
    public static String kbList(Long workspaceId) {
        return PREFIX + "kb:list:" + workspaceId;
    }

    /** 知识库文档计数（String） */
    public static String kbCount(Long kbId) {
        return PREFIX + "kb:count:" + kbId;
    }

    /** 用户+工作空间会话列表（List&lt;ChatSessionResponse&gt; JSON） */
    public static String sessionList(Long userId, Long workspaceId) {
        return PREFIX + "sessionlist:" + userId + ":" + workspaceId;
    }

    /** 会话消息列表（List&lt;ChatMessageResponse&gt; JSON） */
    public static String messages(Long sessionId) {
        return PREFIX + "msgs:" + sessionId;
    }

    /** 会话记忆窗口（List&lt;HistoryEntry&gt; JSON） */
    public static String history(Long sessionId) {
        return PREFIX + "history:" + sessionId;
    }

    /** 抽取任务进度 Hash（ExtractTaskExecutor 写入，ExtractTaskService 读取） */
    public static String task(Long taskId) {
        return PREFIX + "task:" + taskId;
    }

    /** 问答限流计数（String，INCR + EXPIRE） */
    public static String rateLimit(Long userId) {
        return PREFIX + "rl:" + userId;
    }

    /** 防重复提交锁（SETNX） */
    public static String dup(Long userId, String hash) {
        return PREFIX + "dup:" + userId + ":" + hash;
    }

    /** 标题生成防重锁（SETNX，TTL 120s，防同一会话并发首问重复生成标题） */
    public static String titleGen(Long sessionId) {
        return PREFIX + "titleGen:" + sessionId;
    }

    /** 文档解析 in-flight guard（文档级互斥；TTL 30min，解析超时后自动失效） */
    public static String docParse(Long docId) {
        return PREFIX + "doc:parse:" + docId;
    }

    /** 抽取任务执行互斥（任务级；TTL 8h，任务最长执行时间） */
    public static String taskRun(Long taskId) {
        return PREFIX + "task:run:" + taskId;
    }

    /** 抽取文档级互斥（同一文档同时仅一个抽取任务处理；TTL 8h） */
    public static String taskExtract(Long docId) {
        return PREFIX + "task:extract:" + docId;
    }

    private RedisKeys() {
    }
}
