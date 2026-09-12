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
      <span v-if="uploading" class="upload-progress">{{ uploading }}</span>
      <span v-if="auth.canWrite" class="tip">文件名后的 ✎ 只改展示与检索引用名；「重建向量」不重新解析</span>
    </div>
    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="fileName" label="文件名" min-width="200">
        <template #default="{ row }">
          <span>{{ row.fileName }}</span>
          <el-button v-if="auth.canWrite" link type="primary" size="small" class="rename-btn"
                     title="重命名（只改列表与检索引用名，不重新解析、不动磁盘文件）" @click="rename(row)">✎</el-button>
        </template>
      </el-table-column>
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
      <!-- 向量健康度：解析成功 ≠ 已进向量库（历史 bug 就是这两件事在界面上无法区分） -->
      <el-table-column label="向量" width="110">
        <template #default="{ row }">
          <el-tooltip v-if="vectorTip(row)" :content="vectorTip(row)" placement="top">
            <el-tag :type="vectorType(row)" size="small">{{ vectorText(row) }}</el-tag>
          </el-tooltip>
          <el-tag v-else :type="vectorType(row)" size="small">{{ vectorText(row) }}</el-tag>
        </template>
      </el-table-column>
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
      <el-table-column label="操作" width="350" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="viewChunks(row)">分块</el-button>
          <el-button v-if="auth.canWrite" link type="warning" @click="retry(row)">重试</el-button>
          <el-button v-if="canReindex(row)" link type="primary" @click="reindex(row)">重建向量</el-button>
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
const uploading = ref('') // 批量上传内联进度（"上传中 k/N"），空串表示无进行中的批次
const ALLOWED_EXT = ['txt', 'md', 'html', 'pdf', 'docx', 'pptx', 'xlsx', 'xls']
const MAX_FILE_SIZE = 20 * 1024 * 1024 // 与后端单文件上限同口径（spring.servlet.multipart.max-file-size，默认 20MB）
const MAX_NAME_CHARS = 255 // 与后端 DocumentService.MAX_FILE_NAME_CHARS / kb_document.file_name varchar(255) 同口径

const parseType = (s) => ({
  SUCCESS: 'success', FAILED: 'danger', ERROR: 'danger', PARSING: 'warning', PENDING: 'info'
}[s] || 'info')

// ===== 向量健康度（解析状态之外的第二条线：SUCCESS 只代表分块已落 MySQL，向量未必进了 ES） =====
/**
 * 无分块信息或后端未返回计数时不显示标记（避免误报"0/0 异常"）。
 * 但"应进向量库的块数为 0"还可能是另一种情况：全部块待人工审核（SUSPECT 走 DEFER，不进 ES），
 * 此时文档零向量、零召回，却会因 total===0 落到下面的 null 分支、与"没有分块"共用一个 '-'。
 * 故 total===0 且 suspectCount>0 时返回 deferred 形态，由 text/type/tip 三处显式说出来。
 */
function vectorInfo(row) {
  const v = row.vector
  if (!v) return null
  const total = (v.indexed || 0) + (v.pending || 0) + (v.failed || 0)
  const suspect = row.suspectCount || 0
  if (total === 0) return suspect > 0 ? { indexed: 0, pending: 0, failed: 0, total: 0, deferred: suspect } : null
  return { ...v, total, deferred: suspect }
}

function vectorText(row) {
  const v = vectorInfo(row)
  if (!v) return '-'
  if (v.total === 0) return `待审核 ${v.deferred}`
  if (v.failed > 0) return `向量 ${v.indexed}/${v.total}`
  if (v.pending > 0) return `向量中 ${v.indexed}/${v.total}`
  return `向量 ${v.indexed}/${v.total}`
}

function vectorType(row) {
  const v = vectorInfo(row)
  if (!v) return 'info'
  if (v.total === 0) return 'warning'
  if (v.failed > 0) return 'danger'
  if (v.pending > 0) return 'warning'
  return 'success'
}

/** 悬浮明细：只在有未完成/待审项时给提示，全健康不啰嗦 */
function vectorTip(row) {
  const v = vectorInfo(row)
  if (!v) return ''
  if (v.total === 0) {
    return `全部 ${v.deferred} 块待人工审核，暂不进向量库，本文档当前检索不到；`
      + '在「文档精修」页点「保留/编辑」后即单条补索引（无需重新解析）'
  }
  const parts = []
  if (v.failed > 0) parts.push(`${v.failed} 块向量化失败，可点「重建向量」`)
  if (v.pending > 0) parts.push(`${v.pending} 块待向量化（请先在「模型配置」配置向量模型）`)
  if (v.deferred > 0) parts.push(`另有 ${v.deferred} 块待人工审核（不参与检索，需在「文档精修」处置）`)
  return parts.join('；')
}

/** 初洗/精修流程中不给重建向量（确认前不触 ES 是后端红线，前端就别给按钮） */
function canReindex(row) {
  return auth.canWrite && !row.curateStatus && (row.parseStatus === 'SUCCESS' || row.parseStatus === 'ERROR')
}

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

