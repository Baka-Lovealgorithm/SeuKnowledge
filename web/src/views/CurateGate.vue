<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 200px" filterable clearable @change="onKbChange">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-select v-model="docId" placeholder="选择文档（策展中）" style="width: 320px" filterable
                 :disabled="!kbId || !docs.length" @change="onDocChange">
        <el-option v-for="d in docs" :key="d.docId" :label="docLabel(d)" :value="d.docId" />
      </el-select>
      <template v-if="status">
        <el-tag v-if="status === 'PREVIEWING'" type="warning">展示门 · 待决断</el-tag>
        <el-tag v-else-if="status === 'ACCEPTED'" type="warning">已接受 · 待确认</el-tag>
        <span class="stat">共 {{ chunks.length }} chunk ｜ SUSPECT {{ suspectCount }}</span>
      </template>
      <div class="spacer" />
      <template v-if="auth.canWrite && status === 'PREVIEWING'">
        <el-button type="primary" :loading="mdLoading" @click="openMd">编辑 md（清洗后重新分块）</el-button>
        <el-button type="success" :loading="acting" @click="accept">接受并进入精修</el-button>
      </template>
      <template v-else-if="auth.canWrite && status === 'ACCEPTED'">
        <el-button type="success" :loading="acting" @click="confirm">确认完成并向量化</el-button>
      </template>
    </div>

    <el-empty v-if="!loading && !docId" :description="kbId ? '该知识库暂无策展中的文档' : '请选择知识库（上传文档时勾选「策展门」后，文档会出现在这里）'" />

    <template v-else>
      <el-alert v-if="status === 'PREVIEWING'" type="info" :closable="false" class="hint"
                title="展示门：分块结果已生成但未向量化。可「编辑 md」在线清洗后重新分块（可反复），或「接受」进入逐块精修；未决断前不会进入向量库。" />
      <el-alert v-else-if="status === 'ACCEPTED'" type="warning" :closable="false" class="hint"
                :title="`已接受：后悔通道已关闭（不可再编辑 md/重新分块）。可逐块编辑/保留/删除；「确认完成」后统一向量化。${suspectCount > 0 ? `仍有 ${suspectCount} 个 SUSPECT 未处置，确认后它们不进向量库（可在清洗复核页补处理）。` : ''}`" />

      <el-table :data="chunks" v-loading="loading" border size="small">
        <el-table-column prop="seq" label="序号" width="70" />
        <el-table-column prop="pageNum" label="页码" width="70" />
        <el-table-column prop="title" label="所属标题" min-width="130" show-overflow-tooltip />
        <el-table-column label="内容" min-width="320">
          <template #default="{ row }">
            <div class="content-cell">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="清洗" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="row.cleanReason" :content="row.cleanReason" placement="top">
              <el-tag :type="cleanTagType(row)" size="small">{{ cleanTagText(row) }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="cleanTagType(row)" size="small">{{ cleanTagText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
            <template v-if="auth.canWrite && status === 'ACCEPTED'">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.cleanStatus === 'SUSPECT'" link type="success" @click="keep(row)">保留</el-button>
              <el-button link type="danger" @click="drop(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- md 在线编辑（分屏：左源码右渲染预览） -->
    <el-dialog v-model="mdVisible" title="清洗 md（整篇编辑，保存后重新分块，仍停在展示门）" width="94%" top="3vh">
      <div class="md-split">
        <el-input v-model="mdText" type="textarea" :rows="26" resize="none" class="md-src"
                  placeholder="编辑 markdown 原文。&#10;&#10;<!-- PAGE N --> 为页标记：可移动/删除（删标记的段落并入前一页），整篇无标记将归为第 1 页。&#10;保存后自动重新分块并重新运行清洗规则（页眉页脚/碎片/重复）。" />
        <div class="md-preview" v-html="mdPreview"></div>
      </div>
      <template #footer>
        <el-button @click="mdVisible = false">取消</el-button>
        <el-button type="primary" :loading="mdSaving" @click="saveMd">保存并重新分块</el-button>
      </template>
    </el-dialog>

    <!-- chunk 精修编辑 -->
    <el-dialog v-model="editVisible" :title="`编辑 chunk #${editRow?.seq || ''}（${editRow?.docName || ''}）`" width="760px">
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
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- chunk 详情（只读，完整内容） -->
    <ChunkDetail v-model="detailVisible" :row="detailRow" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { curateApi, kbApi } from '../api'
import { renderMarkdown } from '../utils/markdown'
import { useAuthStore } from '../stores/auth'
import ChunkDetail from '../components/ChunkDetail.vue'

const route = useRoute()
const auth = useAuthStore()

const kbs = ref([])
const kbId = ref(null)
const docs = ref([])
const docId = ref(null)
const info = ref({})
const chunks = ref([])
const loading = ref(false)
const acting = ref(false)
const mdLoading = ref(false)
const mdVisible = ref(false)
const mdSaving = ref(false)
const mdText = ref('')
const editVisible = ref(false)
const editSaving = ref(false)
const editRow = ref(null)
const editTitle = ref('')
const editContent = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)

const status = computed(() => info.value.curateStatus)
const suspectCount = computed(() => info.value.suspectCount ?? 0)

const mdPreview = computed(() => renderMarkdown(mdText.value.replace(/<!--\s*PAGE\s*\d+\s*-->/g, '\n\n---\n\n')))

const cleanTagType = (row) => {
  if (row.cleanStatus === 'SUSPECT') return 'warning'
  if (row.cleanStatus === 'FILTERED') return 'info'
  return 'success'
}
const cleanTagText = (row) => row.cleanStatus || 'KEEP'

const statusText = (s) => (s === 'PREVIEWING' ? '展示门' : s === 'ACCEPTED' ? '待确认' : s || '')
const docLabel = (d) => `${d.fileName}（${statusText(d.curateStatus)} · SUSPECT ${d.suspectCount ?? 0}）`

async function load() {
  if (!docId.value) return
  loading.value = true
  try {
    const [i, cs] = await Promise.all([curateApi.info(docId.value), curateApi.chunks(docId.value)])
    info.value = i
    chunks.value = cs
  } finally {
    loading.value = false
  }
}

async function loadQueue() {
  docs.value = kbId.value ? await curateApi.queue(kbId.value) : []
}

/** 选中队列中的第一个文档；无则清空详情 */
async function selectFirstDoc() {
  docId.value = docs.value[0]?.docId ?? null
  info.value = {}
  chunks.value = []
  if (docId.value) await load()
}

async function onKbChange() {
  await loadQueue()
  await selectFirstDoc()
}

async function onDocChange(id) {
  docId.value = id
  info.value = {}
  chunks.value = []
  if (id) await load()
}

async function init() {
  kbs.value = await kbApi.list()
  const preferDocId = route.params.docId
  if (preferDocId) {
    // 深链接直达：定位文档所在 KB 并加载
    for (const kb of kbs.value) {
      kbId.value = kb.id
      await loadQueue()
      const found = docs.value.find((d) => String(d.docId) === String(preferDocId))
      if (found) {
        docId.value = found.docId
        await load()
        return
      }
    }
    // 文档不在策展流程（已确认/已删除/未启用）
    kbId.value = null
    docs.value = []
    ElMessage.warning('该文档不在策展流程中，已回到策展工作台')
    await selectFirstKb()
    return
  }
  await selectFirstKb()
}

async function selectFirstKb() {
  kbId.value = kbs.value[0]?.id ?? null
  if (!kbId.value) return
  await loadQueue()
  await selectFirstDoc()
}

async function openMd() {
  mdLoading.value = true
  try {
    mdText.value = await curateApi.getMd(docId.value)
    mdVisible.value = true
  } finally {
    mdLoading.value = false
  }
}

async function saveMd() {
  if (!mdText.value || !mdText.value.trim()) {
    ElMessage.warning('md 内容不能为空')
    return
  }
  mdSaving.value = true
  try {
    const r = await curateApi.saveMd(docId.value, mdText.value)
    ElMessage.success(`已保存 v${r.version} 并重新分块：${r.chunkCount} chunk（SUSPECT ${r.suspect}）`)
    mdVisible.value = false
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    mdSaving.value = false
  }
}

async function accept() {
  acting.value = true
  try {
    await curateApi.accept(docId.value)
    ElMessage.success('已接受，进入逐块精修（后悔通道已关闭）')
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

async function confirm() {
  let tip = '确认完成后将统一向量化全部通过 chunk，文档回到常规状态，之后不可再编辑。'
  if (suspectCount.value > 0) {
    tip = `仍有 ${suspectCount.value} 个 SUSPECT 未处置，确认后它们不会进入向量库（可在清洗复核页补处理）。` + tip
  }
  try {
    await ElMessageBox.confirm(tip, '确认完成并向量化', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
  } catch {
    return
  }
  acting.value = true
  try {
    await curateApi.confirm(docId.value)
    ElMessage.success('已触发向量化，文档已回到常规状态')
    // 队列刷新：确认过的文档已离开队列，自动切到下一个
    await loadQueue()
    await selectFirstDoc()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

function openEdit(row) {
  editRow.value = row
  editTitle.value = row.title || ''
  editContent.value = row.content || ''
  editVisible.value = true
}

async function saveEdit() {
  if (!editContent.value || !editContent.value.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  editSaving.value = true
  try {
    await curateApi.editChunk(docId.value, editRow.value.chunkId, { content: editContent.value, title: editTitle.value })
    ElMessage.success('已保存（确认后统一向量化）')
    editVisible.value = false
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    editSaving.value = false
  }
}

async function keep(row) {
  try {
    await curateApi.keepChunk(docId.value, row.chunkId)
    ElMessage.success('已保留')
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

async function drop(row) {
  try {
    await ElMessageBox.confirm(`确定删除 chunk #${row.seq}？删除后该块不进向量库（记录保留）。`, '删除 chunk', { type: 'warning' })
  } catch {
    return
  }
  try {
    await curateApi.dropChunk(docId.value, row.chunkId)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  }
}

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

onMounted(init)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.stat { color: #909399; font-size: 13px; }
.spacer { flex: 1; }
.hint { margin-bottom: 12px; }
.content-cell {
  max-height: 60px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px;
}
.md-split { display: flex; gap: 12px; height: 62vh; }
.md-src { flex: 1; }
.md-src :deep(textarea) { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 13px; line-height: 1.6; }
.md-preview { flex: 1; overflow: auto; border: 1px solid #e4e7ed; border-radius: 4px; padding: 12px; background: #fff; }
</style>
