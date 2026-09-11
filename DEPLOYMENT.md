# Docker Compose 部署与迭代指南

本方案在不改变现有 Java 后端和 Vite 前端本地开发流程的前提下，提供一套独立的 Docker Compose 部署能力。单台 Docker 主机上会运行 Vue 前端、Spring Boot 后端、MySQL、Elasticsearch 和 Redis。

## 1. 必须持久化的数据

应用镜像随时可以替换，以下数据必须位于镜像之外并纳入备份：

| 位置 | 内容 | 恢复重要性 |
|---|---|---|
| `mysql-data` Docker 卷 | 用户、知识库、文档元数据、chunk、模型配置、会话和审计信息 | 必须保留 |
| `es-data` Docker 卷 | 向量索引和 BM25 索引 | 可由业务数据重建，但耗时较长 |
| `./runtime/data/files` | 用户上传的原始文档（**仅 `KB_STORAGE_TYPE=local` 时**，见下方说明） | 必须保留，重解析和下载需要它 |
| `./runtime/data/llamaparse` | LlamaParse 的原始 Markdown 产物 | 建议保留，便于质量复盘 |
| `./runtime/logs` | 应用、访问和模型调用日志 | 可选，但故障排查很有价值 |

这些目录均已被 `.gitignore` 排除，不能提交到 Git。

**原始文件的存储位置由 `KB_STORAGE_TYPE` 决定，且只影响新上传的文件：**

| 取值 | 原始文件位置 | 备份要求 |
|---|---|---|
| `local`（Compose 当前默认，见 `.env.example`） | `./runtime/data/files`，键 `{wsId}/{kbId}/raw/{ext}/{uuid}.{ext}` | 连同 `runtime/data` 一起备份 |
| `minio`（应用默认值，需要 MinIO 服务） | MinIO 对象存储，同上一列的对象键；md 镜像键 `{wsId}/{kbId}/derived/md/{docId}.md` | 必须单独备份 MinIO 的数据卷，`runtime/data/files` 不再包含新文档 |

两级前缀是「工作空间 / 知识库」：一个知识库就是一棵完整的子树（`{wsId}/{kbId}/`），删库、导出、按库设配额都只涉及一个前缀；`raw` 与 `derived` 分开原始件与解析产物，`raw` 下再按扩展名分层。对象名是纯 uuid——改文件名只改 DB 与 ES 索引，**不搬迁对象**（S3 改名 = copy + delete，非原子且要整份复制），所以按前缀备份/迁移时不必担心对象名与文档名脱节。**改造前**上传的文件键为 `{kbId}/{uuid}.{ext}`（少两级前缀），读取走 `kb_document.file_path` 的原样路径，因此存量文件原地不动即可继续读，**切换前后都不需要搬迁文件**。

`kb_document.storage_type` 逐行记录每个文档实际所在的后端，读取时按行路由，因此**切换后端不必搬迁既有文件**：存量行（`storage_type` 为 NULL/`local`）继续从本地目录读取，只有切换后的新上传走新后端。**本仓库的 Compose 暂未包含 MinIO 服务**，所以在 Compose 部署中请保持 `KB_STORAGE_TYPE=local`；要改用它需先自行加入 MinIO 服务并把 bucket 数据卷列入备份。

注意 MinIO 后端**不做自动降级**：MinIO 不可用时上传会直接失败，而不是回落到本地目录。这是有意为之，避免同一批文档一半在对象存储、一半在本地而无人察觉。

## 2. 部署前提

- Docker Engine 26+ 和 Docker Compose v2。Windows 推荐安装启用 WSL 2 后端的 Docker Desktop。
- 最小环境至少预留 4 GB 可用内存。ES 默认使用 512 MB；共享测试环境建议在 `.env` 中设为 `ES_HEAP_MB=1024`。
- 至少 10 GB 空闲磁盘，用于镜像和持久化数据。
- 主机端口 `8088` 未被占用；冲突时修改 `.env` 的 `FRONTEND_PORT`。
- 需要从后端容器访问模型 API；使用 LlamaParse 时同样需要外网。

