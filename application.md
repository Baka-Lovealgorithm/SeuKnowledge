# application.md — 配置与运维详解

后端所有配置集中在 `src/main/resources/application.yml`，**全部支持环境变量覆盖**（推荐部署方式，密钥不入库、不入日志）。本文收录常用的环境变量、模型服务配置、对象存储布局、数据库升级、日志与测试说明；完整键清单见 `application.yml` 的 `seuknowledge.*` 段（均带默认值）。

> 启动 / 部署入口见 [README.md](README.md)；Docker Compose 部署见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 环境变量参考

### 连接类

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | 127.0.0.1 / 3306 / seuknowledge | MySQL 地址 / 端口 / 库名 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | root / —（必填） | MySQL 账号密码 |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` / `DB_POOL_CONN_TIMEOUT` | 20 / 5 / 30000 | Hikari 连接池最大连接 / 最小空闲 / 获取连接超时（毫秒）；并发高时调大 `DB_POOL_MAX_SIZE` |
| `ES_URIS` | http://localhost:9200 | ES 地址 |
| `ES_USERNAME` / `ES_PASSWORD` | 空 | ES 认证（如开启） |
| `ES_CONN_TIMEOUT` / `ES_SOCKET_TIMEOUT` | 3s / 30s | ES 连接/读超时：一次问答最多 30 次 ES 查询，防止慢节点拖死链路 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | 127.0.0.1 / 6379 / 空 / 0 | Redis 连接（Redis 未启动自动降级直连 DB，fail-open） |
| `LLAMA_CLOUD_API_KEY` | 空 | LlamaParse API Key（敏感，建议环境变量注入） |
| `LANGFUSE_OTEL_ENDPOINT` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | 空 | Langfuse 追踪（可选，不配置自动 no-op） |

### 功能类（有默认值，按需调整）

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | 18080 | 后端端口 |
| `KB_TOKEN_TTL` | 604800 | 登录 token 有效期（秒） |
| `KB_CACHE_MEMBER_TTL` / `KB_CACHE_MODEL_TTL` / `KB_CACHE_AGENT_TTL` / `KB_CACHE_KB_TTL` / `KB_CACHE_KB_COUNT_TTL` / `KB_CACHE_KB_LIST_TTL` / `KB_CACHE_SESSION_TTL` / `KB_CACHE_HISTORY_TTL` / `KB_CACHE_TASK_TTL` | 300 / 600 / 600 / 300 / 300 / 60 / 60 / 600 / 86400 | 各类缓存 TTL（秒） |
| `KB_RATE_LIMIT_ENABLED` / `KB_RATE_LIMIT_ASK_PER_MINUTE` | true / 30 | 问答限流开关与每用户每分钟上限（默认开启） |
| `KB_STORAGE_TYPE` | minio | 文件存储后端：`minio`（对象存储，默认）或 `local`（本地磁盘）。**纯配置切换、不自动降级**——MinIO 不可用即上传失败，不会静默落本地。该值只决定**新写入**去向；读取按 `kb_document.storage_type` 逐行路由，故切换后端后存量文档仍可读 |
| `KB_MINIO_ENDPOINT` / `KB_MINIO_ACCESS_KEY` / `KB_MINIO_SECRET_KEY` / `KB_MINIO_BUCKET` | http://localhost:9000 / minioadmin / minioadmin / seu-knowledge | MinIO 接入参数（**默认凭证仅供本地开发，生产必须用环境变量覆盖**） |
| `KB_MINIO_AUTO_CREATE_BUCKET` | true | bucket 不存在时自动创建（**不启用版本控制**；md 的历史版本由 `kb_document_curate` 承载） |
| `KB_STORAGE_TEMP_DIR` | 空（`java.io.tmpdir/seuknowledge`） | 对象存储文档的物化临时目录；仅读取 MinIO 文档时使用，用完即删 |
| `KB_FILE_STORAGE_PATH` | ./data/files | **local 后端**的文档落盘根目录（键 `{wsId}/{kbId}/raw/{ext}/…`）；`KB_STORAGE_TYPE=minio` 时不用于写入，但仍用于读取存量本地行 |
| `KB_FILE_MAX_SIZE` | 20971520 (20MB) | 单文件大小上限（字节，与 multipart 上限对齐；上传超大文件需调大） |
| `KB_QA_MESSAGE_WINDOW` / `KB_QA_MAX_RETRY` | 20 / 2 | 对话记忆窗口条数 / 自检重试上限 |
| `KB_RERANK_CHUNK_TOP` / `KB_RERANK_OTHER_TOP` | 6 / 4 | 精排配额（文档 chunk / 业务知识+问答对合并；**重排模型本身在模型配置页配置**） |
| `KB_RERANK_MAX_DOCS` / `KB_RERANK_MAX_CHARS` / `KB_RERANK_TIMEOUT_MS` | 20 / 1500 / 10000 | 精排单请求上限 / 单条截断 / 超时（超时自动降级 ES 分） |
| `KB_CHUNK_SIZE` / `KB_CHUNK_OVERLAP` | 800 / 120 | 文档分块大小（字符）与重叠（标题感知分块） |
| `KB_VISION_PARSING` | true | PDF 识图总开关（需配置 VISION 类型模型） |
| `KB_VISION_AUTO` / `KB_VISION_MIN_TEXT` / `KB_VISION_DPI` / `KB_VISION_PARALLEL` | true / 50 / 100 / 3 | 按需识图开关 / 扫描页判定阈值 / 渲染分辨率 / 并行度 |
| `KB_LLAMAPARSE_ENABLED` / `KB_LLAMAPARSE_TIER` / `KB_LLAMAPARSE_LANGUAGE` | false / cost_effective / ch_sim | LlamaParse 开关 / 档位 / OCR 语言 |
| `KB_HTML_LOCAL_PARSER_ENABLED` | false | 保留的本地 Jsoup HTML 备用解析开关；默认 HTML 使用 LlamaParse，启用后 HTML 不经过 LlamaParse、初洗门与原始 Markdown 导出 |
| `KB_LLAMAPARSE_OUTPUT_DIR` | ./data/llamaparse | LlamaParse 原始转换 Markdown 导出目录（初洗前，按内容哈希命名；可为空禁用） |
| `KB_LLAMAPARSE_TAKE_SCREENSHOT` / `KB_LLAMAPARSE_FILL_MISSING_PAGES` | true / true | 整页截图返回 / 缺页 VLM 补全（后者需 VISION 模型） |
| `KB_ES_INDEX` / `KB_ES_DIMENSIONS` | kb_chunk / 1024 | ES 索引名与向量维度（**改维度需重建索引**） |
| `KB_ASYNC_CORE_SIZE` / `KB_ASYNC_MAX_SIZE` / `KB_ASYNC_QUEUE_CAPACITY` | 8 / 32 / 256 | 通用异步线程池（文档解析、AI 抽取等无限定符 `@Async`） |
| `KB_VECTOR_ASYNC_CORE_SIZE` / `KB_VECTOR_ASYNC_MAX_SIZE` / `KB_VECTOR_ASYNC_QUEUE_CAPACITY` | 2 / 4 / 200 | 向量化专用线程池（精修「确认」与「重建向量」走此池，不再排在分钟级抽取任务后面） |
| `KB_EXTRACT_CONCURRENCY` | 2 | 抽取任务全局并发上限（公平信号量，超限排队等待） |
| `KB_TRACING_ENABLED` | true | OpenTelemetry 追踪总开关 |
| `LOG_LEVEL_LLM` | debug | LLM I/O 调试日志级别（含 prompt/输出等敏感内容，生产建议 `info`） |
| `ACCESS_LOG_ENABLED` | true | Tomcat HTTP 访问日志开关 |

## 配置模型服务（必配，否则问答/抽取不可用）

系统不绑定具体模型厂商：文本与向量模型是问答/抽取的基础，通过 **DashScope（阿里云百炼）** 或任意 **OpenAI 兼容服务**（DeepSeek、ollama、one-api、SiliconFlow 等）提供。

**模型类型与用途**：类型 = **调用契约**（顶替会出功能性错误，故必须分开）；用途 = 同一契约下的**角色槽位**（顶替只是更贵或更差，可回退）。

| 模型类型 | 用途（角色槽位） | 必配？ | 说明 |
|---|---|---|---|
| CHAT 文本 | GENERATE（答案生成）/ VERIFY（自检校验）/ ROUTER（意图路由＋问题改写）/ EXTRACT（知识抽取）/ MEMORY（会话摘要）/ CHITCHAT（闲聊回复）/ TITLE（会话标题） | **必配**（至少一条） | 不同用途可绑不同模型（如生成用大模型，自检/路由/记忆/闲聊/标题用小模型省成本）。一条配置只占一个用途，未绑定的用途回退该类型的「通用」配置（用途留空那条）；同一模型要占多个用途需另建条目 |
| EMBEDDING 向量 | RETRIEVE（向量化与检索） | **必配** | 向量模型，维度需与 `KB_ES_DIMENSIONS`（默认 1024）一致；**入库与检索必须同一枚**，不得再按角色拆分 |
| VISION 识图 | VISION（PDF/PPTX 图片页与扫描页转写） | 可选 | 与文本同走 chat 端点但能力不同，故为独立类型：若并入文本，会被「通用文本模型」顶上而把图片发给不支持图像的模型 |
| RERANK 重排 | RERANK（交叉编码器精排） | 可选 | 未配置/超时自动降级为按检索分截断 |

> **标题曾是独立类型 `TITLE`**，现已并入 CHAT 的 `TITLE` 用途。存量 `model_type='TITLE'` 配置**无需迁移**：解析链为
> 「CHAT+TITLE 精确 → 历史 TITLE 类型 → CHAT+GENERATE」（第一档只认精确绑定，否则会被 CHAT 通用行顶替），
> 在配置页编辑保存后自动并入文本模型。

**配置步骤**（登录后在「模型配置」页操作）：

1. 新建「文本模型 CHAT」：选供应商（DASHSCOPE / OPENAI_COMPAT）→ 填模型名（如 `deepseek-chat`）→ 填 Base URL（OPENAI_COMPAT 填服务根地址，**不要带 `/v1`**，系统自动拼接）→ 填 API Key → 勾选用途（GENERATE；VERIFY/ROUTER/EXTRACT/MEMORY/CHITCHAT/TITLE 可另建条目分别绑定）→ 用途留空即为该类型的「通用」兜底配置
2. 新建「向量模型 EMBEDDING」：如 DashScope `text-embedding-v4` 或 OpenAI 兼容服务
3. 可选：VISION 识图模型（PDF 扫描页）、RERANK 重排模型（精排质量，如 `gte-rerank-v2`）
4. 每条配置点击**连通性测试**，通过后保存

**API Key 安全约定**：模型配置页的 `apiKey` 填 `env:环境变量名` 引用真实 Key（如 `env:ALIBABA_API_KEY`），真实 Key 通过环境变量注入，**不落库、不落日志、不外传**。

## 对象键布局（MinIO 与本地磁盘同口径）

两级前缀是「工作空间 / 知识库」，一个知识库就是一棵完整的子树（删库、导出、配额都只涉及一个前缀）；
`raw` 与 `derived` 分开原始件与解析产物，`raw` 下再按扩展名分层：

```
{wsId}/{kbId}/raw/{ext}/{uuid}.{ext}   原始上传文件
{wsId}/{kbId}/derived/md/{docId}.md    LlamaParse 产物 / 编辑后的 md（同键覆盖即最新版）
```

- 对象名是**纯 uuid**（32 位十六进制、去连字符）。名字段不参与任何程序逻辑（读取永远只用整串
  `object_key`），而 S3 没有 rename 原语——改名等于 copy + delete，既要整份复制、两步又非原子，
  所以干脆不放：**重命名文档无需搬迁对象**，也不存在中间态或孤儿对象。可读名以 DB 的 `file_name`
  为准，代价仅是 MinIO 控制台里认不出对象属于哪个文档。
- 本地后端把同一个 key 落到 `KB_FILE_STORAGE_PATH` 下，即 `data/files/{wsId}/{kbId}/…`。
- 改造前上传的文件键为 `{kbId}/{uuid}.{ext}`（少两级前缀），读取仍走 `kb_document.file_path` 的**原样路径**，
  因此存量文件原地不动即可继续读，**无需迁移**。

## 数据库初始化与升级

应用默认在启动时自动建表（JPA `ddl-auto: update`）并创建内置默认账号，**通常无需手工建库**；也可选择手工预建：

```bash
mysql -uroot -p < sql/schema.sql
```

`sql/schema.sql` 为 Hibernate schema export 导出的 **19 张表结构 + 内置默认账号种子**（admin 的 BCrypt 哈希、默认工作空间及其 OWNER 成员关系），**不含任何个人/业务数据**，可重复执行（`IF NOT EXISTS` + `INSERT IGNORE`）；预建后应用启动会自动跳过已存在的账号。Elasticsearch 的 `kb_chunk` 索引由应用启动时程序化创建，无需手工建。

已有数据库的版本升级脚本（用新版应用启动过一次后 `ddl-auto: update` 会自行补齐列，再执行对应脚本报 `Duplicate column name` 属正常，跳过即可）：

- **DRAFT 草稿唯一约束**：`sql/migrate_v2_unique_draft.sql`（去重 + 生成列索引，防并发重复草稿）。全新部署的 schema.sql 已含此约束，无需迁移。
- **答案评价**：`sql/migrate_v3_qa_feedback.sql`，为 `kb_chat_message` 增加反馈四列（`feedback` / `feedback_at` / `feedback_reason` / `feedback_note`）与答案自检快照四列（`verify_score` / `faithfulness_score` / `retry_count` / `missing_info`）。**全部可空、不回填、无索引、无外键**，存量消息与既有功能不受影响；仅 `role='ASSISTANT'` 的行会有值。快照列对存量行是 NULL，因此汇总页的均值类指标只统计含快照的行（页面同时给出该口径的样本数）。
- **对象存储**：`sql/migrate_v4_minio_storage.sql`，为 `kb_document` 增加 `storage_type` / `object_key` 两列。**`file_path` 保留不动、存量行不回填**——`storage_type` 为 NULL 或 `local` 的行一律按本地磁盘解释，因此升级后无需搬迁任何文件；只有新上传的文档才写入 `KB_STORAGE_TYPE` 指定的后端。

## 日志

- **输出位置**：控制台 + `logs/app.log`（INFO，按日期+大小自动轮转，保留 14 天）；LLM 调用详情（prompt/输出/耗时/token）独立写入 `logs/llm.log`（DEBUG，含知识库内容，**生产建议设置 `LOG_LEVEL_LLM=info`**）；HTTP 访问日志 `logs/access.*.log`（`ACCESS_LOG_ENABLED=false` 可关）。`logs/` 不入库
- **上下文关联**：每条日志携带 `requestId / userId / workspaceId / sessionId`（异步任务自动继承）。排查一次问答：`grep "<requestId>" logs/app.log` 即可串起改写→召回→精排→生成→校验全链路；`logs/llm.log` 可查看每次模型调用的 prompt 与原始输出

## 测试

```bash
# 纯单元测试（无需外部依赖，mock 隔离）
# 注意：裸跑 mvnw test 会把下面 4 个集成用例一起跑，未激活 dev profile 时会因空密码失败
.\mvnw.cmd test "-Dtest=!KonwledgeRepoApplicationTests,!ChatMessageStoreConcurrencyTest" -DfailIfNoTests=false

# 全量测试（含 2 个 @SpringBootTest 集成测试类 / 4 个用例，需 MySQL/Redis；ES 缺失时 fail-open 降级）
$env:SPRING_PROFILES_ACTIVE='dev'; .\mvnw.cmd test

# MinIO 真机冒烟（默认跳过，需 9000 端口已起 MinIO；覆盖 put/读/物化/覆盖/删除与按行路由）
.\mvnw.cmd test -Dminio.smoke=true -Dtest=MinioStorageSmokeTest -DfailIfNoSpecifiedTests=false
```
