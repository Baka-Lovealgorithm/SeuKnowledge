<template>
  <div>
    <div class="toolbar">
      <el-select v-model="kbId" placeholder="选择知识库" style="width: 220px" @change="load">
        <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
      </el-select>
      <el-select v-model="statusFilter" placeholder="状态" style="width: 120px" clearable @change="load">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已通过" value="APPROVED" />
        <el-option label="已拒绝" value="REJECTED" />
        <el-option label="已禁用" value="DISABLED" />
      </el-select>
      <el-button v-if="auth.canWrite" type="primary" :disabled="!kbId" @click="openCreate">新建问答对</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
      <el-table-column prop="normalizedQuestion" label="归一化问题" min-width="160" show-overflow-tooltip />
      <el-table-column prop="answer" label="答案" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button v-if="auth.canWrite && row.status === 'DRAFT'" link type="success" @click="approve(row)">通过</el-button>
          <el-button v-if="auth.canWrite && row.status === 'DRAFT'" link type="danger" @click="reject(row)">拒绝</el-button>
          <el-button v-if="auth.canWrite" link type="primary" @click="normalize(row)" :loading="normalizingId === row.id">归一化</el-button>
          <el-button v-if="auth.canWrite && row.status !== 'DISABLED'" link type="warning" @click="disable(row)">禁用</el-button>
          <el-button v-else-if="auth.canWrite" link type="success" @click="enable(row)">启用</el-button>
          <el-button v-if="auth.canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="viewVersions(row)">版本</el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑问答对' : '新建问答对'" width="560px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="问题" required><el-input v-model="form.question" /></el-form-item>
        <el-form-item label="答案" required><el-input v-model="form.answer" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionVisible" title="版本历史" width="620px">
      <el-table :data="versions" size="small" border>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="当前" width="70">
          <template #default="{ row }">{{ !row.deleted ? '✓' : '' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="auth.canWrite && row.deleted" link type="primary" @click="rollback(row)">回退</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { qaApi, kbApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const kbs = ref([])
const kbId = ref(null)
const statusFilter = ref('')
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref(null)
const versionVisible = ref(false)
const versions = ref([])
const normalizingId = ref(null)

const emptyForm = () => ({ question: '', answer: '' })
const form = reactive(emptyForm())

const STATUS = { DRAFT: ['warning', '草稿'], APPROVED: ['success', '已通过'], REJECTED: ['danger', '已拒绝'], DISABLED: ['info', '已禁用'] }
const statusType = (s) => (STATUS[s] || ['info'])[0]
const statusText = (s) => (STATUS[s] || [null, s])[1]

async function loadKbs() { kbs.value = await kbApi.list() }

async function load() {
  if (!kbId.value) return
  loading.value = true
  try { list.value = await qaApi.list(kbId.value, statusFilter.value || undefined) } finally { loading.value = false }
}

function openCreate() {
  editing.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, { question: row.question, answer: row.answer })
  dialogVisible.value = true
}

async function save() {
  if (!form.question.trim() || !form.answer.trim()) { ElMessage.warning('请填写问题与答案'); return }
  saving.value = true
  try {
    if (editing.value) await qaApi.update(editing.value.id, { question: form.question, answer: form.answer })
    else await qaApi.create(kbId.value, { question: form.question, answer: form.answer })
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { saving.value = false }
}

async function approve(row) { await qaApi.approve(row.id); ElMessage.success('已通过'); load() }
async function reject(row) { await qaApi.reject(row.id); ElMessage.success('已拒绝'); load() }
async function disable(row) { await qaApi.disable(row.id); ElMessage.success('已禁用'); load() }
async function enable(row) { await qaApi.enable(row.id); ElMessage.success('已启用'); load() }

async function normalize(row) {
  normalizingId.value = row.id
  try {
    const res = await qaApi.normalize(row.id)
    ElMessage.success(`归一化完成：${res.normalizedQuestion}`)
    load()
  } finally { normalizingId.value = null }
}

async function viewVersions(row) {
  versions.value = await qaApi.versions(row.id)
  versionVisible.value = true
}

async function rollback(row) {
  await ElMessageBox.confirm(`确定回退到版本 ${row.version}？`, '提示', { type: 'warning' })
  await qaApi.rollback(row.id, row.version)
  ElMessage.success('已回退')
  versionVisible.value = false
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除问答对「${row.question}」？`, '提示', { type: 'warning' })
  await qaApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(async () => {
  await loadKbs()
  if (kbs.value.length) { kbId.value = kbs.value[0].id; load() }
})
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
</style>
