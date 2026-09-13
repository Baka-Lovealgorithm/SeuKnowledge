<template>
  <router-view v-if="isLoginPage" />
  <NoWorkspace v-else-if="auth.isLogin && !auth.workspaces.length" />
  <el-container v-else class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">📚 SeuKnowledge</div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon><span>智能问答</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/stats">
          <el-icon><DataLine /></el-icon><span>问答反馈</span>
        </el-menu-item>
        <el-menu-item index="/review">
          <el-icon><Finished /></el-icon><span>文档分块</span>
        </el-menu-item>
        <el-menu-item v-if="auth.canWrite" index="/curate">
          <el-icon><EditPen /></el-icon><span>文档初洗</span>
        </el-menu-item>
        <el-menu-item index="/kb">
          <el-icon><FolderOpened /></el-icon><span>知识库</span>
        </el-menu-item>
        <el-menu-item v-if="auth.canWrite" index="/bk">
          <el-icon><Notebook /></el-icon><span>业务知识</span>
        </el-menu-item>
        <el-menu-item v-if="auth.canWrite" index="/qa">
          <el-icon><ChatLineSquare /></el-icon><span>问答对</span>
        </el-menu-item>
        <el-menu-item v-if="features.aiExtraction && auth.canWrite" index="/extract">
          <el-icon><MagicStick /></el-icon><span>AI 抽取</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/members">
          <el-icon><User /></el-icon><span>成员管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/groups">
          <el-icon><Share /></el-icon><span>组管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/models">
          <el-icon><Cpu /></el-icon><span>模型配置</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/agent">
          <el-icon><Setting /></el-icon><span>Agent 配置</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-dropdown trigger="click" @command="onWsCommand">
            <span class="ws-switcher">
              <el-icon><OfficeBuilding /></el-icon>
              <span class="ws-name">{{ auth.currentWorkspace?.name || '未选择工作区' }}</span>
              <el-tag v-if="auth.role" size="small" :type="roleTag">{{ roleLabel }}</el-tag>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="w in auth.workspaces" :key="w.id"
                                  :command="{ type: 'switch', id: w.id }"
                                  :class="{ 'ws-active': String(w.id) === auth.wsId }">
                  <span>{{ w.name }}</span>
                  <el-tag size="small" class="ws-role-tag" :type="roleTagOf(w.role)">{{ roleLabelOf(w.role) }}</el-tag>
                </el-dropdown-item>
                <el-dropdown-item divided :command="{ type: 'create' }">＋ 新建工作区</el-dropdown-item>
                <el-dropdown-item v-if="auth.isOwner" :command="{ type: 'rename' }">重命名当前工作区</el-dropdown-item>
                <el-dropdown-item v-if="auth.isAdmin" :command="{ type: 'members' }">成员管理</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="header-title">{{ headerTitle }}</span>
        </div>
        <div class="header-right">
          <span v-if="auth.user?.username">{{ auth.user.username }}</span>
          <el-button link type="primary" @click="openChangePassword">修改密码</el-button>
          <el-button link type="primary" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 新建 / 重命名工作区 -->
    <el-dialog v-model="wsDialogVisible" :title="wsDialogMode === 'create' ? '新建工作区' : '重命名工作区'" width="420px">
      <el-form @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="wsName" maxlength="128" placeholder="请输入工作区名称" @keyup.enter="submitWsDialog" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="wsDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="wsDialogLoading" @click="submitWsDialog">确定</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码（mustChangePassword=true 时为强制改密：不可关闭，仅允许改密或退出） -->
    <el-dialog v-model="pwdVisible" :title="forcedChange ? '请先修改密码' : '修改密码'" width="440px"
               :close-on-click-modal="false" :close-on-press-escape="!forcedChange" :show-close="!forcedChange">
      <el-alert v-if="forcedChange" type="warning" :closable="false" class="pwd-tip"
                title="您的密码已被管理员重置，请先设置新密码后再继续使用。" />
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="当前密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.newPassword" type="password" show-password autocomplete="new-password"
                    placeholder="至少 8 位，须包含字母和数字" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password autocomplete="new-password"
                    @keyup.enter="submitChangePassword" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button v-if="forcedChange" @click="logout">退出登录</el-button>
        <el-button v-else @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitChangePassword">确定修改</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ChatDotRound, ChatLineSquare, Cpu, DataLine, EditPen, Finished, FolderOpened, MagicStick, Notebook, OfficeBuilding, Setting, Share, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from './stores/auth'
