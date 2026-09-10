# SeuKnowledge 知识库与智能问答平台

通用型知识库与智能问答平台：知识资产统一管理 + 基于知识库证据的、可追溯的多节点智能问答。文档 / 业务知识 / 问答对统一入库，问答走 10 节点状态图（意图路由 → 问题改写 → 多源召回 → 交叉编码器精排 → 答案生成 → 自检 → 重试兜底，另有闲聊兜底、合并收口两个出口节点与终结节点 TERMINAL），全程 SSE 流式输出并附证据引用。

## 功能亮点

- **多工作空间与角色权限**：一个用户可属于多个工作空间，四角色（拥有者 / 管理员 / 编辑者 / 普通成员），所有数据按空间隔离
- **知识库级权限（ACL，用户 / 组双粒度）**：知识库可见性支持「公开 / 私有」两级——私有库可同时授权给**用户**与**组**（组内成员自动继承，用户直授与组授权合并时**取最高权限**：EDIT 覆盖 VIEW），列表与详情双重拦截；空间管理员与知识库创建者始终可管理（设置可见性、按成员/组授权）
- **知识库与文档管理**：.txt/.md/.html/.pdf/.docx/.pptx/.xlsx/.xls 上传 → 自动解析分块 → 向量化入 Elasticsearch；HTML/PDF/DOCX 统一交由 LlamaParse 转为逐页 Markdown，HTML 也可启用「初洗门 → 精修」人工质量把关；LlamaParse 的**原始 Markdown**按内容哈希导出保留，便于复盘和改进；分块携带**标题祖先链路径**（如 `第一章 > 1.1 背景 > 1.1.1 研究意义`），LlamaParse 转出的 **Markdown 表格特殊解析**（小表整表原子块、大表行组+表头、空单元格 forward-fill 还原合并单元格语义；LlamaParse 产物与 .md 直传均生效）；**Markdown 围栏代码块感知**（```/~~~ 围栏按行优先于表格认领：小代码块整块原子、大代码块按空行块切行组且每组自带语言标签围栏头尾、组间无 overlap、围栏内 `#` 注释不污染标题链、ASCII 表不冒充表格；LlamaParse 产物与 .md 直传均生效）；**Excel 本地 POI 解析**（数据不出本地：纵向合并 forward-fill、横向合并标题行/总结行置空防污染、公式求值、图片忽略，复用表格 A+B 分块）
- **文档初洗 / 文档精修（人工分块质量把关）**：上传时勾选「初洗门」后，解析分块停在**初洗**阶段——全部分块只读，可在线编辑整篇 md 后重新分块；点击「接受」后 md 冻结、分块进入**精修**阶段（以整个文件为单位），可对分块编辑 / 删除 / 合并 / 保留（保留后变「已审核」，可回退「待审核」）；全部未删除分块均为已审核后，「确认完成并向量化」统一入库。普通文档的待审核（SUSPECT）分块也在「文档精修」页按文件复核（保留/编辑即向量化）。分块排序支持「待审核优先 / 自然顺序」两类
- **模型配置**：DashScope + OpenAI 兼容双供应商，按用途绑定（生成 / 抽取 / 检索 / 识图 / 重排 / 自检 / 标题 / 路由），按工作空间隔离，支持连通性测试
- **Agent 配置**：每个知识库绑定一个 Agent，页面可视化编辑**系统提示词**（注入意图路由 / 问题改写 / 答案生成 / 自检各节点）与**答案/记忆策略参数**（自检阈值 / 重试上限 / 记忆窗口），保存后立即生效、无需重启；问答链路各节点提示词模板仍统一入库（启动时以 classpath 模板幂等播种、运行时热生效），暂不提供前端管理页面
- **智能问答**：多源召回（文档 chunk + 业务知识 + 问答对）+ 交叉编码器精排 + 自检重试，答案带 [1][2] 证据引用
- **答案评价与点踩汇总**：每条回答可 👍/👎（点踩后可选填原因：没答到点上 / 信息过时 / 引用不对 / 太啰嗦 / 其它，允许跳过；再次点击撤销），评价随答案落 `kb_chat_message`。回答落库时顺带把当轮自检产出（完整性分、事实一致性、重试轮数、缺失项）快照进同一行——零额外模型调用，换来「用户踩的是不是低质量答案」可查。汇总页（`/stats`，仅空间 OWNER/ADMIN 可见）给出回答数、点踩数、踩率、无证据率、被中途停止比例、原因分布与踩明细；统计口径分两类：点踩/健康度指标覆盖窗口内全部回答（含上线前存量），自检均值只统计含快照的行并给出样本数。评价只有会话属主本人能提交，跨用户读取只发生在汇总接口并留访问日志
- **多轮会话记忆**：会话滚动摘要每新增 6 条消息压缩一次，**持久化到会话表**（Redis 仅作读缓存，不再因缓存过期丢失长期记忆），与最近 3 轮原文共同注入意图路由 / 问题改写 / 闲聊节点，用于指代消歧与省略句补全
- **AI 抽取**：自动抽取业务知识（术语/别名/定义/…）与问答对，人工审核 + 启用/禁用 + 版本回退
- **可观测性**：OpenTelemetry Trace → Langfuse 可视化，含 LLM token 统计

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5、Spring AI 1.1（spring-ai-alibaba graph-core 状态图） |
| 数据 | MySQL 8（业务数据）、Elasticsearch 8（向量与混合检索 BM25+knn+RRF）、Redis（登录态 / 缓存 / 任务进度） |
| 前端 | Vue 3 + Vite + Pinia + Element Plus |
| 模型 | DashScope（通义千问 / text-embedding / qwen-vl / gte-rerank）+ OpenAI 兼容（DeepSeek / ollama / one-api 等） |
| 可观测性 | OpenTelemetry SDK + OTLP → Langfuse（未配置自动 no-op） |