/** 是否存在过渡态文档（解析未落定，或向量还在追平），决定要不要继续轮询 */
function hasActive() {
  return list.value.some((d) => d.parseStatus === 'PENDING' || d.parseStatus === 'PARSING'
    || (d.vector && (d.vector.pending || 0) > 0))
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
    const snap = (d) => [d.id, {
      status: d.parseStatus,
      pending: d.vector?.pending || 0,
      failed: d.vector?.failed || 0
    }]
    const prev = new Map(list.value.map(snap))
    try {
      list.value = await docApi.list(kbId)
      for (const d of list.value) {
        const before = prev.get(d.id)
        if (!before) continue
        const pending = d.vector?.pending || 0
        const failed = d.vector?.failed || 0
        // 解析状态落定
        if ((before.status === 'PENDING' || before.status === 'PARSING') && before.status !== d.parseStatus) {
          if (d.parseStatus === 'SUCCESS') {
            ElMessage.success(`文档「${d.fileName}」解析成功，共 ${d.chunkCount ?? '?'} 块`
              + (pending > 0 ? '，向量化进行中' : ''))
          } else if (d.parseStatus === 'FAILED') {
            ElMessage.error(`文档「${d.fileName}」解析失败：${d.errorMsg || '未知错误'}`)
          }
        }
        // 向量追平（解析成功后才走这一段，避免同一条 toast 连着弹两次）
        if (before.pending > 0 && pending === 0 && d.parseStatus === 'SUCCESS') {
          if (failed > 0) {
            ElMessage.warning(`文档「${d.fileName}」${failed} 块向量化失败：${d.errorMsg || '未知原因，可点「重建向量」重试'}`)
          } else {
            ElMessage.success(`文档「${d.fileName}」向量已就绪，共 ${d.vector?.indexed ?? '?'} 块`)
          }
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
 *  ① 预校验（坏扩展名/>20MB/文件名超长 本地剔除，不发请求不连坐）
 *  ② 同名检测（大小写不敏感，对照当前列表快照）
 *  ③ 汇总覆盖确认（仅存在同名时）：全部覆盖 / 跳过这些（取消与关闭均按跳过 dup 处理，新文件照常）
 *  ④ 复用确认（仅走到覆盖时）：答案应用到本批全部文件（哈希按内容命中，新文件同内容同样受益）
 *  ⑤ 串行提交：单文件失败只损失该文件；401（登录失效）中止剩余；逐文件错误不弹 toast（silentError），
 *     由本批汇总一条说明，避免"10 个失败 = 10 条 toast + 1 条汇总"
 *  ⑥ 汇总 toast（成功/失败/跳过计数）+ 一次 load()
 */
async function flushUploadBatch() {
  batchTimer = null
  if (batchFlushing) { batchTimer = setTimeout(flushUploadBatch, 500); return } // 前一批未完：整批延后，避免双流水线抢弹窗
  const files = batchQueue.splice(0)
  if (!files.length) return
  batchFlushing = true
  try {
    // ① 预校验（与后端 DocumentService.validate 同口径：类型 / 大小 / 文件名长度）
    const valid = []
    const skipped = []
    for (const f of files) {
      if (f.name.length > MAX_NAME_CHARS) {
        // 后端列宽 varchar(255)：超长名会落库失败（文件已落盘，还会留下孤儿文件）→ 本地先拦，不发请求
        skipped.push(`${f.name.slice(0, 24)}…（文件名超过 ${MAX_NAME_CHARS} 字符）`)
        continue
      }
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
    //    刻意不合并成一个批量请求：后端接口支持多文件，但 replace 是 per-file 语义、
    //    且 max-request-size 100MB 下"多个 20MB 合并"会整批 413；串行 + 逐条故障隔离更稳。
    const succeeded = []
    const failed = []
    const uploaded = new Set() // 批内同名碰撞 → 后者自动覆盖前者（last-write-wins）
    let authLost = false
    if (toUpload.length > 1) {
      uploading.value = `上传中 0/${toUpload.length}` // 工具栏内联进度（ElMessage 不支持更新已显示的文案，不拿 toast 当进度条）
    }
    for (const f of toUpload) {
      const key = f.name.toLowerCase()
      const replace = dupNames.has(key) || uploaded.has(key)
      uploaded.add(key)
      try {
        await docApi.upload(kbId, [f], replace, reuse, curateOn.value, { silentError: true })
        succeeded.push(f.name)
      } catch (e) {
        if (e && e.response && e.response.status === 401) { authLost = true; break }
        failed.push({ name: f.name, reason: e?.response?.data?.message || e?.message || '请求失败' })
      }
      if (uploading.value) uploading.value = `上传中 ${succeeded.length + failed.length}/${toUpload.length}`
    }

    // ⑥ 一条汇总 + 一次刷新（逐文件错误已静默，这里去重成一条）
    const skipCount = skipped.length + dupSkipped
    const parts = [`成功 ${succeeded.length}`, `失败 ${failed.length}`, `跳过 ${skipCount}`].filter((p) => !p.endsWith(' 0'))
    const summary = `上传完成：${parts.join(' · ') || '无文件'}`
    if (authLost) {
      ElMessage.error('登录已失效，请重新登录；本批剩余文件未上传')
    } else if (failed.length || skipped.length || dupSkipped) {
      if (failed.length) {
        // 同因失败合并计数：10 个同名超长名文件只说一次原因，不再刷 10 条
        const byReason = new Map()
        failed.forEach((x) => byReason.set(x.reason, (byReason.get(x.reason) || 0) + 1))
        const detail = [...byReason.entries()]
          .sort((a, b) => b[1] - a[1]).slice(0, 3)
          .map(([r, n]) => `${r}${n > 1 ? `（${n} 个）` : ''}`).join('；')
        ElMessage.warning(`${summary}。失败原因：${detail}${failed.length > 3 ? ' 等' : ''}`)
      } else {
        ElMessage.warning(summary)
      }
    } else {
      ElMessage.success(summary + (curateOn.value ? '，已启用初洗门，请到左侧「文档初洗」页处理' : ''))
    }
    load()
  } finally {
    uploading.value = ''
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

/**
 * 重试 = 全量重新解析：会删掉现有 chunk 与向量后重跑解析。
 * 二次确认必须区分初洗/精修中文档——那条路径上还会用人家解析结果整体替换初洗 md，
 * 人工编辑过的 md 历史版本（append-only 版本行）被硬删且不可恢复。
 */
async function retry(row) {
  const stage = row.curateStatus === 'PREVIEWING' ? '初洗' : row.curateStatus === 'ACCEPTED' ? '精修' : ''
  const msg = stage
    ? `「${row.fileName}」正处于「${stage}」中：重新解析会删除现有分块与向量，并用新的解析结果整体替换初洗 md`
      + '——你在初洗里编辑过的 md 历史版本会一并丢弃，且不可恢复。确定继续？'
    : `确定重新解析「${row.fileName}」？将删除现有分块与向量后重新解析生成。`
  await ElMessageBox.confirm(msg, '重新解析', { type: 'warning', confirmButtonText: '确定重新解析', cancelButtonText: '取消' })
  await docApi.retry(row.id)
  ElMessage.success('已触发重新解析')
  load()
}

/**
 * 只重建向量、不重新解析：向量化失败（模型未配置 / embedding 异常 / ES 部分写入失败）后的原地救济，
 * 免去"只能靠 retry 全量重解析"的老路（对初洗文档还会连带洗掉人工 md）。
 * 后端回传处理量：全部块待人工审核时本动作是空操作（DEFER 不进 ES），此时必须如实说，
 * 否则用户会以为在处理，回头去点「重试」——那才是真花钱又删块的那条路。
 */
async function reindex(row) {
  const r = await docApi.reindex(row.id)
  if (r && !r.reset) {
    ElMessage.warning(r.awaitingReview > 0
      ? `「${row.fileName}」没有需要重建的分块；${r.awaitingReview} 块待人工审核（暂不进向量库），请到「文档精修」页点「保留/编辑」`
      : `「${row.fileName}」没有向量化失败的分块`)
  } else {
    ElMessage.success(`已触发重建向量（${r?.reset ?? 0} 块将重新向量化，不重新解析文档）`)
  }
  load()
}

const ILLEGAL_NAME_CHARS = /[\\/]/

/** 重命名：只改列表展示与检索引用名（MySQL file_name + ES docName + 派生知识来源名），不重解析、不动磁盘文件 */
async function rename(row) {
  const dot = row.fileName.lastIndexOf('.')
  const stem = dot > 0 ? row.fileName.slice(0, dot) : row.fileName
  const suffix = dot > 0 ? row.fileName.slice(dot) : `.${row.fileType}`
  const target = (input) => `${(input || '').trim()}${suffix}`
  let value
  try {
    ({ value } = await ElMessageBox.prompt(
      `扩展名保持 ${suffix} 不变（解析与分块按扩展名选择处理方式）。改名不会重新解析，也不会改动磁盘文件。`,
      `重命名「${row.fileName}」`,
      {
        inputValue: stem,
        inputPlaceholder: '新名称（不含扩展名）',
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (v) => {
          if (!(v || '').trim()) return '名称不能为空'
          const name = target(v)
          if (name.length > MAX_NAME_CHARS) return `名称过长（含扩展名最多 ${MAX_NAME_CHARS} 字符）`
          if (ILLEGAL_NAME_CHARS.test(name)) return '名称不能包含 / 或 \\'
          if (name === row.fileName) return '新名称与当前相同'
          return true
        }
      }
    ))
  } catch {
    return // 取消/关闭
  }
  await docApi.rename(row.id, target(value))
  ElMessage.success('已重命名，检索引用名同步更新')
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
.toolbar .tip { color: #909399; font-size: 12px; }
.upload-progress { color: #409eff; font-size: 13px; }
.rename-btn { margin-left: 4px; font-size: 12px; }
.content-cell {
  max-height: 60px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px;
}
</style>
