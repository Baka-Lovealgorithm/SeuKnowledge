<template>
  <div>
    <div class="toolbar">
      <span class="ws-name">工作区：{{ auth.currentWorkspace?.name || '-' }}</span>
      <el-button type="primary" @click="openCreate">新建成员</el-button>
      <el-button @click="openInvite">邀请已有用户</el-button>
      <el-button v-if="auth.isOwner" link type="danger" @click="deleteWorkspace">删除工作空间</el-button>
      <span class="tip">角色：拥有者（唯一） / 管理员（可管理成员与模型） / 编辑者（内容生产） / 普通成员（只读）</span>
    </div>

    <el-table :data="members" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="roleTag(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="加入时间" width="170">
        <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <template v-if="row.role !== 'OWNER'">
            <el-select :model-value="row.role" size="small" style="width: 100px; margin-right: 6px"
                       :disabled="!auth.isAdmin" @change="(v) => changeRole(row, v)">
              <el-option label="管理员" value="ADMIN" />
              <el-option label="编辑者" value="EDITOR" />
              <el-option label="普通成员" value="MEMBER" />
            </el-select>
            <el-button v-if="auth.isOwner" link type="primary" @click="transfer(row)">转让</el-button>
            <el-button v-if="auth.isAdmin" link type="danger" @click="remove(row)">移除</el-button>
          </template>
          <span v-else class="owner-badge">拥有者（不可变更）</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新建成员" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="登录用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="初始密码" show-password />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="编辑者" value="EDITOR" />
            <el-option label="普通成员" value="MEMBER" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="inviteVisible" title="邀请已有用户" width="420px">
      <el-form :model="inviteForm" label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="inviteForm.username" placeholder="输入已在系统注册的用户名" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="inviteForm.role" style="width: 100%">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="编辑者" value="EDITOR" />
            <el-option label="普通成员" value="MEMBER" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" :loading="inviting" @click="submitInvite">邀请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { workspaceApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const members = ref([])
const loading = ref(false)
const createVisible = ref(false)
const saving = ref(false)
const form = reactive({ username: '', password: '', role: 'EDITOR' })
const inviteVisible = ref(false)
const inviting = ref(false)
const inviteForm = reactive({ username: '', role: 'MEMBER' })

const roleLabel = (r) => ({ OWNER: '拥有者', ADMIN: '管理员', EDITOR: '编辑者', MEMBER: '普通成员' }[r] || r)
const roleTag = (r) => ({ OWNER: 'danger', ADMIN: 'warning', EDITOR: 'primary', MEMBER: 'info' }[r] || 'info')
const fmt = (t) => (t ? t.replace('T', ' ').slice(0, 19) : '')

async function load() {
  loading.value = true
  try { members.value = await workspaceApi.members() } finally { loading.value = false }
}

function openCreate() {
  form.username = ''
  form.password = ''
  form.role = 'EDITOR'
  createVisible.value = true
}

async function submit() {
  if (!form.username || !form.password) { ElMessage.warning('请填写用户名和密码'); return }
  saving.value = true
  try {
    await workspaceApi.createMember({ username: form.username, password: form.password, role: form.role })
    ElMessage.success('成员已创建')
    createVisible.value = false
    load()
  } finally { saving.value = false }
}

function openInvite() {
  inviteForm.username = ''
  inviteForm.role = 'MEMBER'
  inviteVisible.value = true
}

async function submitInvite() {
  if (!inviteForm.username || !inviteForm.username.trim()) { ElMessage.warning('请输入要邀请的用户名'); return }
  inviting.value = true
  try {
    await workspaceApi.inviteMember({ username: inviteForm.username.trim(), role: inviteForm.role })
    ElMessage.success('已邀请加入本工作区')
    inviteVisible.value = false
    load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally { inviting.value = false }
}

async function changeRole(row, role) {
  await ElMessageBox.confirm(`将成员「${row.username}」的角色改为「${roleLabel(role)}」？`, '修改角色', { type: 'warning' })
  await workspaceApi.updateRole(row.id, role)
  ElMessage.success('角色已更新')
  load()
}

async function remove(row) {
  await ElMessageBox.confirm(`确定移除成员「${row.username}」？该账号将无法登录。`, '移除成员', { type: 'warning' })
  await workspaceApi.removeMember(row.id)
  ElMessage.success('已移除')
  load()
}

async function transfer(row) {
  await ElMessageBox.confirm(
    `转让拥有权给「${row.username}」？转让后您将变为管理员，且无法撤销（除非对方再转让给您）。`,
    '转让工作空间',
    { type: 'warning', confirmButtonText: '转让' }
  )
  await workspaceApi.transferOwnership(row.id)
  ElMessage.success('已转让，角色已更新')
  auth.refresh()
  load()
}

async function deleteWorkspace() {
  await ElMessageBox.confirm(
    '删除当前工作空间将归档全部知识库并移除所有成员，此操作不可恢复！确定删除？',
    '删除工作空间',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  await workspaceApi.deleteWorkspace()
  ElMessage.error('工作空间已删除')
  // 刷新用户信息：落到剩余工作区或无工作区引导页
  await auth.refresh()
  location.reload()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.tip { color: #909399; font-size: 12px; }
.owner-badge { color: #f56c6c; font-size: 12px; }
.ws-name { font-weight: 600; }
</style>
