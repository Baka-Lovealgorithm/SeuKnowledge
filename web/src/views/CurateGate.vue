<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 200px" filterable clearable @change="onKbChange">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-select v-model="docId" placeholder="选择文档（初洗中）" style="width: 320px" filterable
                 :disabled="!kbId || !docs.length" @change="onDocChange">
        <el-option v-for="d in docs" :key="d.docId" :label="docLabel(d)" :value="d.docId" />
      </el-select>
      <template v-if="status">
        <el-tag type="warning">初洗中 · 待决断</el-tag>
        <span class="stat">共 {{ info.chunkCount ?? 0 }} chunk ｜ 待审核 {{ suspectCount }}</span>
      </template>
      <el-switch v-if="docId" v-model="onlySuspect" active-text="只看待审核" size="small" @change="onFilterChange" />
      <div class="spacer" />
      <template v-if="auth.canWrite && status === 'PREVIEWING'">
        <el-button type="primary" :loading="mdLoading" @click="openMd">编辑 md（清洗后重新分块）</el-button>
        <el-button type="success" :loading="acting" @click="accept">接受并进入精修</el-button>
      </template>
    </div>

    <el-empty v-if="!loading && !docId" :description="kbId ? '该知识库暂无初洗中的文档' : '请选择知识库（上传文档时勾选「初洗门」后，文档会出现在这里）'" />

    <template v-else>
      <el-alert v-if="status === 'PREVIEWING'" type="info" :closable="false" class="hint"
                title="初洗中：分块结果已生成但未向量化。此阶段全部分块只读；可「编辑 md」在线清洗后重新分块（可反复），或「接受」进入文档精修。" />

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
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="docId" class="pager">
        <el-pagination v-model:current-page="pageIndex" v-model:page-size="pageSize"
                       :total="total" :page-sizes="[20, 50, 100, 200]"
                       layout="total, sizes, prev, pager, next"
                       @current-change="onPageChange" @size-change="onSizeChange" />
      </div>
    </template>

    <!-- md 在线编辑（分屏：左源码右渲染预览） -->
    <el-dialog v-model="mdVisible" title="初洗 md（整篇编辑，保存后重新分块，仍停在初洗中）" width="94%" top="3vh">
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

    <!-- chunk 详情（只读，完整内容） -->
    <ChunkDetail v-model="detailVisible" :row="detailRow" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { curateApi, kbApi } from '../api'
import { renderMarkdown } from '../utils/markdown'
import { useAuthStore } from '../stores/auth'
import ChunkDetail from '../components/ChunkDetail.vue'

const route = useRoute()
const router = useRouter()
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
const detailVisible = ref(false)
const detailRow = ref(null)

const status = computed(() => info.value.curateStatus)
const suspectCount = computed(() => info.value.suspectCount ?? 0)

/** 分页状态：page 0 基（请求用），pageIndex 1 基（el-pagination 用）；初洗列表固定 SUSPECT 优先排序 */
const page = ref(0)
const pageIndex = ref(1)
const pageSize = ref(20)
const total = ref(0)
const onlySuspect = ref(false)

const mdPreview = computed(() => renderMarkdown(mdText.value.replace(/<!--\s*PAGE\s*\d+\s*-->/g, '\n\n---\n\n')))

const cleanTagType = (row) => {
  if (row.cleanStatus === 'SUSPECT') return 'warning'
  if (row.cleanStatus === 'FILTERED') return 'info'
  return 'success'
}
const cleanTagText = (row) => (row.cleanStatus === 'SUSPECT' ? '待审核' : row.cleanStatus === 'FILTERED' ? '已删除' : '正常')

const statusText = (s) => (s === 'PREVIEWING' ? '初洗中' : s === 'ACCEPTED' ? '精修中' : s || '')
const docLabel = (d) => `${d.fileName}（${statusText(d.curateStatus)} · 待审核 ${d.suspectCount ?? 0}）`

async function load() {
  await loadPage(page.value)
}

/** 分页加载：初洗列表后端 SUSPECT 优先排序 + 可选只看待审核 */
async function loadPage(p = 0) {
  if (!docId.value) return
  loading.value = true
  try {
    page.value = p
    pageIndex.value = p + 1
    const [i, resp] = await Promise.all([
      curateApi.info(docId.value),
      curateApi.chunkPage(docId.value, p, pageSize.value, 'suspect', onlySuspect.value ? 'SUSPECT' : '')
    ])
    info.value = i
    chunks.value = resp.items
    total.value = resp.total
  } finally {
    loading.value = false
  }
}

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

/** 初洗队列：仅展示初洗中（PREVIEWING）文档；原始队列保留用于深链状态判定 */
async function loadQueue() {
  const full = kbId.value ? await curateApi.queue(kbId.value) : []
  docs.value = full.filter((d) => d.curateStatus === 'PREVIEWING')
  return full
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
    // 深链直达：定位文档所在 KB 并加载
    for (const kb of kbs.value) {
      kbId.value = kb.id
      const full = await loadQueue()
      const found = full.find((d) => String(d.docId) === String(preferDocId))
      if (found) {
        if (found.curateStatus === 'ACCEPTED') {
          // 已进入精修：跳到文档精修页处理
          ElMessage.info('该文档已进入精修阶段，已跳转到「文档分块」页')
          router.replace(`/review?docId=${found.docId}`)
          return
        }
        docId.value = found.docId
        await load()
        return
      }
    }
    // 文档不在初洗流程（已确认/已删除/未启用）
    kbId.value = null
    docs.value = []
    ElMessage.warning('该文档不在初洗流程中，已回到初洗工作台')
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
    ElMessage.success(`已保存 v${r.version} 并重新分块：共 ${r.chunkCount} 块，待审核 ${r.suspect} 块`)
    mdVisible.value = false
    await loadPage(0)
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
    ElMessage.success('已接受，md 不可再编辑，分块进入精修阶段（「文档分块」页处理）')
    router.push(`/review?docId=${docId.value}`)
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
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
.pager { display: flex; justify-content: flex-end; margin-top: 8px; }
</style>
