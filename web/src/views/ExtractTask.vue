<template>
  <div>
    <div class="create-panel">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 200px" @change="onKbChange">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-select v-model="docIds" multiple placeholder="选择要抽取的文档" style="width: 320px">
        <el-option v-for="d in docs" :key="d.id" :label="d.fileName" :value="d.id" />
      </el-select>
      <el-radio-group v-model="extractType">
        <el-radio value="BOTH">业务知识+问答对</el-radio>
        <el-radio value="BUSINESS">仅业务知识</el-radio>
        <el-radio value="QA">仅问答对</el-radio>
      </el-radio-group>
      <el-button v-if="auth.canWrite" type="primary" :disabled="!kbId || docIds.length === 0" :loading="creating" @click="createTask">
        开始抽取
      </el-button>
    </div>

    <div class="stats-bar">
      <el-tag type="info" effect="plain">任务总数 {{ tasks.length }}</el-tag>
      <el-tag type="success" effect="plain">成功 {{ successCount }}</el-tag>
      <el-tag type="warning" effect="plain">失败/部分失败 {{ failCount }}</el-tag>
      <el-tag type="primary" effect="plain">总 Token {{ totalTokens.toLocaleString() }}</el-tag>
      <el-tag effect="plain">平均耗时 {{ avgDuration }}</el-tag>
    </div>

    <el-table :data="tasks" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="extractType" label="类型" width="120" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" min-width="160">
        <template #default="{ row }">
          <el-progress :percentage="row.progress || 0" :status="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'exception' : undefined" />
        </template>
      </el-table-column>
      <el-table-column prop="processedDocs" label="已处理/总数" width="100">
        <template #default="{ row }">{{ row.processedDocs }}/{{ row.totalDocs }}</template>
      </el-table-column>
      <el-table-column label="耗时/Token" width="130">
        <template #default="{ row }">
          {{ row.durationMs ? (row.durationMs / 1000).toFixed(1) + 's' : '-' }} / {{ row.tokenTotal ?? 0 }}
        </template>
      </el-table-column>
      <el-table-column prop="resultSummary" label="结果" min-width="180" />
      <el-table-column prop="errorLog" label="错误日志" min-width="160" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" width="160">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="auth.canWrite && (row.status === 'FAILED' || row.status === 'PARTIAL_FAILED')" link type="warning" @click="retryTask(row)">
            重试
          </el-button>
          <el-button link type="primary" @click="viewResults(row)">结果预览</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="resultVisible" title="抽取结果预览（DRAFT 草稿）" width="860px">
      <div class="result-section">业务知识（{{ result.businessKnowledge.length }} 条）</div>
      <el-table :data="result.businessKnowledge" size="small" border max-height="260">
        <el-table-column prop="term" label="术语" min-width="120" />
        <el-table-column prop="definition" label="定义" min-width="260" show-overflow-tooltip />
        <el-table-column prop="scope" label="适用范围" min-width="120" />
        <el-table-column prop="sourceDocName" label="来源" min-width="110" />
      </el-table>
      <div class="result-section">问答对（{{ result.qaPairs.length }} 条）</div>
      <el-table :data="result.qaPairs" size="small" border max-height="260">
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="answer" label="答案" min-width="280" show-overflow-tooltip />
        <el-table-column prop="sourceDocName" label="来源" min-width="110" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi, extractApi, kbApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const kbs = ref([])
const kbId = ref(null)
const docs = ref([])
const docIds = ref([])
const extractType = ref('BOTH')
const creating = ref(false)
const tasks = ref([])
const loading = ref(false)
const resultVisible = ref(false)
const result = ref({ businessKnowledge: [], qaPairs: [] })
let timer = null

const statusType = (s) => ({ PENDING: 'info', RUNNING: 'warning', SUCCESS: 'success', PARTIAL_FAILED: 'warning', FAILED: 'danger' }[s] || 'info')

// ===== 抽取统计（成功率 / Token / 平均耗时，由全量列表聚合）=====
const successCount = computed(() => tasks.value.filter((t) => t.status === 'SUCCESS').length)
const failCount = computed(() => tasks.value.filter((t) => t.status === 'FAILED' || t.status === 'PARTIAL_FAILED').length)
const totalTokens = computed(() => tasks.value.reduce((sum, t) => sum + (t.tokenTotal || 0), 0))
const avgDuration = computed(() => {
  const done = tasks.value.filter((t) => t.durationMs)
  if (!done.length) return '-'
  const avg = done.reduce((sum, t) => sum + t.durationMs, 0) / done.length
  return avg >= 1000 ? (avg / 1000).toFixed(1) + 's' : Math.round(avg) + 'ms'
})

function fmt(t) { return t ? t.replace('T', ' ').slice(0, 19) : '' }

async function loadKbs() { kbs.value = await kbApi.list() }

async function onKbChange() {
  docs.value = await docApi.list(kbId.value)
  docIds.value = []
}

async function createTask() {
  creating.value = true
  try {
    await extractApi.create({ kbId: kbId.value, docIds: docIds.value, extractType: extractType.value })
    ElMessage.success('抽取任务已创建')
    loadTasks()
  } finally { creating.value = false }
}

async function loadTasks() {
  loading.value = true
  try { tasks.value = await extractApi.list() } finally { loading.value = false }
  const running = tasks.value.some((t) => t.status === 'RUNNING' || t.status === 'PENDING')
  if (running) startPolling()
  else stopPolling()
}

function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    tasks.value = await extractApi.list()
    if (!tasks.value.some((t) => t.status === 'RUNNING' || t.status === 'PENDING')) stopPolling()
  }, 3000)
}

function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

async function viewResults(row) {
  result.value = await extractApi.results(row.id)
  resultVisible.value = true
}

/** 重试失败任务：仅重抽失败文档，重抽前清掉这些文档的旧 DRAFT 草稿 */
async function retryTask(row) {
  await ElMessageBox.confirm(
    `将重新抽取该任务失败的文档（${row.failedDocIds?.length || '全部'} 个），并清掉这些文档下未审核的草稿，确定重试？`,
    '重试抽取任务',
    { type: 'warning', confirmButtonText: '重试', cancelButtonText: '取消' }
  )
  await extractApi.retry(row.id)
  ElMessage.success('已提交重试，正在重新抽取')
  loadTasks()
}

onMounted(async () => {
  await loadKbs()
  if (kbs.value.length) { kbId.value = kbs.value[0].id; onKbChange() }
  loadTasks()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.create-panel { display: flex; gap: 12px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }
.stats-bar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.result-section { font-weight: 600; margin: 12px 0 8px; }
</style>