Compose 中的 ES 镜像会在构建时安装 `analysis-smartcn`。`ES_VERSION` 与插件版本必须完全一致，修改 ES 版本后必须重新构建镜像。

## 3. 首次部署

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
notepad .env
```

启动前至少在 `.env` 中设置：

```dotenv
MYSQL_PASSWORD=a-long-random-database-password
SEUKNOWLEDGE_SECURITY_ADMIN_PASSWORD=a-long-random-initial-admin-password
```

`.env` 含密钥且被 Git 忽略，不能上传。若要让 HTML/PDF/DOCX 使用 LlamaParse，继续设置：

```dotenv
KB_LLAMAPARSE_ENABLED=true
LLAMA_CLOUD_API_KEY=your-llamaparse-key
```

`.env` 还支持 `VITE_ENABLE_AI_EXTRACTION`（默认 `false`，见 `.env.example`）：前端**构建期**开关，关闭时隐藏「AI 抽取」相关入口与模型配置页的抽取（EXTRACT）用途绑定。设为 `true` 后需要重新构建前端镜像（`docker compose up -d --build frontend`）才能生效；已有的抽取配置数据保留在后端，可随开关恢复。

`.env.example` 已预置 `KB_STORAGE_TYPE=local`，Compose 部署请保持该值——本套 Compose 的服务清单里没有 MinIO（详见第 1 节）。该值是**运行期**开关（不像 `VITE_ENABLE_AI_EXTRACTION` 需要重建），改完 `docker compose up -d backend` 重启后端即可生效，但只影响新上传的文档。

构建并启动整套服务：

```powershell
docker compose up -d --build
docker compose ps
```

首次构建会下载 Maven、Node、Java、Nginx、MySQL、Redis、Elasticsearch 和中文分词插件，耗时数分钟属于正常现象。

浏览器访问 `http://localhost:8088`。前端会将 `/api` 代理到后端，浏览器不需要直接访问 `18080`；后端、MySQL、ES 和 Redis 都不会暴露到宿主机。

检查服务健康状态：

```powershell
docker compose ps
docker compose logs --tail=100 backend
docker compose exec backend wget -q -O - http://localhost:18080/actuator/health
```

健康端点应返回 `{"status":"UP"}`。接着使用首次启动前配置的管理员账号登录，配置模型服务，上传一份小文档并验证一次知识库问答。

注意：初始管理员密码只在管理员账号首次创建时生效。之后修改 `SEUKNOWLEDGE_SECURITY_ADMIN_PASSWORD` 不会重置已有账号。

## 4. 日常运维

常用命令：

```powershell
# 持续查看后端日志
docker compose logs -f backend

# 停止容器，但保留数据卷和上传文件
docker compose down

# 使用已有镜像和数据重新启动
docker compose up -d

# 源码修改后，仅重新构建应用镜像
docker compose up -d --build backend frontend

# 查看状态与实际 Compose 配置
docker compose ps
docker compose config
```

有业务数据的环境不要执行 `docker compose down -v`，`-v` 会删除 MySQL、Elasticsearch 和 Redis 数据卷。

## 5. 更新到新版本

应使用 Git 提交号或发布标签标识部署版本，不要只依赖 `latest`：

```powershell
git fetch origin --prune
git switch main
git pull --ff-only origin main
```

更新前先按下一节创建备份，再在 `.env` 写入可追踪版本，例如 `APP_TAG=git-09bed07`，然后执行：

```powershell
docker compose up -d --build backend frontend
docker compose ps
docker compose logs --tail=100 backend
```

确认登录、普通问答、流式问答和一次文档上传均正常后再宣布发布完成。只更新应用源码不会改变 Compose 服务名和持久化卷，因此不会删除现有数据。

后续可将本地构建替换为 CI 构建的不可变镜像，例如 `registry.example/seuknowledge-backend:git-09bed07`。保留 Compose 编排结构，将应用服务的 `build` 段替换为已审核镜像地址即可。