import { authApi, workspaceApi } from './api'
import { features } from './config/features'
import NoWorkspace from './views/NoWorkspace.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isLoginPage = computed(() => route.path === '/login')
const activeMenu = computed(() => {
  if (route.path.startsWith('/kb')) return '/kb'
  if (route.path.startsWith('/bk')) return '/bk'
  if (route.path.startsWith('/qa')) return '/qa'
  if (route.path.startsWith('/extract')) return '/extract'
  if (route.path.startsWith('/review')) return '/review'
  if (route.path.startsWith('/curate')) return '/curate'
  if (route.path.startsWith('/models')) return '/models'
  if (route.path.startsWith('/agent')) return '/agent'
  if (route.path.startsWith('/members')) return '/members'
  if (route.path.startsWith('/groups')) return '/groups'
  if (route.path.startsWith('/chat')) return '/chat'
  return '/kb'
})
const headerTitle = computed(() => route.meta.title || 'SeuKnowledge')

const ROLE_LABEL = { OWNER: '拥有者', ADMIN: '管理员', EDITOR: '编辑者', MEMBER: '普通成员' }
const roleLabel = computed(() => ROLE_LABEL[auth.role] || auth.role)
const roleTag = computed(() => ({ OWNER: 'danger', ADMIN: 'warning', EDITOR: 'primary', MEMBER: 'info' }[auth.role] || 'info'))
const roleLabelOf = (r) => ROLE_LABEL[r] || r
const roleTagOf = (r) => ({ OWNER: 'danger', ADMIN: 'warning', EDITOR: 'primary', MEMBER: 'info' }[r] || 'info')

const wsDialogVisible = ref(false)
const wsDialogMode = ref('create')
const wsDialogLoading = ref(false)
const wsName = ref('')

// ===== 修改密码（普通 / 管理员重置后的强制改密共用） =====
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const forcedChange = computed(() => auth.mustChangePassword)

/** 打开改密弹窗：管理员重置后的强制场景自动弹出 */
function openChangePassword() {
  pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  pwdVisible.value = true
}

async function submitChangePassword() {
  const { oldPassword, newPassword, confirmPassword } = pwdForm.value
  if (!oldPassword || !newPassword || !confirmPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdSaving.value = true
  try {
    await authApi.changePassword({ password: oldPassword, newPassword, confirmPassword })
    auth.clearMustChangePassword()
    pwdVisible.value = false
    ElMessage.success('密码已修改')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    pwdSaving.value = false
  }
}

onMounted(() => {
  if (auth.isLogin) {
    auth.refresh().then(() => {
      // 管理员重置后的首次登录：强制弹改密弹窗
      if (auth.mustChangePassword) openChangePassword()
    })
  }
})

async function onWsCommand(cmd) {
  if (cmd.type === 'switch') {
    if (String(cmd.id) !== auth.wsId) {
      auth.switchWorkspace(cmd.id)
    }
    return
  }
  if (cmd.type === 'create') {
    wsDialogMode.value = 'create'
    wsName.value = ''
    wsDialogVisible.value = true
    return
  }
  if (cmd.type === 'rename') {
    wsDialogMode.value = 'rename'
    wsName.value = auth.currentWorkspace?.name || ''
    wsDialogVisible.value = true
    return
  }
  if (cmd.type === 'members') {
    router.push('/members')
  }
}

async function submitWsDialog() {
  if (!wsName.value || !wsName.value.trim()) {
    ElMessage.warning('请输入工作区名称')
    return
  }
  wsDialogLoading.value = true
  try {
    if (wsDialogMode.value === 'create') {
      await auth.createWorkspace(wsName.value.trim())
    } else {
      await workspaceApi.rename({ name: wsName.value.trim() })
      await auth.refresh()
      ElMessage.success('已重命名')
      wsDialogVisible.value = false
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    wsDialogLoading.value = false
  }
}

async function logout() {
  try { await authApi.logout() } catch (e) { /* ignore */ }
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #fff; border-right: 1px solid #e6e6e6; }
.logo { height: 56px; line-height: 56px; text-align: center; font-weight: 700; font-size: 18px; color: #409eff; }
.menu { border-right: none; }
.header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #e6e6e6; }
.header-left { display: flex; align-items: center; gap: 16px; }
.header-title { font-size: 16px; font-weight: 600; }
.header-right { display: flex; align-items: center; gap: 12px; }
.main { background: #f5f7fa; }
.ws-switcher { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 14px; font-weight: 600; color: #303133; outline: none; }
.ws-name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.caret { font-size: 12px; color: #909399; }
.ws-active { background: #ecf5ff; }
.ws-role-tag { margin-left: 8px; }
.pwd-tip { margin-bottom: 14px; }
</style>
