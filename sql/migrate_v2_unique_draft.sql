-- ============================================================================
-- SeuKnowledge 迁移脚本 v2：唯一约束（防 DRAFT 重复）
-- 适用：已有 v1 数据库，升级到 v2（schema.sql 已含生成列，全新部署无需此脚本）
-- 步骤：
--   1. 清理现有重复活跃 DRAFT（每组保留 MAX(id)，其余软删）
--   2. 添加生成列 + 唯一索引
-- 执行：mysql -u root -p seuknowledge < migrate_v2_unique_draft.sql
-- ============================================================================

-- 1. 业务知识：清理 (kb_id, term) 重复的活跃 DRAFT，仅保留最大 id
UPDATE kb_business_knowledge b
JOIN (
    SELECT kb_id, term, MAX(id) AS keep
    FROM kb_business_knowledge
    WHERE deleted = 0
    GROUP BY kb_id, term
    HAVING COUNT(*) > 1
) d ON b.kb_id = d.kb_id AND b.term = d.term AND b.id <> d.keep
SET b.deleted = 1;

-- 2. 问答对：清理 (kb_id, MD5(question)) 重复的活跃 DRAFT
UPDATE kb_qa_pair q
JOIN (
    SELECT kb_id, MD5(question) AS qh, MAX(id) AS keep
    FROM kb_qa_pair
    WHERE deleted = 0
    GROUP BY kb_id, MD5(question)
    HAVING COUNT(*) > 1
) d ON q.kb_id = d.kb_id AND MD5(q.question) = d.qh AND q.id <> d.keep
SET q.deleted = 1;

-- 3. 添加生成列 + 唯一索引（仅 DRAFT 状态参与唯一约束）
ALTER TABLE kb_business_knowledge
    ADD COLUMN active_term varchar(255)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 AND status = 'DRAFT' THEN term ELSE NULL END) STORED,
    ADD UNIQUE INDEX uk_kb_active_term (kb_id, active_term);

ALTER TABLE kb_qa_pair
    ADD COLUMN active_question char(32)
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 AND status = 'DRAFT' THEN MD5(COALESCE(question,'')) ELSE NULL END) STORED,
    ADD UNIQUE INDEX uk_kb_active_question (kb_id, active_question);