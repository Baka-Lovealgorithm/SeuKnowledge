<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-title">📚 SeuKnowledge 知识库平台</div>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="请输入用户名" size="large"
                    :readonly="autofillGuard" @focus="autofillGuard = false" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large" show-password
                    :readonly="autofillGuard" @focus="autofillGuard = false" />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })
/** 初始只读防浏览器凭证自动填充（聚焦即解锁，正常输入不受影响） */
const autofillGuard = ref(true)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    /* 错误提示由拦截器处理 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #409eff33, #fff); }
.login-card { width: 380px; padding: 20px 12px; }
.login-title { text-align: center; font-size: 20px; font-weight: 700; margin-bottom: 24px; }
</style>
