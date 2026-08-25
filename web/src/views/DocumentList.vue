<template>
  <div>
    <div class="toolbar">
      <el-button @click="goBack">← 返回知识库</el-button>
      <el-upload
        v-if="auth.canWrite"
        :show-file-list="false"
        :http-request="doUpload"
        multiple
        accept=".txt,.md,.pdf,.docx,.pptx"
      >
        <el-button type="primary">上传文档 (.txt/.md/.pdf/.docx/.pptx)</el-button>
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
      <el-table-column prop="errorMsg" label="错误信息" min-width="180" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="上传时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
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
        <el-table-column prop="content" label="内容" min-width="360" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const kbId = route.params.kbId
const list = ref([])
const loading = ref(false)
const chunkVisible = ref(false)
const chunks = ref([])
const chunkTitle = ref('')

const parseType = (s) => ({ SUCCESS: 'success', FAILED: 'danger', PARSING: 'warning', PENDING: 'info' }[s] || 'info')

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
    await docApi.upload(kbId, [file])
    ElMessage.success(`上传 ${file.name} 成功`)
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

async function viewChunks(row) {
  chunks.value = await docApi.chunks(row.id)
  chunkTitle.value = `分块结果：${row.fileName}`
  chunkVisible.value = true
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
</style>
