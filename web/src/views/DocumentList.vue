<template>
  <div>
    <div class="toolbar">
      <el-button @click="goBack">← 返回知识库</el-button>
      <el-checkbox v-model="curateOn" :disabled="!auth.canWrite" style="margin-left: 8px">
        解析后人工确认分段（初洗门）
      </el-checkbox>
      <el-upload
        v-if="auth.canWrite"
        :show-file-list="false"
        :http-request="doUpload"
        multiple
        accept=".txt,.md,.html,.pdf,.docx,.pptx,.xlsx,.xls"
      >
        <el-button type="primary">上传文档 (.txt/.md/.html/.pdf/.docx/.pptx/.xlsx/.xls)</el-button>
      </el-upload>
    </div>
    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="fileName" label="文件名" min-width="200" />
      <el-table-column prop="fileType" label="类型" width="80" />
      <el-table-column prop="fileSize" label="大小" width="100">
        <template #default="{ row }">{{ sizeText(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="解析状态" width="110">
        <template #default="{ row }">
          <el-tag :type="parseType(row.parseStatus)" size="small">{{ row.parseStatus }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="分块数" width="90" />
      <el-table-column label="待审核" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.suspectCount > 0" type="warning" size="small">{{ row.suspectCount }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column prop="errorMsg" label="错误信息" min-width="180" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="上传时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewChunks(row)">分块</el-button>
          <el-button v-if="auth.canWrite" link type="warning" @click="retry(row)">重试</el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="chunkVisible" :title="chunkTitle" width="860px">
      <el-table :data="chunks" size="small" border max-height="520">
        <el-table-column prop="seq" label="序号" width="70" />
        <el-table-column prop="title" label="所属标题" min-width="130" show-overflow-tooltip />
        <el-table-column prop="pageNum" label="页码" width="70" />
        <el-table-column label="内容" min-width="300">
          <template #default="{ row }">
            <div class="content-cell">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="row.cleanReason" :content="row.cleanReason" placement="top">
              <el-tag :type="chunkStatusType(row)" size="small">{{ chunkStatusText(row) }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="chunkStatusType(row)" size="small">{{ chunkStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- chunk 详情（只读，完整内容） -->
    <ChunkDetail v-model="detailVisible" :row="detailRow" />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi } from '../api'
import { useAuthStore } from '../stores/auth'
import ChunkDetail from '../components/ChunkDetail.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const kbId = route.params.kbId
const list = ref([])
const loading = ref(false)
const chunkVisible = ref(false)
const chunks = ref([])
const chunkTitle = ref('')
const curateOn = ref(false)
const detailVisible = ref(false)
const detailRow = ref(null)

/** 过渡态轮询定时器（解析状态自动刷新） */
let timer = null

// ===== 批量上传状态（同一次文件选择的多个 change 事件归为一批） =====
const batchQueue = []
let batchTimer = null
let batchFlushing = false
const ALLOWED_EXT = ['txt', 'md', 'html', 'pdf', 'docx', 'pptx', 'xlsx', 'xls']
const MAX_FILE_SIZE = 20 * 1024 * 1024 // 与后端单文件上限同口径（spring.servlet.multipart.max-file-size，默认 20MB）

const parseType = (s) => ({ SUCCESS: 'success', FAILED: 'danger', PARSING: 'warning', PENDING: 'info' }[s] || 'info')

const chunkStatusType = (row) => {
  if (row.cleanStatus === 'SUSPECT') return 'warning'
  if (row.cleanStatus === 'FILTERED' || row.status === 'FILTERED') return 'info'
  return { INDEXED: 'success', EMBEDDING: 'primary', FAILED: 'danger' }[row.status] || 'info'
}
const chunkStatusText = (row) => {
  if (row.cleanStatus === 'SUSPECT') return 'SUSPECT'
  if (row.cleanStatus === 'FILTERED' || row.status === 'FILTERED') return 'FILTERED'
  return row.status || ''
}

function sizeText(n) {
  if (!n) return '-'
  if (n > 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  if (n > 1024) return (n / 1024).toFixed(1) + ' KB'
  return n + ' B'
}

function fmt(t) { return t ? t.replace('T', ' ').slice(0, 19) : '' }

/** 是否存在过渡态文档（解析未落定），决定要不要继续轮询 */
function hasActive() {
  return list.value.some((d) => d.parseStatus === 'PENDING' || d.parseStatus === 'PARSING')
}

function syncPolling() {
  if (hasActive()) startPolling()
  else stopPolling()
}

/** 3s 静默轮询：不置 loading 避免表格闪烁；全部落定后自动停止；请求失败即停止（拦截器已 toast，避免连环报错）。
 *  轮询中检测状态跳变：文档解析落定时用与页面一致的 ElMessage 顶部提示。 */
function startPolling() {
  if (timer) return
  timer = setInterval(async () => {
    const prev = new Map(list.value.map((d) => [d.id, d.parseStatus]))
    try {
      list.value = await docApi.list(kbId)
      for (const d of list.value) {
        const before = prev.get(d.id)
        if (before !== 'PENDING' && before !== 'PARSING') continue
        if (d.parseStatus === 'SUCCESS') {
          ElMessage.success(`文档「${d.fileName}」解析成功，共 ${d.chunkCount ?? '?'} 块`)
        } else if (d.parseStatus === 'FAILED') {
          ElMessage.error(`文档「${d.fileName}」解析失败：${d.errorMsg || '未知错误'}`)
        }
      }
      if (!hasActive()) stopPolling()
    } catch {
      stopPolling()
    }
  }, 3000)
}

function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

async function load() {
  loading.value = true
  try { list.value = await docApi.list(kbId) } finally { loading.value = false }
  syncPolling()
}

async function doUpload({ file }) {
  // 批量归组：一次文件选择的 N 个 change 事件在同一 tick 内合并为一批（详见 flushUploadBatch）
  batchQueue.push(file)
  if (batchTimer) clearTimeout(batchTimer)
  batchTimer = setTimeout(flushUploadBatch, 0)
}

/**
 * 批量上传流水线：一次选择 → 至多 2 个汇总弹窗 → 逐文件串行请求（故障隔离）→ 1 次汇总 toast + 1 次刷新。
 *  ① 预校验（坏扩展名/>20MB 本地剔除，不发请求不连坐）
 *  ② 同名检测（大小写不敏感，对照当前列表快照）
 *  ③ 汇总覆盖确认（仅存在同名时）：全部覆盖 / 跳过这些（取消与关闭均按跳过 dup 处理，新文件照常）
 *  ④ 复用确认（仅走到覆盖时）：答案应用到本批全部文件（哈希按内容命中，新文件同内容同样受益）
 *  ⑤ 串行提交：单文件失败只损失该文件；401（登录失效）中止剩余
 *  ⑥ 汇总 toast（成功/失败/跳过计数）+ 一次 load()
 */
async function flushUploadBatch() {
  batchTimer = null
  if (batchFlushing) { batchTimer = setTimeout(flushUploadBatch, 500); return } // 前一批未完：整批延后，避免双流水线抢弹窗
  const files = batchQueue.splice(0)
  if (!files.length) return
  batchFlushing = true
  try {
    // ① 预校验（与后端 DocumentService.validate 同口径）
    const valid = []
    const skipped = []
    for (const f of files) {
      const dot = f.name.lastIndexOf('.')
      const ext = dot >= 0 ? f.name.slice(dot + 1).toLowerCase() : ''
      if (!ALLOWED_EXT.includes(ext)) { skipped.push(`${f.name}（类型不支持）`); continue }
      if (f.size > MAX_FILE_SIZE) { skipped.push(`${f.name}（超过 20MB）`); continue }
      valid.push(f)
    }
    if (skipped.length) {
      ElMessage.warning(`已跳过 ${skipped.length} 个无效文件：${skipped.slice(0, 5).join('、')}${skipped.length > 5 ?' 等' : ''}`)
    }
    if (!valid.length) return

    // ② 同名检测（当前列表快照，大小写不敏感）
    const dupNames = new Set()
    for (const f of valid) {
      if (list.value.some((d) => d.fileName && d.fileName.toLowerCase() === f.name.toLowerCase())) {
        dupNames.add(f.name.toLowerCase())
      }
    }

    // ③④ 汇总确认（至多 2 个弹窗）
    let reuse = false
    let toUpload = valid
    let dupSkipped = 0
    if (dupNames.size) {
      const dupList = valid.filter((f) => dupNames.has(f.name.toLowerCase()))
      const fresh = valid.filter((f) => !dupNames.has(f.name.toLowerCase()))
      try {
        await ElMessageBox.confirm(
          `以下 ${dupList.length} 个文件已存在：${dupList.map((f) => f.name).join('、')}。` +
          `覆盖将删除旧文档及其向量/初洗内容后重新上传。`,
          '存在同名文件',
          { type: 'warning', confirmButtonText: '全部覆盖', cancelButtonText: '跳过这些' }
        )
        try {
          await ElMessageBox.confirm(
            '是否复用已有解析结果？文件内容与缓存一致时将跳过云端解析（节省消耗），分块与向量化仍会正常生成；选择「重新解析」将全量解析。',
            '复用解析结果',
            { type: 'info', confirmButtonText: '复用', cancelButtonText: '重新解析' }
          )
          reuse = true
        } catch { reuse = false }
      } catch {
        // 跳过这些（含关闭）：dup 剔除，仅新文件继续
        toUpload = fresh
        dupSkipped = dupList.length
      }
    }
    if (!toUpload.length) {
      if (dupSkipped) ElMessage.info(`已跳过 ${dupSkipped} 个同名文件，本批无上传`)
      return
    }

    // ⑤ 串行逐文件请求（参数 per-file 精确；单文件失败不连坐）
    const succeeded = []
    const failed = []
    const uploaded = new Set() // 批内同名碰撞 → 后者自动覆盖前者（last-write-wins）
    let authLost = false
    for (const f of toUpload) {
      const key = f.name.toLowerCase()
      const replace = dupNames.has(key) || uploaded.has(key)
      uploaded.add(key)
      try {
        await docApi.upload(kbId, [f], replace, reuse, curateOn.value)
        succeeded.push(f.name)
      } catch (e) {
        if (e && e.response && e.response.status === 401) { authLost = true; break }
        failed.push(f.name) // 具体错误信息拦截器已逐条 toast
      }
    }

    // ⑥ 一条汇总 + 一次刷新
    const skipCount = skipped.length + dupSkipped
    const parts = [`成功 ${succeeded.length}`, `失败 ${failed.length}`, `跳过 ${skipCount}`].filter((p) => !p.endsWith(' 0'))
    const summary = `上传完成：${parts.join(' · ') || '无文件'}`
    if (authLost) {
      ElMessage.error('登录已失效，请重新登录；本批剩余文件未上传')
    } else if (failed.length || skipped.length || dupSkipped) {
      ElMessage.warning(failed.length ? `${summary}（失败：${failed.slice(0, 5).join('、')}${failed.length > 5 ? ' 等' : ''}）` : summary)
    } else {
      ElMessage.success(summary + (curateOn.value ? '，已启用初洗门，请到左侧「文档初洗」页处理' : ''))
    }
    load()
  } finally {
    batchFlushing = false
  }
}

async function viewChunks(row) {
  chunks.value = await docApi.chunks(row.id)
  chunkTitle.value = `分块结果：${row.fileName}`
  chunkVisible.value = true
}

function openDetail(row) {
  detailRow.value = row
  detailVisible.value = true
}

async function retry(row) {
  await docApi.retry(row.id)
  ElMessage.success('已触发重新解析')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除文档「${row.fileName}」？`, '提示', { type: 'warning' })
  await docApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

function goBack() { router.push('/kb') }

onMounted(load)
onUnmounted(stopPolling)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.content-cell {
  max-height: 60px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px;
}
</style>