---

## 快速开始（在全新机器上部署）

> 使用 Docker Compose 部署、升级、备份与回滚，请阅读 [DEPLOYMENT.md](DEPLOYMENT.md)。本节保留本地开发启动方式。

以下按"从零开始在一台新机器上运行本系统"的顺序说明，**每一步的依赖与配置项均为必读**。

### 1. 前置软件

| 依赖 | 版本要求 | 用途 | 说明 |
|---|---|---|---|
| JDK | **21** | 编译 / 运行后端 | 本项目使用 Java 21 特性（虚拟线程等），低于 21 无法编译 |
| Maven | 3.8+ | 构建后端 | 无 Maven 也可用仓库自带的 `mvnw` / `mvnw.cmd`（自动下载） |
| Node.js | 18+ | 构建 / 运行前端 | 含 npm |
| MySQL | 8.x | 业务数据 | 库名默认 `seuknowledge`，**启动时自动创建**；表结构由 JPA 自动维护，无需手工建表 |
| Elasticsearch | 8.x | chunk 向量与混合检索 | **启动时自动创建 `kb_chunk` 索引**（默认 1024 维）；需安装中文分词插件，见下文 |
| Redis | 7.x | 登录态 / 缓存 / 任务进度 | **可选**——未启动时应用自动降级直连 DB，功能不受影响（fail-open） |

> Elasticsearch 中文分词插件（必装，版本必须与 ES 完全一致，装后重启 ES）：
>
> ```bash
> bin/elasticsearch-plugin install analysis-smartcn
> ```

### 2. 克隆代码并准备配置

```bash
git clone git@github.com:Baka-Lovealgorithm/SeuKnowledge.git seuknowledge
cd seuknowledge
```

后端所有配置集中在 `src/main/resources/application.yml`，**全部支持环境变量覆盖**（推荐部署方式，密钥不入库、不入日志）。

**必填环境变量（不设置将无法启动）**：

| 环境变量 | 示例值 | 说明 |
|---|---|---|
| `MYSQL_PASSWORD` | `your-mysql-password` | MySQL 密码（**无默认值，必填**） |
| `ES_URIS` | `http://localhost:9200` | Elasticsearch 地址（默认 `http://localhost:9200`，如 ES 在远程请务必设置） |

