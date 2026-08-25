<template>
  <div>
    <div class="toolbar">
      <el-button v-if="auth.canWrite" type="primary" @click="openCreate">新建知识库</el-button>
    </div>
    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="documentCount" label="文档数" width="90" />
      <el-table-column prop="createdAt" label="创建时间" width="180">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="enterDocs(row)">文档</el-button>
          <el-button link type="primary" @click="enterAgent(row)">Agent</el-button>
          <el-button v-if="auth.canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="auth.canWrite" link :type="row.status === 'DISABLED' ? 'success' : 'warning'" @click="toggleStatus(row)">
            {{ row.status === 'DISABLED' ? '启用' : '停用' }}
          </el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑知识库' : '新建知识库'" width="480px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi } from '../api'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref(null)
const form = reactive({ name: '', description: '' })

const STATUS = {
  DRAFT: ['info', '草稿'],
  BUILDING: ['warning', '构建中'],
  AVAILABLE: ['success', '可用'],
  ERROR: ['danger', '异常'],
  DISABLED: ['info', '停用']
}
const statusType = (s) => (STATUS[s] || ['info'])[0]
const statusText = (s) => (STATUS[s] || [null, s])[1]

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 19) : ''
}

async function load() {
  loading.value = true
  try { list.value = await kbApi.list() } finally { loading.value = false }
}

function openCreate() {
  editing.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.name = row.name
  form.description = row.description
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) { ElMessage.warning('请输入名称'); return }
  saving.value = true
  try {
    if (editing.value) {
      await kbApi.update(editing.value.id, { name: form.name, description: form.description })
    } else {
      await kbApi.create({ name: form.name, description: form.description })
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { saving.value = false }
}

async function toggleStatus(row) {
  const next = row.status === 'DISABLED' ? 'AVAILABLE' : 'DISABLED'
  await kbApi.updateStatus(row.id, next)
  ElMessage.success('已更新状态')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除知识库「${row.name}」？`, '提示', { type: 'warning' })
  await kbApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

function enterDocs(row) {
  router.push(`/kb/${row.id}/documents`)
}

function enterAgent(row) {
  router.push({ path: `/kb/${row.id}/agent`, query: { kbName: row.name } })
}

onMounted(load)
</script>

<style scoped>
.toolbar { margin-bottom: 16px; }
</style>
