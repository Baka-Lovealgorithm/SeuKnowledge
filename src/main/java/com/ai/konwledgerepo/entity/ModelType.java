package com.ai.konwledgerepo.entity;

/**
 * 模型类型（DB 存储 value() 字符串，保持与旧数据一致）。
 */
public enum ModelType {

    CHAT,
    EMBEDDING,
    VISION,
    /** 重排模型（交叉编码器精排，如 gte-rerank-v2） */
    RERANK,
    /** 标题模型（会话标题概括/提炼，优先于 CHAT GENERATE 使用） */
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
     * CHAT → EXTRACT/GENERATE/VERIFY/ROUTER；EMBEDDING → RETRIEVE；VISION → VISION；RERANK → RERANK；TITLE → TITLE。用途为空（通用）恒合法。
     */
    public boolean validUsage(String usage) {
        if (usage == null || usage.isBlank()) {
            return true;
        }
        return switch (this) {
            case CHAT -> ModelUsage.EXTRACT.is(usage) || ModelUsage.GENERATE.is(usage) || ModelUsage.VERIFY.is(usage)
                    || ModelUsage.ROUTER.is(usage);
            case EMBEDDING -> ModelUsage.RETRIEVE.is(usage);
            case VISION -> ModelUsage.VISION.is(usage);
            case RERANK -> ModelUsage.RERANK.is(usage);
            case TITLE -> ModelUsage.TITLE.is(usage);
        };
    }
}