**常用可选环境变量**：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | 127.0.0.1 / 3306 / seuknowledge | MySQL 地址 / 端口 / 库名 |
| `MYSQL_USER` | root | MySQL 账号 |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` / `DB_POOL_CONN_TIMEOUT` | 20 / 5 / 30000 | Hikari 连接池最大连接 / 最小空闲 / 获取连接超时（毫秒）；并发高时调大 `DB_POOL_MAX_SIZE` |
| `ES_USERNAME` / `ES_PASSWORD` | 空 | ES 认证（如开启） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | 127.0.0.1 / 6379 / 空 / 0 | Redis 连接 |
| `SERVER_PORT` | 18080 | 后端端口 |
| `LLAMA_CLOUD_API_KEY` | 空 | LlamaParse API Key（敏感，建议环境变量注入） |
| `LANGFUSE_OTEL_ENDPOINT` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | 空 | Langfuse 追踪（可选，不配置自动 no-op） |

> 完整键清单（功能类参数如分块大小、重排配额、限流、缓存 TTL 等）见 `application.yml` 的 `seuknowledge.*` 段，均带默认值，按需调整即可。

### 3. 数据库初始化（可选：手工预建）

应用默认在启动时自动建表（JPA `ddl-auto: update`）并创建内置默认账号，**通常无需手工建库**；也可选择手工预建：

```bash
mysql -uroot -p < sql/schema.sql
```

`sql/schema.sql` 为 Hibernate schema export 导出的 **19 张表结构 + 内置默认账号种子**（admin 的 BCrypt 哈希、默认工作空间及其 OWNER 成员关系），**不含任何个人/业务数据**，可重复执行（`IF NOT EXISTS` + `INSERT IGNORE`）；预建后应用启动会自动跳过已存在的账号。Elasticsearch 的 `kb_chunk` 索引由应用启动时程序化创建，无需手工建。

> 已有数据库升级：执行 `sql/migrate_v2_unique_draft.sql` 添加 DRAFT 草稿唯一约束（去重 + 生成列索引，防并发重复草稿）。全新部署的 schema.sql 已含此约束，无需迁移。
>
> 已有数据库升级到「答案评价」版本：执行 `sql/migrate_v3_qa_feedback.sql`，为 `kb_chat_message` 增加反馈四列（`feedback` / `feedback_at` / `feedback_reason` / `feedback_note`）与答案自检快照四列（`verify_score` / `faithfulness_score` / `retry_count` / `missing_info`）。**全部可空、不回填、无索引、无外键**，存量消息与既有功能不受影响；仅 `role='ASSISTANT'` 的行会有值。若你已用新版应用启动过一次，`ddl-auto: update` 会自动补齐这些列，再执行本脚本会报 `Duplicate column name`（无害，跳过即可）。快照列对存量行是 NULL，因此汇总页的均值类指标只统计含快照的行（页面同时给出该口径的样本数）。

### 4. 配置模型服务（必配，否则问答/抽取不可用）

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

### 5. 启动后端

```bash
# Linux / macOS
MYSQL_PASSWORD=xxx ES_URIS=http://localhost:9200 ./mvnw spring-boot:run

# Windows PowerShell
$env:MYSQL_PASSWORD='xxx'; $env:ES_URIS='http://localhost:9200'; .\mvnw.cmd spring-boot:run
```

- 默认端口 **18080**（可用 `SERVER_PORT` 覆盖）；Swagger UI：`http://localhost:18080/swagger-ui.html`
- 首次启动自动创建内置管理员账号（默认账号与角色见下文）

### 6. 启动前端

```bash
cd web
npm install
npm run dev
```

- 默认端口 **5173**，`/api` 已代理到后端 18080（可用 `VITE_PROXY_TARGET` 覆盖）
- 浏览器访问 `http://localhost:5173`

### 7. 默认账号与角色

- **系统内置账号**：`admin / admin123`（系统管理员 ADMIN，默认工作空间拥有者 OWNER）。初始用户名/密码可用 `seuknowledge.security.admin-username` / `admin-password` 配置覆盖，登录后亦可修改；**生产环境请修改默认密码**。
- **工作空间角色（OWNER / ADMIN / EDITOR / MEMBER）**：非独立登录账号，由拥有者/管理员在「成员管理」中分配——「创建新账号」时初始密码由邀请者设置，「邀请已有用户」沿用其原账号。
- 手工预建数据库（执行 `sql/schema.sql`）后，可直接用 `admin/admin123` 登录。

### 8. 首次使用流程

