<template>
  <div class="nows-page">
    <el-card class="nows-card">
      <div class="nows-title">👋 欢迎使用 SeuKnowledge</div>
      <div class="nows-desc">
        当前账号尚未加入任何工作区。创建一个工作区后即可管理知识库、模型配置与团队成员
        （创建者自动成为<b>拥有者</b>）。
      </div>
      <el-form @submit.prevent>
        <el-form-item label="工作区名称">
          <el-input v-model="name" maxlength="128" placeholder="例如：研发团队知识库" size="large" @keyup.enter="create" />
        </el-form-item>
      </el-form>
      <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="create">
        创建工作区
      </el-button>
      <div class="tip">创建后请先在「模型配置」中添加文本与向量模型，再上传文档并提问。</div>
      <div class="tip">
        <el-button link type="info" @click="logout">退出登录</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { authApi } from '../api'

const router = useRouter()
const auth = useAuthStore()
const name = ref('')
const loading = ref(false)

async function create() {
  if (!name.value || !name.value.trim()) {
    ElMessage.warning('请输入工作区名称')
    return
  }
  loading.value = true
  try {
    await auth.createWorkspace(name.value.trim())
    ElMessage.success('工作区创建成功')
    router.push('/')
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function logout() {
  try { await authApi.logout() } catch (e) { /* ignore */ }
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.nows-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #409eff33, #fff); }
.nows-card { width: 440px; padding: 20px 12px; }
.nows-title { text-align: center; font-size: 20px; font-weight: 700; margin-bottom: 12px; }
.nows-desc { color: #606266; font-size: 13px; margin-bottom: 20px; line-height: 1.7; }
.tip { margin-top: 14px; text-align: center; color: #909399; font-size: 12px; }
</style>
