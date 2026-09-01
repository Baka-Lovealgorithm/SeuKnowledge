import { createRouter, createWebHistory } from 'vue-router'

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
    path: '/kb/:kbId/agent',
    component: () => import('../views/AgentConfig.vue'),
    meta: { auth: true, title: 'Agent 配置' }
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
    meta: { auth: true, title: 'AI 抽取任务' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { auth: true, title: '清洗复核' }
  },
  {
    path: '/curate/:docId',
    component: () => import('../views/CurateGate.vue'),
    meta: { auth: true, title: '文档策展' }
  },
  {
    path: '/models',
    component: () => import('../views/ModelConfig.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: '模型配置' }
  },
  {
    path: '/members',
    component: () => import('../views/MemberManage.vue'),
    meta: { auth: true, roles: ['OWNER', 'ADMIN'], title: '成员管理' }
  },
  {
    path: '/chat',
    component: () => import('../views/Chat.vue'),
    meta: { auth: true, title: '智能问答' }
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.auth && !token) {
    return '/login'
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
      // 无权限访问管理页：普通成员回到知识库（只读），其余回首页
      return to.path === '/members' || to.path === '/models' ? '/' : false
    }
  }
  return true
})

export default router
