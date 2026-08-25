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
        } else {
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
    ElMessage.error(msg)
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

export default http
