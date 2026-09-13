# ES 标题召回升级指南（存量索引手工迁移）

> 适用对象：**暂不升级应用代码**的存量部署。新版本应用的启动逻辑会自动完成本文的全部操作（幂等，已做过则跳过），手工执行过的话升级后自动检测到、不会重复执行。
>
> 相关代码：`VectorIndexService`（mapping 与自动补充）、`VectorSearchService`（BM25 查询字段）。

## 问题背景

问答链路的 BM25 检索一直在查询 `title^2.0` 与 `docName^0.5`（意图上标题权重最高），但 `kb_chunk` 索引里这两个字段建的是 **keyword 类型**：不分词、只有「查询串与字段全串完全相等」才能命中。自然语言问句永远无法命中，所以标题祖先链（如「第一章 > 1.1 背景」）与文档名**实际不参与 BM25 召回**——字段有、数据有、查询有，唯独 mapping 类型让它们成了摆设。

修复方式：给 `title` / `docName` 原地追加一个可分词的 **multi-field 子字段** `title.text` / `docName.text`（smartcn 分词），BM25 改打子字段。**不需要重建索引、不需要重新向量化（零 embedding 成本）**：追加子字段是 ES mapping update 明确支持的操作，存量文档用 `update_by_query` 原样重写一遍即可补齐子字段的倒排索引，向量值原样拷贝、一字节不变。

## 前提条件

- Elasticsearch 已安装 **analysis-smartcn** 插件（本项目检索本就依赖它；未装会报 `analyzer [smartcn] not found`）。
- 索引名默认 `kb_chunk`；若你的环境用 `KB_ES_INDEX` 改过索引名，下述命令里的 `kb_chunk` 替换为实际值。

## 第 0 步：确认是否需要升级

```bash
GET kb_chunk/_mapping
```

看 `title` / `docName` 字段：如果没有 `fields` 里的 `"text"` 子字段（即只有 `"type": "keyword"`），需要按下面两步升级；已经有则无需操作。

## 第 1 步：追加分词子字段（原地，不影响现有数据）

```bash
PUT kb_chunk/_mapping
{
  "properties": {
    "title": {
      "type": "keyword",
      "fields": {
        "text": { "type": "text", "analyzer": "smartcn", "search_analyzer": "smartcn" }
      }
    },
    "docName": {
      "type": "keyword",
      "fields": {
        "text": { "type": "text", "analyzer": "smartcn", "search_analyzer": "smartcn" }
      }
    }
  }
}
```

- 对已存在的 keyword 字段追加 multi-field 是 ES 支持的 mapping update，**不会改动任何现有字段类型与数据**。
- 子字段追加后，仅对之后新写入的文档生效；存量文档要靠第 2 步补齐。

## 第 2 步：回填存量文档（原样重写，向量不变）

```bash
POST kb_chunk/_update_by_query?conflicts=proceed&refresh=true
```

- 不带 script：ES 把每条文档按当前 mapping **原样重新索引一遍**（含 dense_vector），顺带补齐 `title.text` / `docName.text` 的倒排索引。
- `conflicts=proceed`：并发写入冲突时跳过被改动的文档（它们下次被更新时自然带上子字段）。
- **幂等**：重复执行无副作用；文档量大时耗时与文档数成正比，期间不阻塞读写。
- 零成本：全程不调用任何 embedding 服务。

## 第 3 步：验证

```bash
# 1. mapping 复查：title / docName 应出现 fields.text
GET kb_chunk/_mapping

# 2. 冒烟：用一个真实存在的祖先标题词（如某文档的章节名）检索，应能命中
GET kb_chunk/_search
{
  "size": 3,
  "query": {
    "bool": {
      "must": [
        { "multi_match": { "query": "章节标题关键词", "fields": ["title.text^2.0"] } }
      ],
      "filter": [
        { "term": { "kbId": 1 } }
      ]
    }
  }
}
```

命中即说明标题祖先链已参与检索。应用侧的 BM25 查询（`VectorSearchService`）在新代码里打的是 `title.text^2.0` / `docName.text^0.5`。

## 行为与风险说明

- **兼容降级**：BM25 的 multi_match 对未映射字段自动忽略。若第 1/2 步未完成（或失败），查询不会报错，自动退化为只查 `content`——与升级前的实际效果一致，**不会更差**。
- **应用自动升级**：新版应用每次启动都会检查子字段是否齐全，缺失则自动执行第 1、2 步并写日志（`ES 索引已补充分词子字段…回填存量文档 N 条`）。手工做过的话应用检测到后直接跳过。
- **叶子标题**：分块时叶子标题文本本身已并入块首 content，通过 content 的 BM25 一直可以命中；本次升级解锁的是**祖先级标题**与文档名的召回。
- **knn 向量召回不受影响**：向量输入本就包含标题（`title + "\n" + content`），本次只修复 BM25 一条链路。
