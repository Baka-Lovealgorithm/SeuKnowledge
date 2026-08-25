import { defineStore } from 'pinia'
import { authApi, workspaceApi } from '../api'

/** 工作空间角色 */
export const ROLE = {
  OWNER: 'OWNER',
  ADMIN: 'ADMIN',
  EDITOR: 'EDITOR',
  MEMBER: 'MEMBER'
}

const USER_KEY = 'seu_user'
/** 当前选中的工作空间 id（多工作空间切换） */
const WS_KEY = 'seu_ws'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

function readWsId() {
  return localStorage.getItem(WS_KEY) || ''
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: readUser(),
    wsId: readWsId()
  }),
  getters: {
    isLogin: (s) => !!s.token,
    /** 当前工作空间下的角色（随切换变化） */
    role: (s) => {
      if (s.user && s.user.workspaces) {
        const cur = s.user.workspaces.find((w) => String(w.id) === String(s.wsId))
        if (cur) return cur.role
      }
      return (s.user && s.user.role) || ''
    },
    /** 当前工作空间 id */
    workspaceId: (s) => (s.user && s.user.workspaces && s.user.workspaces.length ? s.wsId : null),
    /** 用户所属工作空间列表 */
    workspaces: (s) => (s.user && s.user.workspaces) || [],
    /** 当前工作空间信息（含名称） */
    currentWorkspace: (s) => {
      if (s.user && s.user.workspaces) {
        return s.user.workspaces.find((w) => String(w.id) === String(s.wsId)) || s.user.workspaces[0] || null
      }
      return null
    },
    /** 可写内容（知识库/文档/业务知识/问答对/抽取）：EDITOR+ */
    canWrite: (s) => ['OWNER', 'ADMIN', 'EDITOR'].includes(s.role),
    /** 系统级配置（模型/成员管理）：ADMIN+ */
    isAdmin: (s) => ['OWNER', 'ADMIN'].includes(s.role),
    /** 拥有者 */
    isOwner: (s) => s.role === 'OWNER'
  },
  actions: {
    async login(username, password) {
      const data = await authApi.login({ username, password })
      this.token = data.token
      this.user = data
      // 当前工作空间：本地记忆优先（若仍属于该空间），否则第一个
      const remembered = localStorage.getItem(WS_KEY)
      const valid = data.workspaces && data.workspaces.some((w) => String(w.id) === remembered)
      this.wsId = valid ? remembered : (data.workspaces && data.workspaces[0] ? String(data.workspaces[0].id) : '')
      localStorage.setItem('token', data.token)
      localStorage.setItem(USER_KEY, JSON.stringify(data))
      localStorage.setItem(WS_KEY, this.wsId)
    },
    /** 刷新当前用户信息（角色/工作空间可能变化） */
    async refresh() {
      try {
        const data = await authApi.me()
        if (data && data.userId) {
          this.user = data
          localStorage.setItem(USER_KEY, JSON.stringify(data))
          // 校验本地记忆的空间仍存在，否则回退第一个
          const valid = data.workspaces && data.workspaces.some((w) => String(w.id) === this.wsId)
          if (!valid) {
            this.wsId = data.workspaces && data.workspaces[0] ? String(data.workspaces[0].id) : ''
            localStorage.setItem(WS_KEY, this.wsId)
          }
          return data
        }
      } catch (e) {
        /* 未登录 */
      }
      return null
    },
    /** 切换当前工作空间：更新本地状态并整页刷新（各页面 onMounted 重新拉取数据） */
    switchWorkspace(id) {
      this.wsId = String(id)
      localStorage.setItem(WS_KEY, this.wsId)
      window.location.reload()
    },
    /** 创建工作空间并切换（创建者成为 OWNER） */
    async createWorkspace(name) {
      const data = await workspaceApi.create({ name })
      await this.refresh()
      this.wsId = String(data.id)
      localStorage.setItem(WS_KEY, this.wsId)
      window.location.reload()
    },
    logout() {
      this.token = ''
      this.user = null
      this.wsId = ''
      localStorage.removeItem('token')
      localStorage.removeItem(USER_KEY)
      localStorage.removeItem(WS_KEY)
    }
  }
})
