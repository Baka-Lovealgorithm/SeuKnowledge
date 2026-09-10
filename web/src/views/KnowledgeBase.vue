<template>
  <div>
    <div class="toolbar">
      <el-button v-if="auth.canWrite" type="primary" @click="openCreate">新建知识库</el-button>
    </div>
    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="name" label="名称" min-width="160">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.visibility === 'RESTRICTED'" type="warning" size="small" style="margin-left: 6px">私有</el-tag>
        </template>
      </el-table-column>
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
      <el-table-column label="操作" width="340" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="enterDocs(row)">文档</el-button>
          <el-button v-if="canManage(row)" link type="primary" @click="openAccess(row)">共享</el-button>
          <el-button v-if="row.canEdit" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.canEdit" link :type="row.status === 'DISABLED' ? 'success' : 'warning'" @click="toggleStatus(row)">
            {{ row.status === 'DISABLED' ? '启用' : '停用' }}
          </el-button>
          <el-button v-if="row.canEdit" link type="danger" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="accessVisible" :title="`共享设置：${accessKb ? accessKb.name : ''}`" width="620px">
      <el-form label-width="90px">
        <el-form-item label="可见性">
          <el-radio-group v-model="accessVisibility" :disabled="!canManageAccess">
            <el-radio value="PUBLIC">公开（空间内成员可见）</el-radio>
            <el-radio value="RESTRICTED">私有（仅授权用户/组可见）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div v-if="accessVisibility === 'RESTRICTED'" class="access-section">
        <div class="access-title">授权用户 / 组</div>
        <el-table :data="accessList" border size="small" max-height="260">
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.granteeType === 'GROUP'" type="warning" size="small">组</el-tag>
              <el-tag v-else type="info" size="small">用户</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="granteeName" label="名称" min-width="120" />
          <el-table-column label="权限" width="140">
            <template #default="{ row }">
              <el-select v-model="row.permission" size="small" :disabled="!canManageAccess" @change="changePermission(row)">
                <el-option label="只读" value="VIEW" />
                <el-option label="可编辑" value="EDIT" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="70" align="center">
            <template #default="{ row }">
              <el-button v-if="canManageAccess" link type="danger" size="small" @click="revoke(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="access-add">
          <el-select v-model="newGranteeType" size="default" style="width: 90px">
            <el-option label="用户" value="USER" />
            <el-option label="组" value="GROUP" />
          </el-select>
          <el-select v-if="newGranteeType === 'USER'" v-model="newGranteeId" placeholder="选择空间成员" filterable size="default" style="width: 200px; margin-left: 8px">
            <el-option v-for="m in memberOptions" :key="m.userId" :label="m.username" :value="m.userId" />
          </el-select>
          <el-select v-else v-model="newGranteeId" placeholder="选择组" filterable size="default" style="width: 200px; margin-left: 8px">
            <el-option v-for="g in groupOptions" :key="g.id" :label="g.name" :value="g.id" />
          </el-select>
          <el-select v-model="newPermission" size="default" style="width: 110px; margin-left: 8px">
            <el-option label="只读" value="VIEW" />
            <el-option label="可编辑" value="EDIT" />
          </el-select>
          <el-button type="primary" size="default" style="margin-left: 8px" :disabled="!newGranteeId" @click="grant">添加</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="accessVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi, workspaceApi, groupApi } from '../api'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref(null)
const form = reactive({ name: '', description: '' })

const accessVisible = ref(false)
const accessKb = ref(null)
const accessVisibility = ref('PUBLIC')
const accessList = ref([])
const newGranteeType = ref('USER')
const newGranteeId = ref(null)
const newPermission = ref('VIEW')
const members = ref([])
const groups = ref([])

const memberOptions = computed(() =>
  members.value.filter((m) => !accessList.value.some((a) => a.granteeType === 'USER' && a.granteeId === m.userId))
)

const groupOptions = computed(() =>
  groups.value.filter((g) => !accessList.value.some((a) => a.granteeType === 'GROUP' && a.granteeId === g.id))
)

/** 管理权：空间 OWNER/ADMIN 或知识库创建者 */
function canManage(row) {
  return auth.isAdmin || (auth.user && auth.user.userId && row.createdBy === auth.user.userId)
}

/** 共享对话框内控件的可编辑性：与「共享」按钮同一口径（管理权），非纯角色位 */
const canManageAccess = computed(() => !!accessKb.value && canManage(accessKb.value))

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
  await ElMessageBox.confirm(`确定删除知识库「${row.name}」？删除后其共享授权将一并清除。`, '提示', { type: 'warning' })
  await kbApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

function enterDocs(row) {
  router.push(`/kb/${row.id}/documents`)
}

// ===== 共享设置 =====

async function openAccess(row) {
  accessKb.value = row
  accessVisibility.value = row.visibility || 'PUBLIC'
  accessList.value = []
  newGranteeType.value = 'USER'
  newGranteeId.value = null
  newPermission.value = 'VIEW'
  accessVisible.value = true
  const [acls, ms, gs] = await Promise.all([kbApi.accessList(row.id), workspaceApi.members(), groupApi.list()])
  accessList.value = acls
  members.value = ms
  groups.value = gs
}

watch(accessVisibility, async (v, old) => {
  if (!accessKb.value || v === old) return
  await kbApi.setVisibility(accessKb.value.id, v)
  ElMessage.success(v === 'RESTRICTED' ? '已设为私有' : '已设为公开')
  accessKb.value.visibility = v
  load()
})

async function grant() {
  await kbApi.accessGrant(accessKb.value.id, {
    granteeType: newGranteeType.value,
    granteeId: newGranteeId.value,
    permission: newPermission.value
  })
  ElMessage.success('已添加授权')
  newGranteeId.value = null
  newPermission.value = 'VIEW'
  accessList.value = await kbApi.accessList(accessKb.value.id)
}

async function changePermission(row) {
  await kbApi.accessGrant(accessKb.value.id, {
    granteeType: row.granteeType,
    granteeId: row.granteeId,
    permission: row.permission
  })
  ElMessage.success('已更新权限')
}

async function revoke(row) {
  await ElMessageBox.confirm(`移除「${row.granteeName}」的访问权限？`, '提示', { type: 'warning' })
  await kbApi.accessRevoke(accessKb.value.id, row.id)
  ElMessage.success('已移除')
  accessList.value = await kbApi.accessList(accessKb.value.id)
}

onMounted(load)
</script>

<style scoped>
.toolbar { margin-bottom: 16px; }
.access-section { border-top: 1px solid #ebeef5; padding-top: 12px; }
.access-title { font-size: 13px; color: #606266; margin-bottom: 8px; }
.access-add { margin-top: 12px; display: flex; align-items: center; }
</style>
