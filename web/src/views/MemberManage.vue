<template>
  <div>
    <div class="toolbar">
      <span class="ws-name">工作区：{{ auth.currentWorkspace?.name || '-' }}</span>
      <el-button type="primary" @click="openCreate">新建成员</el-button>
      <el-button @click="openInvite">邀请已有用户</el-button>
      <el-button v-if="auth.isOwner" link type="danger" @click="deleteWorkspace">删除工作空间</el-button>
      <div class="filters">
        <el-select v-model="roleFilter" placeholder="身份" style="width: 130px">
          <el-option label="全部身份" value="" />
          <el-option label="拥有者" value="OWNER" />
          <el-option label="管理员" value="ADMIN" />
          <el-option label="编辑者" value="EDITOR" />
          <el-option label="普通成员" value="MEMBER" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索用户名" clearable style="width: 180px" />
        <el-tooltip placement="top">
          <template #content>
            <div class="role-tip">
              <div>拥有者（唯一）</div>
              <div>管理员（可管理成员与模型）</div>
              <div>编辑者（内容生产）</div>
              <div>普通成员（只读）</div>
            </div>
          </template>
          <el-icon class="tip-icon"><QuestionFilled /></el-icon>
        </el-tooltip>
      </div>
    </div>

    <el-table :data="pagedList" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="roleTag(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="加入时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }">
          <template v-if="row.role !== 'OWNER'">
            <el-select :model-value="row.role" size="small" style="width: 100px; margin-right: 6px"
                       :disabled="!auth.isAdmin" @change="(v) => changeRole(row, v)">
              <el-option label="管理员" value="ADMIN" />
              <el-option label="编辑者" value="EDITOR" />
              <el-option label="普通成员" value="MEMBER" />
            </el-select>
            <el-button v-if="auth.isOwner" link type="primary" @click="transfer(row)">转让</el-button>
            <el-button v-if="auth.isAdmin" link type="warning" @click="resetPwd(row)">重置密码</el-button>
            <el-button v-if="auth.isAdmin" link type="danger" @click="remove(row)">移除</el-button>
          </template>
          <span v-else class="owner-badge">拥有者（不可变更）</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <Pager v-model:page="pageIndex" v-model:page-size="pageSize"
             :total="filteredList.length" :page-sizes="[20, 50]" />
    </div>

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

    <!-- 重置密码结果（仅显示一次） -->
    <el-dialog v-model="resetResultVisible" title="密码已重置" width="460px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" class="reset-tip"
                title="新密码仅本次显示，请立即复制并告知该用户；其下次登录时将强制修改密码。" />
      <div class="reset-pwd-row">
        <span class="reset-pwd-user">{{ resetResult.username }}</span>
        <code class="reset-pwd-value">{{ resetResult.newPassword }}</code>
        <el-button size="small" type="primary" @click="copyResetPassword">复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="resetResultVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { workspaceApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { confirmAction } from '../utils/confirm'
import { copyText } from '../utils/clipboard'
import { formatDateTime } from '../utils/format'
import Pager from '../components/Pager.vue'

const auth = useAuthStore()
const members = ref([])
const loading = ref(false)
const createVisible = ref(false)
const saving = ref(false)
const form = reactive({ username: '', password: '', role: 'EDITOR' })
const inviteVisible = ref(false)
const inviting = ref(false)
const inviteForm = reactive({ username: '', role: 'MEMBER' })

// ===== 过滤 + 分页（纯前端）：成员列表接口一次返回全量，筛选与翻页都在浏览器侧完成 =====
const roleFilter = ref('')  // 身份：'' = 全部
const keyword = ref('')     // 用户名模糊匹配（不区分大小写，忽略首尾空格）
const pageIndex = ref(1)
const pageSize = ref(20)

const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return members.value.filter((m) => {
    if (roleFilter.value && m.role !== roleFilter.value) return false
    if (kw && !String(m.username || '').toLowerCase().includes(kw)) return false
    return true
  })
})

/** 当前页数据（前端切片） */
const pagedList = computed(() => {
  const start = (pageIndex.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})

// 过滤条件或每页条数变化 → 回第 1 页
watch([roleFilter, keyword, pageSize], () => { pageIndex.value = 1 })
// 结果集收缩（移除成员 / 改过滤）后当前页可能越界 → 收敛到最后一页
watch(filteredList, (rows) => {
  const maxPage = Math.max(1, Math.ceil(rows.length / pageSize.value))
  if (pageIndex.value > maxPage) pageIndex.value = maxPage
})

const roleLabel = (r) => ({ OWNER: '拥有者', ADMIN: '管理员', EDITOR: '编辑者', MEMBER: '普通成员' }[r] || r)
const roleTag = (r) => ({ OWNER: 'danger', ADMIN: 'warning', EDITOR: 'primary', MEMBER: 'info' }[r] || 'info')
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
  if (!(await confirmAction(`将成员「${row.username}」的角色改为「${roleLabel(role)}」？`, '修改角色'))) return
  await workspaceApi.updateRole(row.id, role)
  ElMessage.success('角色已更新')
  load()
}

async function remove(row) {
  if (!(await confirmAction(`确定移除成员「${row.username}」？该账号将无法登录。`, '移除成员'))) return
  await workspaceApi.removeMember(row.id)
  ElMessage.success('已移除')
  load()
}

/** 重置密码：生成随机密码仅本次展示，用户下次登录强制修改 */
const resetResultVisible = ref(false)
const resetResult = ref({ username: '', newPassword: '' })

async function resetPwd(row) {
  if (!(await confirmAction(
    `确定重置「${row.username}」的密码？将生成随机密码且仅显示一次，该用户下次登录时须先修改密码。`,
    '重置密码', { confirmButtonText: '重置', cancelButtonText: '取消' }))) return
  const r = await workspaceApi.resetMemberPassword(row.userId)
  resetResult.value = { username: r.username, newPassword: r.newPassword }
  resetResultVisible.value = true
  load()
}

async function copyResetPassword() {
  const ok = await copyText(resetResult.value.newPassword)
  if (ok) ElMessage.success('已复制')
  else ElMessage.warning('复制失败，请手动选择复制')
}

async function transfer(row) {
  if (!(await confirmAction(
    `转让拥有权给「${row.username}」？转让后您将变为管理员，且无法撤销（除非对方再转让给您）。`,
    '转让工作空间',
    { confirmButtonText: '转让' }
  ))) return
  await workspaceApi.transferOwnership(row.id)
  ElMessage.success('已转让，角色已更新')
  auth.refresh()
  load()
}

async function deleteWorkspace() {
  if (!(await confirmAction(
    '删除当前工作空间将归档全部知识库并移除所有成员，此操作不可恢复！确定删除？',
    '删除工作空间',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  ))) return
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
.filters { display: flex; align-items: center; gap: 12px; margin-left: auto; }
.tip-icon { color: #909399; font-size: 16px; cursor: help; }
.role-tip div { line-height: 1.7; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.owner-badge { color: #f56c6c; font-size: 12px; }
.ws-name { font-weight: 600; }
.reset-tip { margin-bottom: 14px; }
.reset-pwd-row { display: flex; align-items: center; gap: 12px; }
.reset-pwd-user { font-weight: 600; }
.reset-pwd-value { flex: 1; background: #f5f7fa; border: 1px solid #e6e6e6; border-radius: 4px; padding: 6px 10px; font-size: 14px; letter-spacing: 1px; }
</style>
