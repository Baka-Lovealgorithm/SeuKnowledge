<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 200px" @change="onKbChange">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-select v-model="docId" placeholder="选择文档（精修）" style="width: 320px" filterable clearable
                 :disabled="!kbId || !docs.length" @change="onDocChange">
        <el-option v-for="d in docs" :key="d.docId" :label="docLabel(d)" :value="d.docId" />
      </el-select>
      <el-tag v-if="docId" :type="isCurate ? 'warning' : 'info'" size="small" effect="plain">
        {{ isCurate ? '精修中 · 初洗门已接受' : '普通文档 · 待审核复核' }}
      </el-tag>
      <el-radio-group v-if="docId" v-model="sortMode" size="small">
        <el-radio-button value="suspect">待审核优先</el-radio-button>
        <el-radio-button value="seq">自然顺序</el-radio-button>
      </el-radio-group>
      <div class="spacer" />
      <template v-if="auth.canWrite && isCurate">
        <el-button type="primary" :disabled="!canMerge" :loading="acting" title="勾选两块相邻（|seq差|=1）分块后合并"
                   @click="openMerge">合并{{ selectedRows.length ? ` (${selectedRows.length})` : '' }}</el-button>
        <el-button type="success" :disabled="!canConfirm" :loading="acting" title="全部未删除分块均为已审核后可用"
                   @click="confirm">确认完成并向量化</el-button>
      </template>
      <template v-else-if="auth.canWrite && docId && selectedRows.length">
        <el-button type="primary" size="small" :loading="acting" @click="batchKeep">批量保留 ({{ selectedRows.length }})</el-button>
        <el-button type="danger" size="small" :loading="acting" @click="batchDrop">批量删除 ({{ selectedRows.length }})</el-button>
      </template>
    </div>

    <el-empty v-if="!loading && !docId" :description="kbId ? '该知识库暂无需要精修的文档' : '请选择知识库'" />

    <template v-else>
      <el-alert v-if="docId && isCurate" type="warning" :closable="false" class="hint"
                :title="`精修中：md 已冻结。可对分块编辑/删除/合并/保留（保留后变「已审核」，可回退「待审核」；正常分块默认已审核）；无待审核分块后，「确认完成并向量化」统一入库。${pendingCount > 0 ? `还有 ${pendingCount} 个待审核分块。` : '全部已审核，可以确认向量化。'}`" />
      <el-alert v-else-if="docId" type="info" :closable="false" class="hint"
                title="普通文档：仅待审核（SUSPECT）分块可编辑/保留/删除（保存后即向量化）；已审核分块可回退待审核。合并仅支持未向量化（初洗门精修中）的分块。" />

      <el-table :data="sortedChunks" v-loading="loading" border size="small" @selection-change="onSelection">
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
        <el-table-column label="操作" :width="auth.canWrite ? 300 : 80" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
            <template v-if="auth.canWrite && docId">
              <el-button v-if="editable(row)" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.cleanStatus === 'SUSPECT'" link type="success" @click="keep(row)">保留</el-button>
              <el-button v-if="row.cleanStatus !== 'SUSPECT' && row.cleanStatus !== 'FILTERED'" link type="warning" @click="unkeep(row)">回退</el-button>
              <el-button v-if="deletable(row)" link type="danger" @click="drop(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && docId && !sortedChunks.length" description="该文档暂无分块" />
    </template>

    <!-- chunk 精修编辑 -->
    <el-dialog v-model="editVisible" :title="`编辑 chunk #${editRow?.seq || ''}（${editRow?.docName || ''}）`" width="760px">
      <div class="edit-reason" v-if="editRow && editRow.cleanReason">
        <b>命中规则：</b>{{ editRow.cleanReason }}
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
          {{ isCurate ? '保存' : '保存并保留' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- chunk 详情（只读，完整内容） -->
    <ChunkDetail v-model="detailVisible" :row="detailRow" />

    <!-- 合并相邻 chunk：选择保留的目标块 -->
    <el-dialog v-model="mergeVisible" title="合并相邻 chunk（选择目标）" width="720px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" class="merge-tip"
                title="将两块内容按文档顺序拼接（seq 小者在前）写入目标块；目标块保留原 id/序号，并置回待审核（确认前不向量化）；另一块将被删除（记录保留）。请选择哪一块作为合并后的目标：" />
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
/** 当前文档是否初洗门已接受（精修中）：true → curateApi 操作；false → reviewApi 操作 */
const isCurate = ref(false)
const chunks = ref([])
const sortMode = ref('suspect')
const loading = ref(false)
const acting = ref(false)
const selectedRows = ref([])
const editVisible = ref(false)
const editSaving = ref(false)
const editRow = ref(null)
const editTitle = ref('')
const editContent = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)
const mergeVisible = ref(false)
const mergeSaving = ref(false)
const mergeTargetId = ref(null)
const mergeCandidates = ref([])

/** 待审核（SUSPECT）计数 */
const pendingCount = computed(() => chunks.value.filter((c) => c.cleanStatus === 'SUSPECT').length)
/** 确认完成前置：精修中 + 无待审核分块 + 至少一个未删除分块（正常分块默认已审核） */
const canConfirm = computed(() =>
  isCurate.value && chunks.value.length > 0
  && pendingCount.value === 0
  && chunks.value.some((c) => c.cleanStatus !== 'FILTERED')
)
/** 合并可用性：精修中 + 恰好勾选 2 块 + 同一文档 + 相邻（|seq差|=1） */
const canMerge = computed(() => {
  if (!isCurate.value || !auth.canWrite) return false
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
const docLabel = (d) => `${d.fileName}（${d.curateStatus === 'ACCEPTED' ? '精修中' : '待审核'} · 待审核 ${d.suspectCount ?? 0}）`

/** 编辑可用性：精修中任意非已删除块；普通文档仅待审核块 */
function editable(row) {
  if (isCurate.value) return row.cleanStatus !== 'FILTERED'
  return row.cleanStatus === 'SUSPECT'
}
/** 删除可用性：精修中任意非已删除块；普通文档仅待审核块 */
function deletable(row) {
  if (isCurate.value) return row.cleanStatus !== 'FILTERED'
  return row.cleanStatus === 'SUSPECT'
}

/** 排序：待审核优先（SUSPECT 前置，其余按 seq）或自然顺序（seq） */
const sortedChunks = computed(() => {
  const list = [...chunks.value]
  if (sortMode.value === 'seq') {
    return list.sort((x, y) => x.seq - y.seq)
  }
  return list.sort((x, y) => {
    const xa = x.cleanStatus === 'SUSPECT' ? 0 : 1
    const ya = y.cleanStatus === 'SUSPECT' ? 0 : 1
    if (xa !== ya) return xa - ya
    return x.seq - y.seq
  })
})

/** 勾选：合并用（排除已删除）；普通文档批量用（仅待审核） */
function selectableForSelect(row) {
  if (row.cleanStatus === 'FILTERED') return false
  if (!isCurate.value) return row.cleanStatus === 'SUSPECT'
  return true
}

function onSelection(rows) {
  selectedRows.value = rows
}

async function loadKbs() {
  kbs.value = await kbApi.list()
}

/** 文档列表 = 初洗门精修中（ACCEPTED）+ 普通含待审核分块的文档（去重，精修中优先） */
async function load() {
  if (!kbId.value) return
  loading.value = true
  try {
    const [queue, sus] = await Promise.all([curateApi.queue(kbId.value), reviewApi.suspectQueue(kbId.value)])
    const accDocs = queue.filter((d) => d.curateStatus === 'ACCEPTED')
    const accIds = new Set(accDocs.map((d) => String(d.docId)))
    const susMap = new Map()
    for (const c of sus) {
      const k = String(c.docId)
      if (!accIds.has(k)) {
        const item = susMap.get(k) || { docId: c.docId, fileName: c.docName || '', curateStatus: null, suspectCount: 0 }
        item.suspectCount += 1
        susMap.set(k, item)
      }
    }
    docs.value = [...accDocs.map((d) => ({ docId: d.docId, fileName: d.fileName, curateStatus: d.curateStatus, suspectCount: d.suspectCount ?? 0 })), ...susMap.values()]
  } finally {
    loading.value = false
  }
}

async function selectDoc(id) {
  docId.value = id
  chunks.value = []
  selectedRows.value = []
  if (!id) return
  const d = docs.value.find((x) => String(x.docId) === String(id))
  isCurate.value = !!(d && d.curateStatus === 'ACCEPTED')
  loading.value = true
  try {
    if (isCurate.value) {
      chunks.value = normalizeChunks(await curateApi.chunks(id))
    } else {
      // 普通文档接口返回 ChunkResponse.id；初洗门接口返回 ChunkReviewResponse.chunkId。
      // 统一为 chunkId，避免编辑/审核动作拼出 /chunks/undefined/... 请求。
      chunks.value = normalizeChunks(await docApi.chunks(id))
    }
  } finally {
    loading.value = false
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
    // 深链直达：定位文档所在 KB 并加载
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
    ElMessage.warning('该文档不在精修流程中，已回到精修工作台')
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
    if (isCurate.value) {
      await curateApi.editChunk(docId.value, editRow.value.chunkId, { content: editContent.value, title: editTitle.value })
      ElMessage.success('已保存（确认后统一向量化）')
    } else {
      await reviewApi.edit(editRow.value.chunkId, { content: editContent.value, title: editTitle.value })
      ElMessage.success('已保存并保留（已向量化）')
    }
    editVisible.value = false
    await selectDoc(docId.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    editSaving.value = false
  }
}

async function keep(row) {
  if (!hasChunkId(row)) return
  try {
    if (isCurate.value) {
      await curateApi.keepChunk(docId.value, row.chunkId)
      ElMessage.success('已保留（已审核）')
    } else {
      await reviewApi.keep(row.chunkId)
      ElMessage.success('已保留并向量化')
    }
    await selectDoc(docId.value)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function unkeep(row) {
  if (!hasChunkId(row)) return
  try {
    if (isCurate.value) {
      await curateApi.unkeepChunk(docId.value, row.chunkId)
      ElMessage.success('已回退待审核')
    } else {
      await reviewApi.unkeep(row.chunkId)
      ElMessage.success('已回退待审核（已移出向量库）')
    }
    await selectDoc(docId.value)
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function drop(row) {
  if (!hasChunkId(row)) return
  try {
    await ElMessageBox.confirm(`确定删除 chunk #${row.seq}？删除后该块不进向量库（记录保留）。`, '删除 chunk', { type: 'warning' })
  } catch {
    return
  }
  try {
    if (isCurate.value) {
      await curateApi.dropChunk(docId.value, row.chunkId)
    } else {
      await reviewApi.drop(row.chunkId)
    }
    ElMessage.success('已删除')
    await selectDoc(docId.value)
  } catch (e) {
    /* 拦截器已提示 */
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
    const r = await curateApi.mergeChunk(docId.value, { sourceId: source.chunkId, targetId: target.chunkId })
    ElMessage.success(`已合并为 #${r.seq}（目标块已置回待审核，确认前不向量化；源块已删除）`)
    mergeVisible.value = false
    selectedRows.value = []
    await selectDoc(docId.value)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    mergeSaving.value = false
  }
}

async function confirm() {
  if (!canConfirm.value) return
  try {
    await ElMessageBox.confirm('确认完成后将统一向量化全部已审核分块，文档回到常规状态，之后不可再编辑。', '确认完成并向量化', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
  } catch {
    return
  }
  acting.value = true
  try {
    await curateApi.confirm(docId.value)
    ElMessage.success('已触发向量化，文档已回到常规状态')
    await load()
    await selectDoc(null)
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
  await selectDoc(docId.value)
}

async function batchDrop() {
  const rows = selectedRows.value.filter((r) => r.cleanStatus === 'SUSPECT')
  if (rows.some((row) => !hasChunkId(row))) return
  const ids = rows.map((row) => row.chunkId)
  if (!ids.length) return
  await ElMessageBox.confirm(`确定批量删除 ${ids.length} 个待审核分块？`, '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await reviewApi.batch(ids, 'drop')
  ElMessage.success('批量删除完成')
  await selectDoc(docId.value)
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
.merge-tip { margin-bottom: 12px; }
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
