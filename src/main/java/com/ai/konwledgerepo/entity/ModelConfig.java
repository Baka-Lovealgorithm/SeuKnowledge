package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 模型配置。支持多供应商（DashScope / OpenAI 兼容）、多类型（CHAT / EMBEDDING）。
 */
@Entity
@Table(name = "kb_model_config")
public class ModelConfig extends BaseEntity {

    @Column(nullable = false, length = 128)
    private String name;

    /** 供应商：DASHSCOPE / OPENAI_COMPAT */
    @Column(nullable = false, length = 32)
    private String provider;

    /** 模型类型：CHAT / EMBEDDING / VISION（多模态识图） */
    @Column(name = "model_type", nullable = false, length = 32)
    private String modelType;

    /** 按用途绑定：EXTRACT（抽取）/ GENERATE（生成）/ RETRIEVE（检索）/ VISION（识图）；null 表示通用 */
    @Column(name = "usage_type", length = 32)
    private String usage;

    /** 模型名：如 qwen-plus / text-embedding-v3 / deepseek-chat */
    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "api_key", length = 255)
    private String apiKey;

    /** OpenAI 兼容端点必填；DashScope 可空 */
    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @Column(precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** 是否默认（同类型默认实例） */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 归属工作空间（多工作空间按空间隔离；存量数据初始化时回填默认空间） */
    @Column(name = "workspace_id")
    private Long workspaceId;

    /** 是否关闭思考（thinking/reasoning）：deepseek 系带隐藏 reasoning token，占满 max_tokens 会「触顶即空输出」，judge 调用需关闭 */
    @Column(name = "disable_thinking")
    private Boolean disableThinking = false;

    /** 关闭思考时传给模型的参数模板（JSON）：如 {"thinking":{"type":"disabled"}} 或 {"enable_thinking":false}；空用默认 deepseek 格式 */
    @Column(name = "thinking_params", length = 500)
    private String thinkingParams;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public String getUsage() {
        return usage;
    }

    public void setUsage(String usage) {
        this.usage = usage;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Boolean getDisableThinking() {
        return disableThinking;
    }

    public void setDisableThinking(Boolean disableThinking) {
        this.disableThinking = disableThinking;
    }

    public String getThinkingParams() {
        return thinkingParams;
    }

    public void setThinkingParams(String thinkingParams) {
        this.thinkingParams = thinkingParams;
    }
}
