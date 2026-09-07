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
import { onMounted, ref } from 'vue'
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

async function load() {
  loading.value = true
  try { list.value = await docApi.list(kbId) } finally { loading.value = false }
}

async function doUpload({ file }) {
  try {
    // 同名文件检测（大小写不敏感）→ 询问是否覆盖
    const dup = list.value.find((d) => d.fileName && d.fileName.toLowerCase() === file.name.toLowerCase())
    if (dup) {
      await ElMessageBox.confirm(
        `文件「${file.name}」已存在（${dup.fileName}），是否覆盖？覆盖将删除旧文档及其向量后重新上传。`,
        '文件已存在',
        { type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '取消' }
      )
      // 覆盖后追加询问：是否复用解析缓存（内容相同时跳过云端解析，节省消耗）
      let reuse = false
      try {
        await ElMessageBox.confirm(
          '是否复用已有解析结果？若文件内容与缓存一致将跳过云端解析（节省消耗），分块与向量化仍会正常生成。选择「重新解析」将全量解析。',
          '复用解析结果',
          { type: 'info', confirmButtonText: '复用', cancelButtonText: '重新解析' }
        )
        reuse = true
      } catch {
        // 用户选「重新解析」或关闭弹窗 → 全量解析（不复用缓存）
        reuse = false
      }
      await docApi.upload(kbId, [file], true, reuse, curateOn.value)
    } else {
      await docApi.upload(kbId, [file], false, false, curateOn.value)
    }
    ElMessage.success(`上传 ${file.name} 成功${curateOn.value ? '，已启用初洗门，请到左侧「文档初洗」页处理' : ''}`)
    load()
  } catch (e) {
    // 拦截器已提示；用户取消覆盖询问时静默跳过
    if (e === 'cancel' || e === 'close') return
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
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.content-cell {
  max-height: 60px; overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  white-space: pre-line; word-break: break-all; font-size: 13px;
}
</style>
