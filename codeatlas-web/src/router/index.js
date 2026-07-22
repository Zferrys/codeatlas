import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

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
    path: '/project/:id',
    name: 'ProjectDetail',
    component: () => import('../views/ProjectDetail.vue'),
    meta: { requiresAuth: true },
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
router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    next({ name: 'Login', query: { redirect: to.fullPath } })
  } else if (to.meta.guest && authStore.isLoggedIn) {
    next({ name: 'Dashboard' })
  } else {
    next()
  }
})

export default router
