<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 200px" @change="onKbChange">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-select v-model="docId" placeholder="选择文档" style="width: 340px" filterable clearable
                 :disabled="!kbId || !docs.length" @change="onDocChange">
        <el-option v-for="d in docs" :key="d.docId" :label="docLabel(d)" :value="d.docId" />
      </el-select>
      <el-tag v-if="docId" :type="isCurate ? 'warning' : 'success'" size="small" effect="plain">
        {{ isCurate ? '精修中 · 确认前' : '已向量化 · 修改即时生效' }}
      </el-tag>
      <el-radio-group v-if="docId" v-model="sortMode" size="small" @change="onFilterChange">
        <el-radio-button value="suspect">待审核优先</el-radio-button>
        <el-radio-button value="seq">自然顺序</el-radio-button>
      </el-radio-group>
      <el-switch v-if="docId" v-model="onlySuspect" active-text="只看待审核" size="small" @change="onFilterChange" />
      <div class="spacer" />
      <template v-if="auth.canWrite && docId && suspectCount > 0">
        <el-button type="warning" :loading="batchKeeping" title="保留本文档全部待审核分块（跨页）"
                   @click="keepAll">一键通过 ({{ suspectCount }})</el-button>
      </template>
      <template v-if="auth.canWrite && docId">
        <el-button type="primary" :disabled="!canMerge" :loading="acting" title="勾选两块相邻（|seq差|=1）分块后合并"
                   @click="openMerge">合并{{ selectedRows.length ? ` (${selectedRows.length})` : '' }}</el-button>
        <el-button v-if="isCurate" type="success" :disabled="!canConfirm" :loading="acting"
                   title="全部未删除分块均为已审核后可用" @click="confirm">确认完成并向量化</el-button>
      </template>
      <template v-if="auth.canWrite && !isCurate && docId && selectedSuspectCount > 0">
        <el-button type="primary" size="small" :loading="acting" @click="batchKeep">批量保留 ({{ selectedSuspectCount }})</el-button>
        <el-button type="danger" size="small" :loading="acting" @click="batchDrop">批量删除 ({{ selectedSuspectCount }})</el-button>
      </template>
    </div>

    <el-empty v-if="!loading && !docId" :description="kbId ? '该知识库暂无可处理的文档' : '请选择知识库'" />

    <template v-else>
      <el-alert v-if="docId && isCurate" type="warning" :closable="false" class="hint"
                :title="`精修中（确认前）：md 已冻结，改动只保存到数据库、不触向量库。可编辑/删除/合并/新增/保留分块；无待审核分块后，「确认完成并向量化」统一入库。${pendingCount > 0 ? `还有 ${pendingCount} 个待审核分块。` : '全部已审核，可以确认向量化。'}`" />
      <el-alert v-else-if="docId" type="success" :closable="false" class="hint"
                title="已向量化文档：编辑 / 删除 / 合并 / 新增均可直接操作——保存后该块自动重新向量化（约几秒），删除与回退的分块立即移出检索。" />

      <el-table :data="chunks" v-loading="loading" border size="small" @selection-change="onSelection">
        <el-table-column v-if="auth.canWrite && docId" type="selection" width="40"
                         :selectable="selectableForSelect" />
        <el-table-column prop="seq" label="序号" width="70" />
        <el-table-column prop="pageNum" label="页码" width="70" />
        <el-table-column prop="title" label="所属标题" min-width="130" show-overflow-tooltip />
        <el-table-column label="内容" min-width="300">
          <template #default="{ row }">
            <div class="content-cell">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="row.cleanReason" :content="row.cleanReason" placement="top">
              <el-tag :type="statusTagType(row)" size="small">{{ statusText(row) }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="statusTagType(row)" size="small">{{ statusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="auth.canWrite ? 340 : 80" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
            <template v-if="auth.canWrite && docId">
              <el-button v-if="editable(row)" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.cleanStatus === 'SUSPECT'" link type="success" @click="keep(row)">保留</el-button>
              <el-button v-if="row.cleanStatus !== 'SUSPECT' && row.cleanStatus !== 'FILTERED'" link type="warning" @click="unkeep(row)">回退</el-button>
              <el-button v-if="deletable(row)" link type="danger" @click="drop(row)">删除</el-button>
              <el-button v-if="insertable(row)" link type="primary" @click="openCreate(row)">插入</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="docId" class="pager">
        <el-pagination v-model:current-page="pageIndex" v-model:page-size="pageSize"
                       :total="total" :page-sizes="[20, 50, 100, 200]"
                       layout="total, sizes, prev, pager, next"
                       @current-change="onPageChange" @size-change="onSizeChange" />
      </div>
      <el-empty v-if="!loading && docId && !chunks.length" description="该文档暂无分块" />
    </template>

    <!-- chunk 精修编辑 -->
    <el-dialog v-model="editVisible" :title="`编辑分块 #${editRow?.seq || ''}（${editRow?.docName || ''}）`" width="760px">
      <div class="edit-reason" v-if="editRow && editRow.cleanReason">
        <b>备注：</b>{{ editRow.cleanReason }}
      </div>
      <el-form label-width="60px">
        <el-form-item label="标题">
          <el-input v-model="editTitle" maxlength="255" placeholder="所属小节标题（可空）" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="editContent" type="textarea" :rows="14" resize="none" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" :disabled="!editContent || !editContent.trim()" @click="saveEdit">
          {{ isCurate ? '保存' : '保存并重新向量化' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增分块（插入到锚点分块之后） -->
    <el-dialog v-model="createVisible" :title="`在 #${createAnchor?.seq ?? ''} 后插入新分块`" width="760px">
      <div class="create-tip">新分块将插入到 #{{ createAnchor?.seq }} 之后，其后的分块序号自动后移。</div>
      <el-form label-width="60px">
        <el-form-item label="标题">
          <el-input v-model="createTitle" maxlength="255" placeholder="所属小节标题（可空，默认沿用锚点）" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="createContent" type="textarea" :rows="12" resize="none" placeholder="新分块内容（不能为空）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSaving" :disabled="!createContent || !createContent.trim()" @click="saveCreate">
          {{ isCurate ? '保存' : '保存并立即向量化' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- chunk 详情（只读，完整内容） -->
    <ChunkDetail v-model="detailVisible" :row="detailRow" />

    <!-- 合并相邻分块：选择保留的目标块 -->
    <el-dialog v-model="mergeVisible" title="合并相邻分块（选择目标）" width="720px" :close-on-click-modal="false">
      <el-alert :type="isCurate ? 'info' : 'success'" :closable="false" class="merge-tip"
                :title="isCurate
                  ? '两块内容将按文档顺序拼接写入目标块；目标块回到待审核，另一块将被删除（记录保留）。请选择合并后的目标块：'
                  : '两块内容将按文档顺序拼接写入目标块，目标块立即重新向量化；另一块将从文档与检索中删除（记录保留）。请选择合并后的目标块：'" />
      <el-radio-group v-model="mergeTargetId" class="merge-opts">
        <el-radio v-for="c in mergeCandidates" :key="c.chunkId" :value="c.chunkId" class="merge-opt">
          <div class="merge-opt-title">#{{ c.seq }} {{ c.title || '（无标题）' }}<span v-if="c.pageNum" class="merge-opt-page"> · 第 {{ c.pageNum }} 页</span></div>
          <div class="merge-opt-content">{{ c.content }}</div>
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :loading="mergeSaving" :disabled="!mergeTargetId" @click="doMerge">确认合并</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { curateApi, docApi, kbApi, reviewApi } from '../api'
import { useAuthStore } from '../stores/auth'
import ChunkDetail from '../components/ChunkDetail.vue'

const route = useRoute()
const auth = useAuthStore()

const kbs = ref([])
const kbId = ref(null)
const docs = ref([])
const docId = ref(null)
/** 当前文档是否初洗门已接受（精修中）：true → curateApi（纯 DB，确认时统一向量化）；false → reviewApi/docApi（操作即时向量化） */
const isCurate = ref(false)
const chunks = ref([])
/** 分页状态：page 0 基（请求用），pageIndex 1 基（el-pagination 用）；数据由后端排序与过滤 */
const page = ref(0)
const pageIndex = ref(1)
const pageSize = ref(20)
const total = ref(0)
const suspectCount = ref(0)
const onlySuspect = ref(false)
const batchKeeping = ref(false)
const sortMode = ref('suspect')
const loading = ref(false)
const acting = ref(false)
const selectedRows = ref([])
const editVisible = ref(false)
const editSaving = ref(false)
const editRow = ref(null)
const editTitle = ref('')
const editContent = ref('')
const createVisible = ref(false)
const createSaving = ref(false)
const createAnchor = ref(null)
const createTitle = ref('')
const createContent = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)
const mergeVisible = ref(false)
const mergeSaving = ref(false)
const mergeTargetId = ref(null)
const mergeCandidates = ref([])

/** 待审核（SUSPECT）计数：后端随分页响应返回（全文档口径，不受当前页/过滤影响） */
const pendingCount = computed(() => suspectCount.value)
/** 确认完成前置：精修中 + 文档有分块 + 无待审核分块（服务端 confirm 仍会做最终校验） */
const canConfirm = computed(() => isCurate.value && docId.value && total.value > 0 && pendingCount.value === 0)
/** 勾选中可批量处置的待审核分块（批量保留/删除仅对 SUSPECT 有意义） */
const selectedSuspectCount = computed(() => selectedRows.value.filter((r) => r.cleanStatus === 'SUSPECT').length)
/** 合并可用性：恰好勾选 2 块 + 同一文档 + 相邻（|seq差|=1）；精修中与已向量化文档都支持 */
const canMerge = computed(() => {
  if (!auth.canWrite || !docId.value) return false
  if (selectedRows.value.length !== 2) return false
  const [a, b] = selectedRows.value
  if (!a || !b || String(a.docId) !== String(b.docId)) return false
  return Math.abs(a.seq - b.seq) === 1
})

const statusText = (row) => {
  if (row.cleanStatus === 'SUSPECT') return '待审核'
  if (row.cleanStatus === 'FILTERED') return '已删除'
  return '已审核'
}
const statusTagType = (row) => {
  if (row.cleanStatus === 'SUSPECT') return 'warning'
  if (row.cleanStatus === 'FILTERED') return 'info'
  return 'success'
}

/** 文档阶段文案（下拉与标签用）：精修中 > 已向量化 > 向量失败 > 解析中/失败 */
function stageOf(d) {
  if (d.curateStatus === 'ACCEPTED') return '精修中'
  if (d.parseStatus === 'SUCCESS') return '已向量化'
  if (d.parseStatus === 'ERROR') return '向量失败'
  if (d.parseStatus === 'FAILED') return '解析失败'
  return '解析中'
}

function docLabel(d) {
  const base = `${d.fileName}（${stageOf(d)}`
  if (d.curateStatus === 'ACCEPTED') return `${base} · 待审核 ${d.suspectCount ?? 0}）`
  const parts = [`${(d.chunkCount ?? 0)} 块`]
  if (d.suspectCount > 0) parts.push(`待审核 ${d.suspectCount}`)
  return `${base} · ${parts.join(' · ')}）`
}

/** 编辑/删除可用性：两种阶段均为任意未删除分块（已向量化文档的操作即时生效） */
function editable(row) {
  return row.cleanStatus !== 'FILTERED'
}
function deletable(row) {
  return row.cleanStatus !== 'FILTERED'
}
/** 插入锚点：任意未删除分块（新块插到该块之后） */
function insertable(row) {
  return row.cleanStatus !== 'FILTERED'
}

/** 勾选：合并（两阶段均可）与批量处置用（排除已删除） */
function selectableForSelect(row) {
  return row.cleanStatus !== 'FILTERED'
}

function onSelection(rows) {
  selectedRows.value = rows
}

async function loadKbs() {
  kbs.value = await kbApi.list()
}

/** 文档列表 = 初洗门精修中（ACCEPTED）+ 知识库全部常规文档（去重） */
async function load() {
  if (!kbId.value) return
  loading.value = true
  try {
    const [queue, all] = await Promise.all([curateApi.queue(kbId.value), docApi.list(kbId.value)])
    const accDocs = queue.filter((d) => d.curateStatus === 'ACCEPTED')
    const accIds = new Set(accDocs.map((d) => String(d.docId)))
    const normalDocs = (all || [])
      .filter((d) => !accIds.has(String(d.id)))
      .map((d) => ({ docId: d.id, fileName: d.fileName, parseStatus: d.parseStatus, curateStatus: null,
                     chunkCount: d.chunkCount ?? 0, suspectCount: d.suspectCount ?? 0 }))
    docs.value = [
      ...accDocs.map((d) => ({ docId: d.docId, fileName: d.fileName, parseStatus: 'SUCCESS',
                               curateStatus: d.curateStatus, chunkCount: d.chunkCount ?? 0, suspectCount: d.suspectCount ?? 0 })),
      ...normalDocs
    ]
  } finally {
    loading.value = false
  }
}

async function selectDoc(id) {
  docId.value = id
  chunks.value = []
  selectedRows.value = []
  total.value = 0
  suspectCount.value = 0
  if (!id) return
  const d = docs.value.find((x) => String(x.docId) === String(id))
  isCurate.value = !!(d && d.curateStatus === 'ACCEPTED')
  await loadPage(0)
}

/** 拉取指定页（后端排序/过滤）：sort=suspect|seq，onlySuspect 转为 cleanStatus=SUSPECT 过滤 */
async function loadPage(p = page.value) {
  if (!docId.value) return
  loading.value = true
  try {
    page.value = p
    pageIndex.value = p + 1
    const status = onlySuspect.value ? 'SUSPECT' : ''
    const resp = isCurate.value
      ? await curateApi.chunkPage(docId.value, p, pageSize.value, sortMode.value, status)
      : await docApi.chunkPage(docId.value, p, pageSize.value, status)
    chunks.value = normalizeChunks(resp.items)
    total.value = resp.total
    suspectCount.value = resp.suspectCount
    selectedRows.value = []
  } finally {
    loading.value = false
  }
}

/** 排序 / 只看待审核变化：回第 1 页 */
async function onFilterChange() {
  if (!docId.value) return
  await loadPage(0)
}

async function onPageChange(p) {
  await loadPage(p - 1)
}

async function onSizeChange() {
  await loadPage(0)
}

/**
 * 单块操作后原地更新行（不整页重拉）：按 chunkId 替换；
 * 「只看待审核」下该行已不再是待审核则从当前页移除（页空时自动回退一页）。
 */
function applyRowUpdate(newRow) {
  const norm = { ...newRow, chunkId: newRow.chunkId ?? newRow.id }
  const idx = chunks.value.findIndex((c) => c.chunkId === norm.chunkId)
  if (idx < 0) return
  if (onlySuspect.value && norm.cleanStatus !== 'SUSPECT') {
    chunks.value.splice(idx, 1)
    total.value = Math.max(0, total.value - 1)
    if (!chunks.value.length && page.value > 0) {
      loadPage(page.value - 1)
    }
  } else {
    chunks.value.splice(idx, 1, norm)
  }
}

function normalizeChunks(items) {
  return (items || []).map((chunk) => ({ ...chunk, chunkId: chunk.chunkId ?? chunk.id }))
}

function hasChunkId(row) {
  if (row?.chunkId != null) return true
  ElMessage.error('分块标识缺失，请刷新页面后重试')
  return false
}

async function onKbChange() {
  docId.value = null
  isCurate.value = false
  chunks.value = []
  await load()
  await selectDoc(null)
}

async function onDocChange(id) {
  await selectDoc(id)
}

async function init() {
  await loadKbs()
  const preferDocId = route.query.docId
  if (preferDocId) {
    // 深链直达：先查精修/待审核队列，再回退到知识库全量文档列表定位
    for (const kb of kbs.value) {
      kbId.value = kb.id
      await load()
      const found = docs.value.find((d) => String(d.docId) === String(preferDocId))
      if (found) {
        await selectDoc(found.docId)
        return
      }
    }
    kbId.value = null
    docs.value = []
    ElMessage.warning('未找到该文档，已回到分块工作台')
    return
  }
  kbId.value = kbs.value[0]?.id ?? null
  if (kbId.value) {
    await load()
    await selectDoc(null)
  }
}

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

function openEdit(row) {
  if (!hasChunkId(row)) return
  editRow.value = row
  editTitle.value = row.title || ''
  editContent.value = row.content || ''
  editVisible.value = true
}

async function saveEdit() {
  if (!hasChunkId(editRow.value)) return
  editSaving.value = true
  try {
    const wasSuspect = editRow.value.cleanStatus === 'SUSPECT'
    let resp
    if (isCurate.value) {
      resp = await curateApi.editChunk(docId.value, editRow.value.chunkId, { content: editContent.value, title: editTitle.value })
      ElMessage.success('已保存（确认后统一向量化）')
    } else {
      resp = await reviewApi.edit(editRow.value.chunkId, { content: editContent.value, title: editTitle.value })
      ElMessage.success('已保存并重新向量化')
    }
    editVisible.value = false
    if (wasSuspect && !isCurate.value) suspectCount.value = Math.max(0, suspectCount.value - 1)
    applyRowUpdate(resp)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    editSaving.value = false
  }
}

function openCreate(row) {
  if (!hasChunkId(row)) return
  createAnchor.value = row
  createTitle.value = ''
  createContent.value = ''
  createVisible.value = true
}

async function saveCreate() {
  if (!createAnchor.value || !docId.value) return
  createSaving.value = true
  try {
    const data = { afterChunkId: createAnchor.value.chunkId, content: createContent.value, title: createTitle.value }
    if (isCurate.value) {
      await curateApi.addChunk(docId.value, data)
      ElMessage.success('已插入（确认后统一向量化）')
    } else {
      await reviewApi.addChunk(docId.value, data)
      ElMessage.success('已插入并向量化')
    }
    createVisible.value = false
    total.value += 1
    // 序号让位重排影响当前页及之后，整页重拉
    await loadPage()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    createSaving.value = false
  }
}

async function keep(row) {
  if (!hasChunkId(row)) return
  try {
    let resp
    if (isCurate.value) {
      resp = await curateApi.keepChunk(docId.value, row.chunkId)
      ElMessage.success('已保留（已审核）')
    } else {
      resp = await reviewApi.keep(row.chunkId)
      ElMessage.success('已保留并向量化')
    }
    if (row.cleanStatus === 'SUSPECT') suspectCount.value = Math.max(0, suspectCount.value - 1)
    applyRowUpdate(resp)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function unkeep(row) {
  if (!hasChunkId(row)) return
  try {
    let resp
    if (isCurate.value) {
      resp = await curateApi.unkeepChunk(docId.value, row.chunkId)
      ElMessage.success('已回退待审核')
    } else {
      resp = await reviewApi.unkeep(row.chunkId)
      ElMessage.success('已回退待审核（已移出检索）')
    }
    suspectCount.value += 1
    applyRowUpdate(resp)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function drop(row) {
  if (!hasChunkId(row)) return
  const tip = isCurate.value
    ? `确定删除分块 #${row.seq}？删除后该块不参与向量化（记录保留）。`
    : `确定删除分块 #${row.seq}？删除后立即移出检索（记录保留）。`
  try {
    await ElMessageBox.confirm(tip, '删除分块', { type: 'warning' })
  } catch {
    return
  }
  try {
    let resp
    if (isCurate.value) {
      resp = await curateApi.dropChunk(docId.value, row.chunkId)
    } else {
      resp = await reviewApi.drop(row.chunkId)
    }
    ElMessage.success('已删除')
    if (row.cleanStatus === 'SUSPECT') suspectCount.value = Math.max(0, suspectCount.value - 1)
    applyRowUpdate(resp)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

/** 一键通过：保留本文档全部待审核分块（跨页，含未显示的），弹窗确认防误触 */
async function keepAll() {
  if (!docId.value || suspectCount.value <= 0) return
  try {
    await ElMessageBox.confirm(
      `将保留本文档全部 ${suspectCount.value} 个待审核分块（跨页，含未显示的分块）并标记为已审核。确定执行？`,
      '一键通过', { type: 'warning', confirmButtonText: '全部保留', cancelButtonText: '取消' })
  } catch {
    return
  }
  batchKeeping.value = true
  try {
    if (isCurate.value) {
      const r = await curateApi.batchKeep(docId.value)
      ElMessage.success(`一键通过完成：保留 ${r.kept}${r.failed ? `，失败 ${r.failed}` : ''}`)
    } else {
      const ids = await docApi.suspectIds(docId.value)
      if (!ids.length) {
        ElMessage.info('没有待审核分块')
        return
      }
      const results = await reviewApi.batch(ids, 'keep')
      const ok = results.filter((x) => x.success).length
      ElMessage.success(`一键通过完成：保留 ${ok}${ids.length - ok ? `，失败 ${ids.length - ok}` : ''}`)
    }
    await loadPage(onlySuspect.value ? 0 : page.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    batchKeeping.value = false
  }
}

function openMerge() {
  if (!canMerge.value) return
  // 按文档顺序（seq 升序）展示两个候选，默认选 seq 小者为目标
  mergeCandidates.value = [...selectedRows.value].sort((x, y) => x.seq - y.seq)
  mergeTargetId.value = mergeCandidates.value[0]?.chunkId ?? null
  mergeVisible.value = true
}

async function doMerge() {
  const target = mergeCandidates.value.find((c) => c.chunkId === mergeTargetId.value)
  if (!target) return
  const source = mergeCandidates.value.find((c) => c.chunkId !== mergeTargetId.value)
  if (!hasChunkId(target) || !hasChunkId(source)) return
  mergeSaving.value = true
  try {
    if (isCurate.value) {
      const r = await curateApi.mergeChunk(docId.value, { sourceId: source.chunkId, targetId: target.chunkId })
      ElMessage.success(`已合并为 #${r.seq}（另一分块已删除，目标块回到待审核）`)
    } else {
      const r = await reviewApi.mergeChunk(docId.value, { sourceId: source.chunkId, targetId: target.chunkId })
      ElMessage.success(`已合并为 #${r.seq}（目标块已重新向量化）`)
    }
    mergeVisible.value = false
    selectedRows.value = []
    // 序号/内容变化影响整页，重拉当前页
    await loadPage()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    mergeSaving.value = false
  }
}

async function confirm() {
  if (!canConfirm.value) return
  try {
    await ElMessageBox.confirm('确认完成后将统一向量化全部已审核分块，文档回到常规状态。之后仍可在本页编辑分块（操作即时向量化）。', '确认完成并向量化', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
  } catch {
    return
  }
  acting.value = true
  try {
    await curateApi.confirm(docId.value)
    ElMessage.success('已触发向量化，文档已回到常规状态')
    await load()
    await selectDoc(docId.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

async function batchKeep() {
  const rows = selectedRows.value.filter((r) => r.cleanStatus === 'SUSPECT')
  if (rows.some((row) => !hasChunkId(row))) return
  const ids = rows.map((row) => row.chunkId)
  if (!ids.length) return
  await ElMessageBox.confirm(`批量保留 ${ids.length} 个待审核分块并向量化？`, '批量保留', { type: 'info' })
  await reviewApi.batch(ids, 'keep')
  ElMessage.success('批量保留完成')
  await loadPage()
}

async function batchDrop() {
  const rows = selectedRows.value.filter((r) => r.cleanStatus === 'SUSPECT')
  if (rows.some((row) => !hasChunkId(row))) return
  const ids = rows.map((row) => row.chunkId)
  if (!ids.length) return
  await ElMessageBox.confirm(`确定批量删除 ${ids.length} 个待审核分块？`, '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await reviewApi.batch(ids, 'drop')
  ElMessage.success('批量删除完成')
  await loadPage()
}

onMounted(init)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.hint { margin-bottom: 12px; }
.content-cell {
  max-height: 72px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px;
}
.edit-reason { background: #fdf6ec; border: 1px solid #faecd8; border-radius: 4px; padding: 8px 12px; margin-bottom: 12px; font-size: 13px; color: #8a6d3b; }
.create-tip { background: #f0f9eb; border: 1px solid #e1f3d8; border-radius: 4px; padding: 8px 12px; margin-bottom: 12px; font-size: 13px; color: #67c23a; }
.pager { display: flex; justify-content: flex-end; margin-top: 8px; }
.merge-opts { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.merge-opt { display: flex; align-items: flex-start; white-space: normal; width: 100%; margin-right: 0; height: auto; padding: 10px 12px; }
.merge-opt-title { font-weight: 600; margin-bottom: 4px; }
.merge-opt-page { color: #909399; font-weight: 400; font-size: 12px; }
.merge-opt-content {
  max-height: 64px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px; color: #606266;
}
</style>
