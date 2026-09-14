<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 200px" filterable @change="onKbChange">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-input v-model="keyword" placeholder="搜索文件名" clearable style="width: 200px" />
      <el-select v-model="stageFilter" placeholder="状态" style="width: 140px">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STAGES" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-checkbox v-model="onlySuspect">只看有待审核块</el-checkbox>
      <span class="tip">按状态进入处理页：初洗中 → 文档初洗；精修中 / 已向量化 → 分块编辑</span>
    </div>

    <el-table :data="pagedList" v-loading="loading" border>
      <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="stageType(row)" size="small">{{ stageLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="分块数" width="90" />
      <el-table-column label="待审核" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.suspectCount > 0" type="warning" size="small">{{ row.suspectCount }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.stage === 'PREVIEWING'" link type="warning" @click="goCurate(row)">去初洗</el-button>
          <el-button v-else-if="row.stage === 'ACCEPTED' || row.stage === 'VECTORIZED'"
                     link type="primary" @click="goChunks(row)">编辑分块</el-button>
          <el-button v-else-if="row.stage === 'PARSE_FAILED' || row.stage === 'VECTOR_FAILED'"
                     link type="info" @click="goDocs">去文档管理</el-button>
          <span v-else class="muted">处理中</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <Pager v-model:page="pageIndex" v-model:page-size="pageSize"
             :total="filteredList.length" :page-sizes="[20, 50]" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { docApi, kbApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { formatDateTime } from '../utils/format'
import { loadViewState, saveViewState } from '../utils/viewState'
import Pager from '../components/Pager.vue'

const router = useRouter()
const auth = useAuthStore()

/** 上次工作位置的持久化标识（本页是「文档分块」的首页；chunk 编辑页不持久化，位置即 URL） */
const STATE_NAME = 'reviewList'

/**
 * 文档阶段——本页唯一的分类口径，决定「操作」列进入哪个处理页。
 *
 * 注意判定顺序：**必须先看 curateStatus 再看 parseStatus**。初洗门收尾落的正是
 * `parseStatus=SUCCESS` + `curateStatus=PREVIEWING`（`DocumentParseTx.finalizeSuccessGated`），
 * 若先判 parseStatus，初洗中的文档会被误标成「已向量化」，点进去走的是已向量化通道，
 * 写操作会被后端明确拒绝（`ChunkReviewService` 对 PREVIEWING/ACCEPTED 一律拒绝）。
 */
const STAGES = [
  { value: 'PREVIEWING', label: '初洗中', type: 'warning' },
  { value: 'ACCEPTED', label: '精修中', type: 'primary' },
  { value: 'VECTORIZED', label: '已向量化', type: 'success' },
  { value: 'PARSING', label: '解析中', type: 'info' },
  { value: 'PARSE_FAILED', label: '解析失败', type: 'danger' },
  { value: 'VECTOR_FAILED', label: '向量失败', type: 'danger' }
]
const STAGE_MAP = Object.fromEntries(STAGES.map((s) => [s.value, s]))

/** 文档阶段：初洗中 > 精修中 > 已向量化 > 解析失败 > 向量失败 > 解析中 */
function stageOf(d) {
  if (d.curateStatus === 'PREVIEWING') return 'PREVIEWING'
  if (d.curateStatus === 'ACCEPTED') return 'ACCEPTED'
  if (d.parseStatus === 'SUCCESS') return 'VECTORIZED'
  if (d.parseStatus === 'FAILED') return 'PARSE_FAILED'
  if (d.parseStatus === 'ERROR') return 'VECTOR_FAILED'
  return 'PARSING'
}
const stageLabel = (row) => (STAGE_MAP[row.stage] || {}).label || row.stage
const stageType = (row) => (STAGE_MAP[row.stage] || {}).type || 'info'

const kbs = ref([])
const kbId = ref(null)
const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const stageFilter = ref('')
const onlySuspect = ref(false)
const pageIndex = ref(1)
const pageSize = ref(20)

// ===== 过滤 + 分页（纯前端）：列表接口返回全量（含状态、待审核数），筛选与翻页在浏览器侧完成 =====
const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return rows.value.filter((d) => {
    if (stageFilter.value && d.stage !== stageFilter.value) return false
    if (onlySuspect.value && !(d.suspectCount > 0)) return false
    if (kw && !String(d.fileName || '').toLowerCase().includes(kw)) return false
    return true
  })
})

const pagedList = computed(() => {
  const start = (pageIndex.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})

/** 当前页越界（过滤后结果变少 / 删除文档）时收敛到最后一页 */
function clampPage() {
  const maxPage = Math.max(1, Math.ceil(filteredList.value.length / pageSize.value))
  if (pageIndex.value > maxPage) pageIndex.value = maxPage
}

// 过滤条件或每页条数变化 → 回第 1 页
watch([keyword, stageFilter, onlySuspect, pageSize], () => { pageIndex.value = 1 })
watch(filteredList, clampPage)
// 工作位置整体持久化（含页码），下次进入本页时恢复
watch([kbId, keyword, stageFilter, onlySuspect, pageSize, pageIndex], persistState)

/** 文档列表：接口一次带全「状态 / 分块数 / 待审核数」，无需再拼初洗队列 */
async function load() {
  if (!kbId.value) return
  loading.value = true
  try {
    const all = await docApi.list(kbId.value)
    rows.value = (all || []).map((d) => ({
      docId: d.id,
      fileName: d.fileName,
      parseStatus: d.parseStatus,
      curateStatus: d.curateStatus ?? null,
      chunkCount: d.chunkCount ?? 0,
      suspectCount: d.suspectCount ?? 0,
      createdAt: d.createdAt,
      stage: stageOf(d)
    }))
  } finally {
    loading.value = false
  }
}

async function onKbChange() {
  pageIndex.value = 1
  await load()
}

/** 记录当前工作位置（知识库 / 搜索词 / 状态过滤 / 只看待审核 / 分页） */
function persistState() {
  saveViewState(STATE_NAME, auth.workspaceId, {
    kbId: kbId.value,
    keyword: keyword.value,
    stageFilter: stageFilter.value,
    onlySuspect: onlySuspect.value,
    pageSize: pageSize.value,
    page: pageIndex.value
  })
}

async function init() {
  kbs.value = await kbApi.list()
  // 恢复上次的工作位置；上次的知识库不可用（归档 / 撤权 / 换空间）时降级到第一个
  const saved = loadViewState(STATE_NAME, auth.workspaceId)
  const targetKb = kbs.value.find((k) => String(k.id) === String(saved.kbId)) || kbs.value[0]
  kbId.value = targetKb ? targetKb.id : null
  if (saved.keyword) keyword.value = saved.keyword
  if (saved.stageFilter) stageFilter.value = saved.stageFilter
  if (saved.onlySuspect) onlySuspect.value = true
  if (saved.pageSize) pageSize.value = saved.pageSize
  pageIndex.value = Math.max(1, Number(saved.page) || 1)
  if (!kbId.value) return
  await load()
  clampPage()
}

/** 按状态分流：初洗中 → 文档初洗页；精修中 / 已向量化 → 分块编辑页 */
function goCurate(row) { router.push(`/curate/${row.docId}`) }
function goChunks(row) { router.push(`/review/${row.docId}`) }
function goDocs() { router.push(`/kb/${kbId.value}/documents`) }

onMounted(init)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.toolbar .tip { color: #909399; font-size: 12px; }
.muted { color: #c0c4cc; font-size: 13px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
