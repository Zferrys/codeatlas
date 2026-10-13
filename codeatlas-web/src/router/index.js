import { createRouter, createWebHashHistory } from 'vue-router'
import { Modal } from 'ant-design-vue'
import { useAuthStore } from '../stores/auth'
import { hasActiveAiTask, cancelActiveAiTask } from '../composables/useAiTask'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { guest: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/Register.vue'),
    meta: { guest: true }
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('../views/Dashboard.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/Profile.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin/dashboard',
    name: 'AdminDashboard',
    component: () => import('../views/admin/AdminDashboard.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/admin/users',
    name: 'AdminUsers',
    component: () => import('../views/admin/AdminUsers.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/admin/audit-log',
    name: 'AdminAuditLog',
    component: () => import('../views/admin/AdminAuditLog.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/admin/ai-models',
    name: 'AdminAiModels',
    component: () => import('../views/admin/AdminAiModels.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/project/:id',
    name: 'ProjectDetail',
    component: () => import('../views/ProjectDetail.vue'),
    meta: { requiresAuth: true },
    redirect: { name: 'ProjectOverview' },
    children: [
      {
        path: 'overview',
        name: 'ProjectOverview',
        component: () => import('../views/ProjectOverview.vue')
      },
      {
        path: 'map',
        name: 'ProjectMap',
        component: () => import('../views/ProjectMap.vue')
      },
      {
        path: 'story',
        name: 'ProjectStory',
        component: () => import('../views/ProjectStory.vue')
      },
      {
        path: 'rules',
        name: 'ProjectRules',
        component: () => import('../views/ProjectRules.vue')
      },
      {
        path: 'violations',
        name: 'ProjectViolations',
        component: () => import('../views/ProjectViolations.vue')
      },
      {
        path: 'insights',
        name: 'ProjectInsights',
        component: () => import('../views/ProjectInsights.vue')
      },
      {
        path: 'settings',
        name: 'ProjectSettings',
        component: () => import('../views/ProjectSettings.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// Navigation guard
// 离开确认弹窗去重标志：弹窗打开期间用户再次点击导航时不重复弹
let leaveConfirmOpen = false

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
  } else if (to.meta.requiresAdmin && authStore.user?.role !== 'ADMIN') {
    next({ name: 'Dashboard' })
  } else if (to.meta.guest && authStore.isLoggedIn) {
    next({ name: 'Dashboard' })
  } else if (hasActiveAiTask()) {
    if (leaveConfirmOpen) {
      // 弹窗已打开：挂起后续导航（浏览器后退等），避免绕过离开确认直接放行
      next(false)
    } else {
      leaveConfirmOpen = true
      Modal.confirm({
        title: 'AI 任务正在进行中',
        content: '离开当前页面将终止本次 AI 回答，确定要离开吗？',
        okText: '停止并离开',
        okType: 'danger',
        cancelText: '继续等待',
        onOk: () => {
          leaveConfirmOpen = false
          cancelActiveAiTask()
          next()
        },
        onCancel: () => {
          leaveConfirmOpen = false
          next(false)
        }
      })
    }
  } else {
    next()
  }
})

export default router
