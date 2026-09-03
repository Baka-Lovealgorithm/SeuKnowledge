package com.ai.konwledgerepo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 提示词模板（平台级配置）：问答链路节点 / 抽取器 / 服务的内联提示词统一入库管理。
 * 启动时以 classpath:prompts/*.txt 幂等播种（key+scope 已存在则跳过），
 * 运行期以本表为真相源（Redis 缓存加速），classpath 文件退化为默认值与兜底。
 * <p>scope_type 预留多团队扩展：当前仅实现 PLATFORM（全局一套）；
 * 将来多团队可加 WORKSPACE / KB 层覆盖，解析链「平台→空间→知识库」逐级回退，无需改表。
 */
@Entity
@Table(name = "kb_prompt_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_prompt_key_scope", columnNames = {"p_key", "scope_type", "scope_id"}))
public class PromptTemplate extends BaseEntity {

    /** 模板名（如 intent-route / answer-compose-rules），对应 classpath 文件名去 .txt */
    @Column(name = "p_key", nullable = false, length = 64)
    private String key;

    /** 作用域：PLATFORM（当前唯一实现）/ WORKSPACE / KB（预留多团队） */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType = "PLATFORM";

    /** 作用域 id：PLATFORM 为 null；WORKSPACE / KB 时为对应 id（预留） */
    @Column(name = "scope_id")
    private Long scopeId;

    /** 模板正文（{{变量名}} 命名占位符） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 版本号：每次编辑 +1（从 1 开始） */
    @Column(nullable = false)
    private Integer version = 1;

    /** 最近修改人（种子导入为 null） */
    @Column(name = "updated_by")
    private Long updatedBy;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }
}
