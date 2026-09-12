# SeuKnowledge 知识库与智能问答平台

通用型知识库与智能问答平台：知识资产统一管理 + 基于知识库证据的、可追溯的多节点智能问答。文档 / 业务知识 / 问答对统一入库，问答走 10 节点状态图（意图路由 → 问题改写 → 多源召回 → 交叉编码器精排 → 答案生成 → 自检 → 重试兜底，另有闲聊兜底、合并收口两个出口节点与终结节点 TERMINAL），全程 SSE 流式输出并附证据引用。

## 功能亮点

- 多工作空间与四角色权限（拥有者 / 管理员 / 编辑者 / 成员），数据按空间隔离
- 知识库级 ACL：公开 / 私有两级可见性，私有库可按用户与组授权、权限取高
- 文档管理：.txt/.md/.html/.pdf/.docx/.pptx/.xlsx/.xls 上传 → 解析分块 → 向量化入 ES；HTML/PDF/DOCX 走 LlamaParse 转 Markdown；表格、代码围栏感知分块；Excel 本地 POI 解析（数据不出本地）
- 文档初洗 / 精修：人工分块质量把关，可在线编辑 md 重分块、逐块审核后入库
- 模型配置：DashScope + OpenAI 兼容双供应商，按用途绑定（生成 / 检索 / 识图 / 重排等）
- Agent 配置：可视化编辑系统提示词与答案 / 记忆策略参数，保存即热生效
- 智能问答：多源召回（chunk + 业务知识 + 问答对）+ 交叉编码器精排 + 自检重试，答案带 [1][2] 证据引用
- 答案评价与点踩汇总：👍/👎 落库，`/stats` 汇总页（仅空间管理员可见）给踩率、原因分布与自检分明细
- 多轮会话记忆：滚动摘要持久化到会话表，与最近对话共同注入路由 / 改写节点
- AI 抽取：自动抽取业务知识与问答对，人工审核 + 版本回退
- 可观测性：OpenTelemetry Trace → Langfuse 可视化，含 LLM token 统计

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5、Spring AI 1.1（spring-ai-alibaba graph-core 状态图） |
| 数据 | MySQL 8（业务数据）、Elasticsearch 8（向量与混合检索 BM25+knn+RRF）、Redis（登录态 / 缓存 / 任务进度） |
| 文件存储 | MinIO 对象存储（默认）或本地磁盘（`KB_STORAGE_TYPE=local`），两种后端纯配置切换，**无自动降级** |
| 前端 | Vue 3 + Vite + Pinia + Element Plus |
| 模型 | DashScope（通义千问 / text-embedding / qwen-vl / gte-rerank）+ OpenAI 兼容（DeepSeek / ollama / one-api 等） |
| 可观测性 | OpenTelemetry SDK + OTLP → Langfuse（未配置自动 no-op） |

---

## 快速开始（本地开发）

> Docker Compose 部署、升级、备份与回滚，请阅读 [DEPLOYMENT.md](DEPLOYMENT.md)。
> 完整配置项（环境变量全表、模型服务配置、数据库升级、日志、测试）见 [application.md](application.md)。

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

### 2. 克隆代码并设置环境变量

```bash
git clone git@github.com:Baka-Lovealgorithm/SeuKnowledge.git seuknowledge
cd seuknowledge
```

后端所有配置集中在 `src/main/resources/application.yml`，**全部支持环境变量覆盖**（推荐部署方式，密钥不入库、不入日志）。

**必填环境变量（不设置将无法启动）**：

| 环境变量 | 示例值 | 说明 |
|---|---|---|
| `MYSQL_PASSWORD` | `your-mysql-password` | MySQL 密码（**无默认值，必填**） |
| `ES_URIS` | `http://localhost:9200` | Elasticsearch 地址（ES 在远程时务必设置） |

**常用可选环境变量**：

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | 18080 | 后端端口 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 127.0.0.1 / 6379 / 空 | Redis 连接 |
| `LLAMA_CLOUD_API_KEY` | 空 | LlamaParse API Key（HTML/PDF/DOCX 解析用） |

> 其余环境变量（连接池、ES 超时、缓存 TTL、限流、存储后端、分块、线程池、追踪等）见 [application.md](application.md)。

### 3. 启动后端

```bash
# Linux / macOS
MYSQL_PASSWORD=xxx ES_URIS=http://localhost:9200 ./mvnw spring-boot:run

# Windows PowerShell
$env:MYSQL_PASSWORD='xxx'; $env:ES_URIS='http://localhost:9200'; .\mvnw.cmd spring-boot:run
```

- 默认端口 **18080**（可用 `SERVER_PORT` 覆盖）；Swagger UI：`http://localhost:18080/swagger-ui.html`
- 首次启动自动建表并创建内置管理员账号 `admin / admin123`（可用 `seuknowledge.security.admin-username` / `admin-password` 覆盖；**生产环境请修改默认密码**）
- 已有数据库从旧版本升级：见 [application.md](application.md) 的「数据库初始化与升级」

### 4. 启动前端

```bash
cd web
npm install
npm run dev
```

- 默认端口 **5173**，`/api` 已代理到后端 18080（可用 `VITE_PROXY_TARGET` 覆盖）
- 浏览器访问 `http://localhost:5173`

### 5. 首次使用

1. 登录（admin/admin123）→ 新建或切换工作空间
2. **配置模型服务**（必配，否则问答/抽取不可用）：在「模型配置」页配置文本 + 向量模型并执行连通性测试，详见 [application.md](application.md) 的「配置模型服务」
3. 建知识库 → 上传文档（自动解析、分块、向量化）→ 智能问答
4. 可选：AI 抽取、文档初洗/精修、识图 / 重排模型、问答反馈汇总

## 相关文档

- [application.md](application.md) — 详细配置：环境变量全表、模型服务配置、对象存储布局、数据库升级、日志、测试
- [DEPLOYMENT.md](DEPLOYMENT.md) — Docker Compose 部署、运维、备份与回滚
