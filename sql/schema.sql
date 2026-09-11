-- ============================================================================
-- SeuKnowledge 数据库建表脚本（MySQL 8）
-- ----------------------------------------------------------------------------
-- 1. 本文件仅含【表结构】与【内置默认账号种子】，不含任何个人/业务数据。
-- 2. 19 张表由 Hibernate schema export 依据实体模型导出（与应用启动时
--    JPA ddl-auto:update 自动建表的效果一致），统一改为 IF NOT EXISTS，
--    可重复执行。
-- 3. 应用启动时 AdminInitializer 会按用户名检查内置账号：已存在则自动跳过，
--    因此「执行本脚本预建」与「直接启动应用自动建表」两种方式可共存且幂等。
--    本脚本适用于全新数据库预建；若库中已有应用自动创建的数据，可只执行
--    建表部分（种子段均为 INSERT IGNORE，重复执行亦不报错）。
-- 4. 实体变更后可用以下命令重新导出 DDL（覆盖本文件）：
--    mvnw spring-boot:run "-Dspring-boot.run.arguments=--spring.jpa.hibernate.ddl-auto=none
--        --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
--        --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=<绝对路径>/sql/schema.sql"
-- 5. Elasticsearch 的 kb_chunk 向量索引由应用启动时程序化创建（非 SQL），本文件不含。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS seuknowledge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE seuknowledge;

-- ============================================================================
-- 表结构（19 张）
-- ============================================================================