## 6. 备份与恢复

在仓库外创建带时间戳的备份目录。下列命令会导出 MySQL，并复制后端挂载的数据和日志：

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path "..\seuknowledge-backup-$stamp" | Out-Null
docker compose exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --events "${MYSQL_DATABASE}"' > "..\seuknowledge-backup-$stamp\mysql.sql"
Copy-Item -Recurse -Force runtime\data "..\seuknowledge-backup-$stamp\data"
Copy-Item -Recurse -Force runtime\logs "..\seuknowledge-backup-$stamp\logs"
```

完整灾备还应由主机备份系统或维护流程归档 `mysql-data` 和 `es-data` 数据卷。ES 可通过重新入库恢复，但保留 ES 卷可以显著缩短恢复时间。

恢复到一套刻意初始化为空的环境时，先停止应用容器，将 SQL 导入 MySQL，恢复 `runtime/data`，再启动后端和前端。投入生产前，务必先在非生产环境演练一次完整恢复。

## 7. 回滚

只有目标代码与当前数据库结构、ES Mapping 兼容时，应用回滚才安全。应保留上一个已验证的提交号或镜像标签以及更新前备份。

源码构建部署的回滚方式：

```powershell
git switch <previous-verified-tag-or-commit>
docker compose up -d --build backend frontend
```

数据库迁移或 Embedding 维度变更后不能直接盲目回滚；必要时恢复匹配的 MySQL 备份，并重建或重新入库 ES。

## 8. 需要专门迁移方案的变更

| 变更 | 必须动作 |
|---|---|
| `KB_ES_DIMENSIONS` 或向量模型维度 | 新建或重建 `kb_chunk` 索引，再重新向量化全部来源 |
| ES 分词器、插件或版本 | 构建匹配的 ES 镜像并重新建立索引 |
| MySQL 表或字段变更 | 上线前使用版本化迁移，长期不能依赖 `ddl-auto=update`。存储后端改造对应 `sql/migrate_v4_minio_storage.sql`（`kb_document` 加 `storage_type` / `object_key`，可空、不回填） |
| 上传文件存储路径 / 存储后端 | `KB_STORAGE_TYPE=local` 时变更前复制 `runtime/data/files`；切换到 `minio` 前先确认 MinIO 数据卷已纳入备份。既有文件不必搬迁——读取按 `kb_document.storage_type` 逐行路由，存量行继续走原路径 |
| API 或前端路由变更 | 后端与前端必须作为同一测试版本发布 |

当前项目仍使用 Hibernate `ddl-auto=update`，它适合开发。首次长期生产部署前，应引入 Flyway 或 Liquibase，并让生产环境切换到 schema validation。之后每次表结构变更都应有经审核的版本化迁移和恢复方案。

## 9. 生产加固清单

- 为前端容器配置域名和 TLS 终止；本仓库的 Nginx 只负责应用反向代理，不负责证书管理。
- 只向公网开放前端端口，MySQL、ES、Redis 和后端只留在 Compose 内部网络。
- 使用云平台或 CI 的密钥管理，不要广泛复制生产 `.env` 文件。
- MySQL 和管理员账号使用不同的强随机密码，首次登录后修改管理员密码。
- 生产保持 `LOG_LEVEL_LLM=info` 或更严格，因为 DEBUG 日志可能含提示词、回答和知识库内容。
- 定期备份 MySQL 与 `runtime/data`，并至少演练一次恢复。
- 监控磁盘空间、ES 内存、容器重启、后端日志和文档解析失败。
- 在测试环境构建和验收镜像，再替换生产版本。

## 10. 本地开发流程保持不变

Docker 部署文件不会替换现有本地开发方式。开发人员仍可用 Maven 启动后端、用 Vite 启动前端；Compose 用于验证部署行为或获得隔离的完整环境。部署相关改动应尽量保持在独立 Pull Request 中，使业务功能和发布配置易于分别审查。
