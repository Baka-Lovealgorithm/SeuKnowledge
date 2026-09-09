package com.ai.konwledgerepo.entity;

/**
 * 模型类型（DB 存储 value() 字符串，保持与旧数据一致）。
 *
 * <h2>类型 vs 用途的划分判据</h2>
 * <b>类型 = 调用契约（不可互相顶替）</b>：会被顶替弄「错」的才独立成类型——
 * 文本与识图虽同走 chat 端点，但把图片发给纯文本模型是功能性错误（且 {@code VisionOcrService#isAvailable}
 * 会误报已配置）；向量模型换一枚等于历史向量全废（维度/语义不兼容）；重排走 {@code /rerank} 而非 chat。
 * <br>
 * <b>用途（{@link ModelUsage}）= 同一契约内的角色槽位（可互相顶替）</b>：顶替的后果只是「更贵/更差」，
 * 如生成用大模型、自检/路由/记忆/闲聊/标题用小模型。
 *
 * <h2>为什么 VISION 不是 CHAT 的一个用途</h2>
 * 解析链第 2 档是「同类型通用行」（{@code ModelConfigResolver#resolveConfigId}）。若识图并入 CHAT，
 * 一条 CHAT 通用配置就会被当作识图模型顶上——今天正是靠类型隔离挡住这一点，故保留独立类型。
 */
public enum ModelType {

    /** 文本对话（问答生成/自检/路由/改写/记忆摘要/闲聊/抽取/标题等角色共用此契约） */
    CHAT,
    /** 向量模型：入库与检索必须同一枚，故其用途（RETRIEVE）禁止再按角色拆分 */
    EMBEDDING,
    /** 识图模型（多模态 chat 契约：PDF/PPTX 图片页与扫描页转写） */
    VISION,
    /** 重排模型（交叉编码器精排，如 gte-rerank-v2） */
    RERANK,
    /**
     * 历史类型：会话标题已并入 {@link #CHAT} 的 {@link ModelUsage#TITLE} 用途。
     * 保留常量仅为兼容存量 {@code model_type='TITLE'} 行（API 白名单与配置页均不再产出该值，
     * 解析链见 {@code ModelConfigResolver#resolveTitleConfigId}，编辑保存即自愈）。
     */
    @Deprecated
    TITLE;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static ModelType of(String v) {
        if (v == null) {
            return null;
        }
        for (ModelType s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 模型类型与用途绑定是否合法（迁移自 ModelConfigService.validateUsageType）：
     * CHAT → EXTRACT/GENERATE/VERIFY/ROUTER/MEMORY/CHITCHAT/TITLE；EMBEDDING → RETRIEVE；
     * VISION → VISION；RERANK → RERANK；TITLE（历史类型）→ TITLE。用途为空（通用）恒合法。
     * <p>注：EMBEDDING/VISION/RERANK 的合法用途与类型同名且唯一——配置页对它们不显示用途下拉，
     * 向量用途（RETRIEVE）尤其不得再按角色拆分（入库与检索必须同一枚模型）。
     */
    public boolean validUsage(String usage) {
        if (usage == null || usage.isBlank()) {
            return true;
        }
        return switch (this) {
            case CHAT -> ModelUsage.EXTRACT.is(usage) || ModelUsage.GENERATE.is(usage) || ModelUsage.VERIFY.is(usage)
                    || ModelUsage.ROUTER.is(usage) || ModelUsage.MEMORY.is(usage) || ModelUsage.CHITCHAT.is(usage)
                    || ModelUsage.TITLE.is(usage);
            case EMBEDDING -> ModelUsage.RETRIEVE.is(usage);
            case VISION -> ModelUsage.VISION.is(usage);
            case RERANK -> ModelUsage.RERANK.is(usage);
            case TITLE -> ModelUsage.TITLE.is(usage);
        };
    }
}
