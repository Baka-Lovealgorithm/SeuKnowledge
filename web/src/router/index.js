import { createRouter, createWebHistory } from 'vue-router'
import { features } from '../config/features'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  { path: '/', redirect: '/kb' },
  {
    path: '/kb',
    component: () => import('../views/KnowledgeBase.vue'),
    meta: { auth: true, title: '知识库管理' }
  },
  {
    path: '/kb/:kbId/documents',
    component: () => import('../views/DocumentList.vue'),
    meta: { auth: true, title: '文档管理' }
  },
  {
    path: '/bk',
    component: () => import('../views/BusinessKnowledge.vue'),
    meta: { auth: true, title: '业务知识管理' }
  },
  {
    path: '/qa',
    component: () => import('../views/QaPair.vue'),
    meta: { auth: true, title: '问答对管理' }
  },
  {
    path: '/extract',
    component: () => import('../views/ExtractTask.vue'),
    // 页面及代码保留；默认通过 aiExtraction 开关隐藏并阻止直接访问。
    meta: { auth: true, title: 'AI 抽取任务', feature: 'aiExtraction' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { auth: true, title: '文档精修' }
  },
  {
    path: '/curate',
    component: () => import('../views/CurateGate.vue'),
    meta: { auth: true, title: '文档初洗' }
  },
  {
    path: '/curate/:docId',
    component: () => import('../views/CurateGate.vue'),
    meta: { auth: true, title: '文档初洗' }
  },
  {
    path: '/models',
    component: () => import('../views/ModelConfig.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: '模型配置' }
  },
  {
    path: '/agent',
    component: () => import('../views/AgentConfig.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: 'Agent 配置' }
  },
  { path: '/prompts', redirect: '/agent' },
  {
    path: '/members',
    component: () => import('../views/MemberManage.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: '成员管理' }
  },
  {
    path: '/groups',
    component: () => import('../views/GroupManage.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: '组管理' }
  },
  {
    path: '/chat',
    component: () => import('../views/Chat.vue'),
    meta: { auth: true, title: '智能问答' }
  },
  {
    path: '/stats',
    component: () => import('../views/QaStats.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: '问答反馈汇总' }
  }
]

/**
 * 管理类页面清单：从路由表的 meta.roles 派生，新增管理页自动生效
 * （原先是守卫里手写的 || 串联，加新页时漏列会让无权限用户停在空白页）。
 */
const MANAGED_PAGES = routes.filter((r) => r.meta && r.meta.roles).map((r) => r.path)

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.auth && !token) {
    return '/login'
  }
  if (to.meta.feature && !features[to.meta.feature]) {
    return '/kb'
  }
  if (to.meta.roles) {
    // 角色按「当前工作空间」解析（切换工作空间后 seu_user 中的 role 为上一空间值，需按 seu_ws 匹配）
    const raw = JSON.parse(localStorage.getItem('seu_user') || 'null')
    const wsId = localStorage.getItem('seu_ws')
    let role = ''
    if (raw && Array.isArray(raw.workspaces) && raw.workspaces.length) {
      const cur = raw.workspaces.find((w) => String(w.id) === String(wsId)) || raw.workspaces[0]
      role = (cur && cur.role) || ''
    } else if (raw) {
      role = raw.role || ''
    }
    if (!role || !to.meta.roles.includes(role)) {
      // 无权限访问管理页：这些页面回到知识库（只读首页），其余中止导航留在原页。
      // 用清单而不是逐条 || 串联，避免新加管理页时漏列导致无权限用户落到空白页。
      return MANAGED_PAGES.includes(to.path) ? '/' : false
    }
  }
  return true
})

export default router
