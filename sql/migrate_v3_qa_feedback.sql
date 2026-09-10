-- ============================================================================
-- SeuKnowledge 迁移脚本 v3：问答答案反馈（点踩）与答案质量快照
-- ----------------------------------------------------------------------------
-- 适用：已有 v2 数据库，升级到 v3（schema.sql 已含下列列，全新部署无需执行本脚本）
-- 内容：kb_chat_message 增 8 列（答案反馈 4 列 + 自检快照 4 列），
--       并补回基线早先漏记的 interrupted（实体与线上库早已存在，仅基线缺列）
-- 特性：全部可空、无默认值、无索引、无外键、不回填 —— 存量行与老功能零影响
-- 注意：应用使用 JPA ddl-auto:update，只要用新版启动过一次，列就已自动补齐，
--       此时再执行本脚本会报 Duplicate column name（无害，跳过即可）
-- 执行：mysql -u root -p seuknowledge < migrate_v3_qa_feedback.sql
-- 回滚：列可留（老代码按名取列，不认得的列不影响读写）；
--       确需清理见下方「回滚」段
-- ============================================================================

ALTER TABLE kb_chat_message
    ADD COLUMN interrupted bit NULL,
    ADD COLUMN feedback varchar(10) NULL,
    ADD COLUMN feedback_at datetime(6) NULL,
    ADD COLUMN feedback_reason varchar(32) NULL,
    ADD COLUMN feedback_note varchar(200) NULL,
    ADD COLUMN verify_score double NULL,
    ADD COLUMN faithfulness_score double NULL,
    ADD COLUMN retry_count integer NULL,
    ADD COLUMN missing_info varchar(500) NULL;

-- 列语义（仅 ASSISTANT 行有值；USER 行恒为 NULL）
--   feedback          ：UP / DOWN，NULL = 未评价（撤销时连同下三列一并置 NULL）
--   feedback_at       ：评价时间
--   feedback_reason   ：FeedbackReason 枚举 name，NULL = 用户跳过了原因
--   feedback_note     ：补充说明，落库前截断 200 字
--   verify_score      ：答案自检完整性分快照（AnswerVerify 阶段一）
--   faithfulness_score：事实一致性快照（SUPPORTED 断言占比 0~1）
--   retry_count       ：重试轮数快照
--   missing_info      ：自检「缺失信息」快照，截断 500 字
-- 统计口径：自检快照三列对存量行为 NULL，均值类指标只统计非空行，
--           不可把 NULL 当 0 分参与计算。

-- ============================================================================
-- 回滚（仅在确需退回 v2 结构时执行）
-- ============================================================================
-- ALTER TABLE kb_chat_message
--     DROP COLUMN feedback,
--     DROP COLUMN feedback_at,
--     DROP COLUMN feedback_reason,
--     DROP COLUMN feedback_note,
--     DROP COLUMN verify_score,
--     DROP COLUMN faithfulness_score,
--     DROP COLUMN retry_count,
--     DROP COLUMN missing_info;
-- interrupted 不要 DROP：它在 v2 之前就已由实体声明并被应用创建，只是基线漏记。
