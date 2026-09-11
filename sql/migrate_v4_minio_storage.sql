-- ============================================================================
-- SeuKnowledge 迁移脚本 v4：文档物理存储后端可切换（MinIO / 本地磁盘）
-- ----------------------------------------------------------------------------
-- 适用：已有 v3 数据库，升级到 v4（schema.sql 已含下列列，全新部署无需执行本脚本）
-- 内容：kb_document 增 2 列 storage_type / object_key
-- 特性：全部可空、无默认值、无索引、无外键、**不回填** —— 存量行与老功能零影响
-- 注意：应用使用 JPA ddl-auto:update，只要用新版启动过一次，列就已自动补齐，
--       此时再执行本脚本会报 Duplicate column name（无害，跳过即可）
-- 执行：mysql -u root -p seuknowledge < migrate_v4_minio_storage.sql
-- 回滚：列可留（老代码按名取列，不认得的列不影响读写）；
--       确需清理见下方「回滚」段
-- ============================================================================

ALTER TABLE kb_document
    ADD COLUMN storage_type varchar(20) NULL,
    ADD COLUMN object_key varchar(512) NULL;

-- ============================================================================
-- 列语义
-- ----------------------------------------------------------------------------
--   storage_type：该行原始文件所在的后端，取值 local / minio。
--                 **NULL 或空白一律按 local 解释**（存量行兼容），因此本脚本不回填任何数据。
--                 读走这一列逐行路由，所以 MinIO 部署下依然能读旧的本地文件；
--                 反过来本地部署遇到 minio 行会明确报「后端未启用」，而不是静默失败。
--   object_key  ：与后端无关的逻辑键，落库后即权威（读取/删除都按它路由，不再由 kbId 反推）。
--                 原始文件 {wsId}/{kbId}/raw/{ext}/{uuid}.{ext}（改造前为 {kbId}/{uuid}.{ext}，
--                          少两级前缀；存量行的 object_key 为空，读取走 file_path，不受影响）；
--                          对象名为纯 uuid——改文件名只改 DB file_name 与 ES 索引，不搬迁对象；
--                 LlamaParse 产物 md  {wsId}/{kbId}/derived/md/{docId}.md（docId 恒定 → 同键覆盖即最新版，
--                          无对应列，由 DocumentBlobService.mdKey 推导）。
--
-- file_path 保留不动：local 行的 file_path 为真实本地路径（新上传时同时写 object_key），
--                     minio 行的 file_path 为 NULL（对象不在本机）。
--                     本地读取优先把 file_path 按「原样路径」解释，其次才按
--                     {KB_FILE_STORAGE_PATH}/{object_key} 解释，因此存量路径无需改写。
--
-- 升级后的行为（重要，避免误判为"迁移失败"）：
--   1. 存量文档一行未动，仍然从原来的本地路径读取；
--   2. 只有升级后**新上传**的文档才写入 KB_STORAGE_TYPE 指定的后端；
--   3. 切换 KB_STORAGE_TYPE 不会搬移既有文件，也不会改变既有行的 storage_type。
--
-- 写入去向由配置 KB_STORAGE_TYPE 单独决定（默认 minio），**不做自动降级**：
-- MinIO 不可用时上传直接失败，不会静默落到本地磁盘。
-- ============================================================================

-- ============================================================================
-- 回滚（仅在确需退回 v3 结构时执行）
-- ============================================================================
-- ALTER TABLE kb_document
--     DROP COLUMN storage_type,
--     DROP COLUMN object_key;
-- 注意：若已用 KB_STORAGE_TYPE=minio 上传过文档，回滚后这些行的原始文件只能在 MinIO 中找到，
--       file_path 为 NULL，老版本代码会读不到文件 —— 回滚前先确认无此类行：
--   SELECT COUNT(*) FROM kb_document WHERE storage_type = 'minio';
