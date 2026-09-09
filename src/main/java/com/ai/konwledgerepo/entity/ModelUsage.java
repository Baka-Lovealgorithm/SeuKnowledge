package com.ai.konwledgerepo.entity;

/**
 * 模型用途绑定（DB 存储 value() 字符串，保持与旧数据一致）：同一{@link ModelType 调用契约}内的角色槽位。
 * <p>CHAT 契约下的角色（EXTRACT/GENERATE/VERIFY/ROUTER/MEMORY/CHITCHAT/TITLE）可互相顶替，
 * 只是成本/质量差异；{@link #RETRIEVE} 不得再按角色拆分（入库与检索必须同一枚向量模型）。
 */
public enum ModelUsage {

    GENERATE,
    EXTRACT,
    /** 向量检索（入库与召回共用；EMBEDDING 类型的唯一用途） */
    RETRIEVE,
    /** 识图（VISION 类型的唯一用途，与文本不可互相顶替，故识图是类型而非本契约下的用途） */
    VISION,
    /** 重排（交叉编码器精排） */
    RERANK,
    /** 答案自检/事实一致性校验（可单独配置更小的 judge 模型；未配置时回退通用/默认 chat） */
    VERIFY,
    /** 会话标题概括/提炼（原 model_type=TITLE，已并入 CHAT 契约下的用途；解析链见 ModelConfigResolver#resolveTitleConfigId） */
    TITLE,
    /** 意图路由/问题改写（可单独配置更小的路由模型；未配置时回退通用/默认 chat） */
    ROUTER,
    /** 会话记忆摘要/压缩（滚动摘要专用，可配置更便宜的小模型；未配置时回退 ROUTER 模型配置） */
    MEMORY,
    /** 闲聊回复（纯闲聊与混合链路的闲聊片段回复，可配置更便宜的小模型；未配置时回退通用/默认 chat） */
    CHITCHAT;

    public String value() {
        return name();
    }

    public boolean is(String v) {
        return name().equals(v);
    }

    public static ModelUsage of(String v) {
        if (v == null) {
            return null;
        }
        for (ModelUsage s : values()) {
            if (s.name().equals(v)) {
                return s;
            }
        }
        return null;
    }
}
