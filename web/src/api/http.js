import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useAuthStore } from '../stores/auth'

const http = axios.create({ baseURL: '/api', timeout: 90000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // 多工作空间：请求携带当前选中的工作空间 id
  const wsId = localStorage.getItem('seu_ws')
  if (wsId) {
    config.headers['X-Workspace-Id'] = wsId
  }
  // 请求链路 id：后端 access log（reqId）与 app.log/llm.log 的 MDC requestId 一致，便于一次 grep 串起整条链路
  config.headers['X-Request-Id'] = config.headers['X-Request-Id'] || genRequestId()
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        // 工作空间相关 403（被移出/空间被删除/无空间）：刷新用户信息后整页重载自愈
        if (body.code === 403 && /工作空间|无权访问该工作空间/.test(body.message || '')) {
          handleWorkspaceLost(body.message)
        } else if (!res.config?.silentError) {
          // silentError：调用方自行汇总（批量上传逐文件请求），不在这里逐条 toast
          ElMessage.error(body.message || '请求失败')
        }
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  (err) => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('token')
      router.push('/login')
    }
    const msg = err.response?.data?.message || err.message || '网络错误'
    // silentError：调用方自行汇总错误时（如批量上传逐文件请求），抑制拦截器的逐条 toast，
    // 避免"10 个文件失败 = 10 条 toast + 1 条汇总"的刷屏
    if (!err.config?.silentError) {
      ElMessage.error(msg)
    }
    return Promise.reject(err)
  }
)

/** 工作空间失效（被移出/删除/无空间）：刷新 /me 后重载，落到剩余空间或无空间引导页；避免死循环 */
let workspaceRecovering = false
async function handleWorkspaceLost(message) {
  if (workspaceRecovering) {
    return
  }
  workspaceRecovering = true
  try {
    const auth = useAuthStore()
    const data = await auth.refresh()
    if (data) {
      window.location.reload()
    } else {
      ElMessage.error(message || '工作空间不可用')
    }
  } catch (e) {
    ElMessage.error(message || '工作空间不可用')
  } finally {
    setTimeout(() => {
      workspaceRecovering = false
    }, 1500)
  }
}

/** 生成 32 位 hex 请求 id（与后端自生成格式一致）；优先 crypto.randomUUID，老环境兜底 */
function genRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  return 'xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export default http
