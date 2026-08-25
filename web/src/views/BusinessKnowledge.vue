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
      </el-select>
      <el-button v-if="auth.canWrite" type="primary" :disabled="!kbId" @click="openCreate">新建业务知识</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="term" label="术语" min-width="130" />
      <el-table-column label="别名" min-width="150">
        <template #default="{ row }">{{ (row.aliases || []).join(', ') }}</template>
      </el-table-column>
      <el-table-column prop="definition" label="定义" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="70" />
      <el-table-column prop="sourceDocName" label="来源" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="310" fixed="right">
        <template #default="{ row }">
          <el-button v-if="auth.canWrite && row.status === 'DRAFT'" link type="success" @click="approve(row)">通过</el-button>
          <el-button v-if="auth.canWrite && row.status === 'DRAFT'" link type="danger" @click="reject(row)">拒绝</el-button>
          <el-button v-if="auth.canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="auth.canWrite" link type="warning" @click="openMerge(row)">合并</el-button>
          <el-button link type="primary" @click="viewVersions(row)">版本</el-button>
          <el-button v-if="auth.canWrite" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑业务知识' : '新建业务知识'" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="术语" required><el-input v-model="form.term" /></el-form-item>
        <el-form-item label="别名"><el-input v-model="form.aliasesText" placeholder="多个别名用逗号分隔" /></el-form-item>
        <el-form-item label="定义"><el-input v-model="form.definition" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="适用范围"><el-input v-model="form.scope" /></el-form-item>
        <el-form-item label="示例"><el-input v-model="form.example" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="禁用规则"><el-input v-model="form.prohibitedRules" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mergeVisible" title="合并到另一条记录" width="420px">
      <el-select v-model="mergeTargetId" placeholder="选择目标记录" style="width: 100%">
        <el-option v-for="item in mergeCandidates" :key="item.id" :label="`${item.term} (v${item.version})`" :value="item.id" />
      </el-select>
      <div class="merge-tip">合并后本记录软删除，别名合并去重、缺失字段从目标记录补齐。</div>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!mergeTargetId" @click="doMerge">合并</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionVisible" title="版本历史" width="620px">
      <el-table :data="versions" size="small" border>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="term" label="术语" min-width="120" />
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bkApi, kbApi } from '../api'
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
const mergeVisible = ref(false)
const mergeTargetId = ref(null)
const mergeSourceId = ref(null)
const versionVisible = ref(false)
const versions = ref([])
const versionRow = ref(null)

const emptyForm = () => ({ term: '', aliasesText: '', definition: '', scope: '', example: '', prohibitedRules: '' })
const form = reactive(emptyForm())

const STATUS = { DRAFT: ['warning', '草稿'], APPROVED: ['success', '已通过'], REJECTED: ['danger', '已拒绝'] }
const statusType = (s) => (STATUS[s] || ['info'])[0]
const statusText = (s) => (STATUS[s] || [null, s])[1]
const mergeCandidates = computed(() => list.value.filter((i) => i.id !== mergeSourceId.value))

async function loadKbs() { kbs.value = await kbApi.list() }

async function load() {
  if (!kbId.value) return
  loading.value = true
  try { list.value = await bkApi.list(kbId.value, statusFilter.value || undefined) } finally { loading.value = false }
}

function openCreate() {
  editing.value = null
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    term: row.term, aliasesText: (row.aliases || []).join(','),
    definition: row.definition, scope: row.scope, example: row.example, prohibitedRules: row.prohibitedRules
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.term.trim()) { ElMessage.warning('请输入术语'); return }
  saving.value = true
  try {
    const payload = {
      term: form.term,
      aliases: form.aliasesText ? form.aliasesText.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : [],
      definition: form.definition, scope: form.scope, example: form.example, prohibitedRules: form.prohibitedRules
    }
    if (editing.value) await bkApi.update(editing.value.id, payload)
    else await bkApi.create(kbId.value, payload)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { saving.value = false }
}

async function approve(row) { await bkApi.approve(row.id); ElMessage.success('已通过'); load() }
async function reject(row) { await bkApi.reject(row.id); ElMessage.success('已拒绝'); load() }

function openMerge(row) {
  mergeSourceId.value = row.id
  mergeTargetId.value = null
  mergeVisible.value = true
}

async function doMerge() {
  await bkApi.merge(mergeSourceId.value, mergeTargetId.value)
  ElMessage.success('合并成功')
  mergeVisible.value = false
  load()
}

async function viewVersions(row) {
  versions.value = await bkApi.versions(row.id)
  versionVisible.value = true
}

async function rollback(row) {
  await ElMessageBox.confirm(`确定回退到版本 ${row.version}？`, '提示', { type: 'warning' })
  await bkApi.rollback(versionRow.value?.id || row.id, row.version)
  ElMessage.success('已回退')
  versionVisible.value = false
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定删除业务知识「${row.term}」？`, '提示', { type: 'warning' })
  await bkApi.remove(row.id)
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
.merge-tip { color: #909399; font-size: 12px; margin-top: 8px; }
</style>