1. 登录（admin/admin123）→ 新建或切换工作空间
2. **模型配置**：配置文本 + 向量模型（apiKey 建议 `env:` 引用）并执行**连通性测试**；（可选）配置识图 / 重排模型
3. 建知识库 → 上传文档（自动解析、分块、向量化）。文档列表分两条健康线：**解析状态**（`PENDING/PARSING/SUCCESS/FAILED/ERROR`，只代表分块是否落库）与**向量**（`向量 i/t`，代表分块是否真的进了 ES；`SUCCESS` 但 `i<t` 说明向量化尚未追平，悬停看明细）。改名用文件名后的 ✎（只改列表与检索引用名，不重解析、扩展名不可改）；向量化失败或"先补配模型再救历史文档"时点**重建向量**（只重跑 embedding+ES 写入，不产生云端解析消耗）；**重试**才会全量重新解析（初洗中的文档会连带丢弃人工编辑的 md 版本，有二次确认）
4. （可选）AI 抽取：对文档创建抽取任务 → 在业务知识 / 问答对页审核草稿
5. 智能问答：选择知识库提问，答案流式输出并附证据引用；回答下方的 👍/👎 用于反馈质量（点踩可选填原因，可跳过；再点一次撤销）
6. （可选，管理员）「问答反馈」页查看点踩汇总：踩率、原因分布与踩明细（含该答案当时的自检分与缺失项），据此定位该补哪类文档

---

## 配置参考（完整环境变量表）

### 连接类

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | 127.0.0.1 / 3306 / seuknowledge | MySQL 地址 / 端口 / 库名 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | root / —（必填） | MySQL 账号密码 |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` / `DB_POOL_CONN_TIMEOUT` | 20 / 5 / 30000 | Hikari 连接池最大连接 / 最小空闲 / 获取连接超时（毫秒）；并发高时调大 `DB_POOL_MAX_SIZE` |
| `ES_URIS` | http://localhost:9200 | ES 地址 |
| `ES_USERNAME` / `ES_PASSWORD` | 空 | ES 认证（如开启） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | 127.0.0.1 / 6379 / 空 / 0 | Redis 连接 |
| `LLAMA_CLOUD_API_KEY` | 空 | LlamaParse API Key（敏感，建议环境变量注入） |
| `LANGFUSE_OTEL_ENDPOINT` / `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | 空 | Langfuse 追踪（可选） |

### 功能类（有默认值，按需调整）

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | 18080 | 后端端口 |
| `KB_TOKEN_TTL` | 604800 | 登录 token 有效期（秒） |
| `KB_CACHE_MEMBER_TTL` / `KB_CACHE_MODEL_TTL` / `KB_CACHE_AGENT_TTL` / `KB_CACHE_KB_TTL` / `KB_CACHE_KB_COUNT_TTL` / `KB_CACHE_KB_LIST_TTL` / `KB_CACHE_SESSION_TTL` / `KB_CACHE_HISTORY_TTL` / `KB_CACHE_TASK_TTL` | 300 / 600 / 600 / 300 / 300 / 60 / 60 / 600 / 86400 | 各类缓存 TTL（秒） |
| `KB_RATE_LIMIT_ENABLED` / `KB_RATE_LIMIT_ASK_PER_MINUTE` | true / 30 | 问答限流开关与每用户每分钟上限（默认开启） |
| `KB_FILE_STORAGE_PATH` | ./data/files | 文档存储目录 |
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

> 完整键清单见 `application.yml` 的 `seuknowledge.*` 段。

---

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
```

当前 **75 个测试类、904 个用例**（分块器与标题祖先链、LlamaParse 表格解析、代码围栏分块、Excel 本地解析、文档解析、文档重命名/重建向量/文件名校验、向量化状态回写与线程池装配、模型解析/配置、模型类型×用途组合矩阵、标题槽位解析链（含历史 `TITLE` 类型兼容）、知识库、会话与滚动摘要、抽取任务、多工作空间成员管理、空间组管理与权限取高、重排客户端/节点、标题生成、答案自检两阶段聚合（并行/串行两路一致）、答案评价（越权拒绝 / 撤销 / 只写反馈列 / 消息缓存失效）、点踩汇总口径（踩率分母、无快照时均值留空、无知识库短路、明细批量取问题不逐行）、旧版本缓存载荷兼容等）。其中 2 个 `@SpringBootTest` 集成测试类（4 个用例）需 MySQL/Redis 环境，纯单元测试 900 个全绿。