CREATE TABLE IF NOT EXISTS kb_access (
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    created_by bigint,
    kb_id bigint not null,
    grantee_type varchar(10) not null,
    grantee_id bigint not null,
    permission varchar(10) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_group (
    id bigint not null auto_increment,
    workspace_id bigint not null,
    name varchar(64) not null,
    description varchar(200),
    created_at datetime(6),
    updated_at datetime(6),
    created_by bigint,
    primary key (id),
    constraint uk_group_workspace_name unique (workspace_id, name)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_group_member (
    id bigint not null auto_increment,
    group_id bigint not null,
    user_id bigint not null,
    created_at datetime(6),
    updated_at datetime(6),
    created_by bigint,
    primary key (id),
    constraint uk_group_member unique (group_id, user_id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_agent (
    max_retry integer,
    memory_window integer,
    verify_threshold float(53),
    created_at datetime(6),
    created_by bigint,
    id bigint not null auto_increment,
    kb_id bigint not null,
    updated_at datetime(6),
    name varchar(128) not null,
    description varchar(500),
    system_prompt TEXT,
    CONSTRAINT uk_kb_agent_kb_id UNIQUE (kb_id),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_business_knowledge (
    deleted bit not null,
    version integer not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    kb_id bigint not null,
    source_doc_id bigint,
    updated_at datetime(6),
    status varchar(20) not null,
    history_group_id varchar(64),
    aliases TEXT,
    definition TEXT,
    example TEXT,
    prohibited_rules TEXT,
    scope TEXT,
    source_doc_name varchar(255),
    term varchar(255) not null,
    active_term varchar(255) GENERATED ALWAYS AS (CASE WHEN deleted = 0 AND status = 'DRAFT' THEN term ELSE NULL END) STORED,
    CONSTRAINT uk_kb_active_term UNIQUE (kb_id, active_term),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_chat_message (
    created_at datetime(6),
    id bigint not null auto_increment,
    session_id bigint not null,
    updated_at datetime(6),
    faithfulness_score double,
    retry_count integer,
    verify_score double,
    feedback_at datetime(6),
    interrupted bit,
    role varchar(20) not null,
    feedback varchar(10),
    feedback_reason varchar(32),
    feedback_note varchar(200),
    missing_info varchar(500),
    intent varchar(32),
    content TEXT not null,
    refs TEXT,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_chat_session (
    message_count integer,
    memory_summary TEXT,
    summary_msg_count integer,
    title_auto bit,
    created_at datetime(6),
    id bigint not null auto_increment,
    kb_id bigint not null,
    last_message_at datetime(6),
    updated_at datetime(6),
    user_id bigint not null,
    title varchar(255),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_chunk (
    page_num integer,
    seq integer,
    token_count integer,
    created_at datetime(6),
    doc_id bigint not null,
    id bigint not null auto_increment,
    kb_id bigint not null,
    updated_at datetime(6),
    status varchar(20) not null,
    es_id varchar(64),
    clean_status varchar(20),
    clean_reason varchar(500),
    content TEXT not null,
    title varchar(255),
    primary key (id)
) engine=InnoDB;

-- 文档。file_path 为历史字段（local 后端仍写真实路径）；storage_type/object_key 为对象存储改造
-- 新增列：storage_type ∈ {local, minio}，存量行为 NULL 一律按 local 并用 file_path 解释（零迁移）。
-- object_key 是与后端无关的逻辑键：原始文件 {wsId}/{kbId}/raw/{ext}/{uuid}_{原始名}.{ext}
-- （改造前为 {kbId}/{uuid}.{ext}；存量值为 NULL，读取走 file_path）。
CREATE TABLE IF NOT EXISTS kb_document (
    chunk_count integer,
    version integer,
    created_at datetime(6),
    created_by bigint,
    file_size bigint,
    id bigint not null auto_increment,
    kb_id bigint not null,
    updated_at datetime(6),
    file_type varchar(10) not null,
    parse_status varchar(20) not null,
    error_msg varchar(500),
    file_path varchar(500),
    file_name varchar(255) not null,
    storage_type varchar(20),
    object_key varchar(512),
    curate_required bit,
    curate_status varchar(20),
    primary key (id)
) engine=InnoDB;

-- 人工清洗（md 策展）：分页分段 + append-only 版本（每次整篇保存插入新版本的全部页行，
-- 最新 = max(version)；版本即审计，相邻版本按页对比即 diff；删除文档时按 doc_id 整删）。
CREATE TABLE IF NOT EXISTS kb_document_curate (
    id bigint not null auto_increment,
    doc_id bigint not null,
    version integer not null,
    page_num integer not null,
    content MEDIUMTEXT not null,
    updated_by bigint not null,
    created_at datetime(6),
    updated_at datetime(6),
    CONSTRAINT uk_doc_version_page UNIQUE (doc_id, version, page_num),
    primary key (id)
) engine=InnoDB;

-- 文档级策展动作审计（save_md / accept / confirm / edit_chunk / drop_chunk / keep_chunk）；
-- chunk 级编辑内容留痕仍写 chunk_review_log，本表仅记文档级动作与前后摘要。
CREATE TABLE IF NOT EXISTS document_curate_log (
    id bigint not null auto_increment,
    doc_id bigint not null,
    action varchar(30) not null,
    before_summary TEXT,
    after_summary TEXT,
    user_id bigint,
    created_at datetime(6),
    updated_at datetime(6),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_extract_task (
    processed_docs integer,
    progress integer not null,
    total_docs integer,
    created_at datetime(6),
    created_by bigint,
    duration_ms bigint,
    finished_at datetime(6),
    id bigint not null auto_increment,
    kb_id bigint not null,
    token_input bigint,
    token_output bigint,
    token_total bigint,
    updated_at datetime(6),
    extract_type varchar(20) not null,
    status varchar(20) not null,
    result_summary varchar(500),
    doc_ids TEXT,
    error_log TEXT,
    failed_doc_ids TEXT,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS chunk_review_log (
    created_at datetime(6),
    updated_at datetime(6),
    chunk_id bigint not null,
    doc_id bigint not null,
    user_id bigint,
    id bigint not null auto_increment,
    action varchar(20) not null,
    before_content TEXT,
    after_content TEXT,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_knowledge_base (
    archived bit not null,
    created_at datetime(6),
    created_by bigint,
    id bigint not null auto_increment,
    updated_at datetime(6),
    workspace_id bigint,
    status varchar(20) not null,
    visibility varchar(10) not null,
    name varchar(128) not null,
    description varchar(500),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_model_config (
    enabled bit not null,
    is_default bit not null,
    max_tokens integer,
    temperature decimal(3,2),
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    workspace_id bigint,
    disable_thinking bit,
    thinking_params varchar(500),
    model_type varchar(32) not null,
    provider varchar(32) not null,
    usage_type varchar(32),
    model_name varchar(128) not null,
    name varchar(128) not null,
    api_key varchar(255),
    base_url varchar(255),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_qa_pair (
    deleted bit not null,
    version integer not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    kb_id bigint not null,
    source_doc_id bigint,
    updated_at datetime(6),
    status varchar(20) not null,
    history_group_id varchar(64),
    answer TEXT not null,
    normalized_question TEXT,
    question TEXT not null,
    source_doc_name varchar(255),
    synonyms TEXT,
    active_question char(32) GENERATED ALWAYS AS (CASE WHEN deleted = 0 AND status = 'DRAFT' THEN MD5(COALESCE(question,'')) ELSE NULL END) STORED,
    CONSTRAINT uk_kb_active_question UNIQUE (kb_id, active_question),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_workspace (
    created_at datetime(6),
    id bigint not null auto_increment,
    owner_user_id bigint not null,
    updated_at datetime(6),
    name varchar(128) not null,
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS kb_workspace_member (
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    user_id bigint not null,
    workspace_id bigint not null,
    role varchar(20) not null,
    CONSTRAINT uk_kb_workspace_member UNIQUE (workspace_id, user_id),
    primary key (id)
) engine=InnoDB;

CREATE TABLE IF NOT EXISTS sys_user (
    enabled bit not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    role varchar(20) not null,
    username varchar(64) not null,
    password varchar(255) not null,
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    primary key (id)
) engine=InnoDB;

-- ============================================================================
-- 内置默认账号种子（无个人数据）
-- ----------------------------------------------------------------------------
-- 系统唯一内置账号：admin / admin123（BCrypt 哈希存储，与应用启动时
-- AdminInitializer 自动创建的一致；已通过 BCrypt matches 校验）。
-- 生产环境请登录后修改密码，或通过 seuknowledge.security.admin-password 覆盖。
-- 默认工作空间与 OWNER 成员关系与应用启动初始化逻辑一致。
-- ============================================================================

INSERT IGNORE INTO sys_user (id, username, password, role, enabled, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$WoVAOQtt2Z.EOKY2xV2qVueBMWvwlwPIdeJ1LHz9MKvf9g6sKOowC', 'ADMIN', 1, NOW(), NOW());

INSERT IGNORE INTO kb_workspace (id, name, owner_user_id, created_at, updated_at)
VALUES (1, '默认工作空间', 1, NOW(), NOW());

INSERT IGNORE INTO kb_workspace_member (id, workspace_id, user_id, role, created_at, updated_at)
VALUES (1, 1, 1, 'OWNER', NOW(), NOW());
