import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'

const { confirmMock, hasActiveAiTaskMock, cancelActiveAiTaskMock } = vi.hoisted(() => ({
  confirmMock: vi.fn(),
  hasActiveAiTaskMock: vi.fn(() => false),
  cancelActiveAiTaskMock: vi.fn()
}))

vi.mock('ant-design-vue', () => ({ Modal: { confirm: confirmMock } }))
vi.mock('../composables/useAiTask', () => ({
  AI_TASK_CANCELLED: Symbol('AI_TASK_CANCELLED'),
  hasActiveAiTask: hasActiveAiTaskMock,
  cancelActiveAiTask: cancelActiveAiTaskMock
}))

// 页面组件全部桩掉：路由测试只关心路由表与守卫，不加载真实视图
const { viewStub } = vi.hoisted(() => ({
  viewStub: name => ({ default: { name, render: () => null } })
}))
vi.mock('../views/Login.vue', () => viewStub('LoginView'))
vi.mock('../views/Register.vue', () => viewStub('RegisterView'))
vi.mock('../views/Dashboard.vue', () => viewStub('DashboardView'))
vi.mock('../views/Profile.vue', () => viewStub('ProfileView'))
vi.mock('../views/admin/AdminDashboard.vue', () => viewStub('AdminDashboardView'))
vi.mock('../views/admin/AdminUsers.vue', () => viewStub('AdminUsersView'))
vi.mock('../views/admin/AdminAuditLog.vue', () => viewStub('AdminAuditLogView'))
vi.mock('../views/admin/AdminAiModels.vue', () => viewStub('AdminAiModelsView'))
vi.mock('../views/admin/AdminAiUsage.vue', () => viewStub('AdminAiUsageView'))
vi.mock('../views/ProjectDetail.vue', () => viewStub('ProjectDetailView'))
vi.mock('../views/ProjectOverview.vue', () => viewStub('ProjectOverviewView'))
vi.mock('../views/ProjectMap.vue', () => viewStub('ProjectMapView'))
vi.mock('../views/ProjectStory.vue', () => viewStub('ProjectStoryView'))
vi.mock('../views/ProjectRules.vue', () => viewStub('ProjectRulesView'))
vi.mock('../views/ProjectViolations.vue', () => viewStub('ProjectViolationsView'))
vi.mock('../views/ProjectInsights.vue', () => viewStub('ProjectInsightsView'))
vi.mock('../views/ProjectSettings.vue', () => viewStub('ProjectSettingsView'))

import router from './index'
import { useAuthStore } from '../stores/auth'

const routes = router.options.routes
const findRoute = path => routes.find(r => r.path === path)

describe('route table', () => {
  it('routes_whenRootVisited_shouldRedirectToDashboard', () => {
    expect(findRoute('/').redirect).toBe('/dashboard')
  })

  it('routes_whenGuestPages_shouldBeMarkedGuestAndLazyLoaded', () => {
    const login = findRoute('/login')
    const register = findRoute('/register')

    expect(login.name).toBe('Login')
    expect(login.meta).toEqual({ guest: true })
    expect(typeof login.component).toBe('function')
    expect(register.name).toBe('Register')
    expect(register.meta).toEqual({ guest: true })
    expect(typeof register.component).toBe('function')
  })

  it('routes_whenProtectedPages_shouldRequireAuth', () => {
    for (const path of ['/dashboard', '/profile']) {
      const route = findRoute(path)
      expect(route.meta.requiresAuth).toBe(true)
      expect(typeof route.component).toBe('function')
    }
    expect(findRoute('/dashboard').name).toBe('Dashboard')
    expect(findRoute('/profile').name).toBe('Profile')
  })

  it('routes_whenAdminPages_shouldRequireAdminAndAuth', () => {
    const expected = {
      '/admin/dashboard': 'AdminDashboard',
      '/admin/users': 'AdminUsers',
      '/admin/audit-log': 'AdminAuditLog',
      '/admin/ai-models': 'AdminAiModels',
      '/admin/token-usage': 'AdminTokenUsage'
    }
    for (const [path, name] of Object.entries(expected)) {
      const route = findRoute(path)
      expect(route.name).toBe(name)
      expect(route.meta).toEqual({ requiresAuth: true, requiresAdmin: true })
      expect(typeof route.component).toBe('function')
    }
  })

  it('routes_whenProjectDetail_shouldRedirectToOverviewWithSevenTabs', () => {
    const project = findRoute('/project/:id')

    expect(project.name).toBe('ProjectDetail')
    expect(project.meta).toEqual({ requiresAuth: true })
    expect(project.redirect).toEqual({ name: 'ProjectOverview' })
    expect(typeof project.component).toBe('function')

    const tabNames = project.children.map(c => c.name)
    expect(tabNames).toEqual([
      'ProjectOverview', 'ProjectMap', 'ProjectStory', 'ProjectRules',
      'ProjectViolations', 'ProjectInsights', 'ProjectSettings'
    ])
    expect(project.children.map(c => c.path)).toEqual([
      'overview', 'map', 'story', 'rules', 'violations', 'insights', 'settings'
    ])
    for (const child of project.children) {
      expect(typeof child.component).toBe('function')
    }
  })

  it('routes_whenLazyLoaded_shouldResolveEveryComponentFunction', async () => {
    const records = routes.flatMap(route => [route, ...(route.children || [])])
    const lazyRecords = records.filter(route => typeof route.component === 'function')

    expect(lazyRecords).toHaveLength(17)
    for (const record of lazyRecords) {
      const module = await record.component()
      expect(module.default).toBeTruthy()
    }
  })

  // 模块在用例内动态加载（vi.resetModules + import）：模块求值期执行的静态变异体
  // 才会被归属到本文件的用例，否则路由表结构变异只能由 main.test.js 覆盖
  it('routes_whenFreshlyImported_shouldMatchExactRouteTableShape', async () => {
    vi.resetModules()
    const { default: freshRouter } = await import('./index')

    const pickShape = list => list.map(route => ({
      path: route.path,
      name: route.name,
      meta: route.meta,
      redirect: route.redirect,
      children: route.children ? pickShape(route.children) : undefined
    }))

    expect(pickShape(freshRouter.options.routes)).toEqual([
      { path: '/', redirect: '/dashboard' },
      { path: '/login', name: 'Login', meta: { guest: true } },
      { path: '/register', name: 'Register', meta: { guest: true } },
      { path: '/dashboard', name: 'Dashboard', meta: { requiresAuth: true } },
      { path: '/profile', name: 'Profile', meta: { requiresAuth: true } },
      { path: '/admin/dashboard', name: 'AdminDashboard', meta: { requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/users', name: 'AdminUsers', meta: { requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/audit-log', name: 'AdminAuditLog', meta: { requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/ai-models', name: 'AdminAiModels', meta: { requiresAuth: true, requiresAdmin: true } },
      { path: '/admin/token-usage', name: 'AdminTokenUsage', meta: { requiresAuth: true, requiresAdmin: true } },
      {
        path: '/project/:id',
        name: 'ProjectDetail',
        meta: { requiresAuth: true },
        redirect: { name: 'ProjectOverview' },
        children: [
          { path: 'overview', name: 'ProjectOverview' },
          { path: 'map', name: 'ProjectMap' },
          { path: 'story', name: 'ProjectStory' },
          { path: 'rules', name: 'ProjectRules' },
          { path: 'violations', name: 'ProjectViolations' },
          { path: 'insights', name: 'ProjectInsights' },
          { path: 'settings', name: 'ProjectSettings' }
        ]
      }
    ])
  })

  it('routes_whenFreshlyImported_shouldResolveAllSeventeenLazyComponentDefaults', async () => {
    vi.resetModules()
    const { default: freshRouter } = await import('./index')

    const records = freshRouter.options.routes.flatMap(route => [route, ...(route.children || [])])
    const lazyRecords = records.filter(record => typeof record.component === 'function')

    expect(lazyRecords).toHaveLength(17)
    for (const record of lazyRecords) {
      const module = await record.component()
      expect(module && module.default).toBeTruthy()
    }
  })
})

describe('navigation guard', () => {
  let bootCount = 0

  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    hasActiveAiTaskMock.mockReset()
    hasActiveAiTaskMock.mockReturnValue(false)
    confirmMock.mockReset()
    cancelActiveAiTaskMock.mockReset()
    // 固定匿名起点：query 带唯一序号，避免与上一用例的 location 判重导致守卫被短路
    bootCount += 1
    await router.push({ path: '/login', query: { boot: String(bootCount) } })
    expect(router.currentRoute.value.path).toBe('/login')
  })

  function loginAs(role = 'DEVELOPER') {
    useAuthStore().setAuth('tok-guard', { id: 1, username: 'guard-user', role })
  }

  it('guard_whenAnonymousHitsProtectedPage_shouldRedirectToLoginWithRedirectQuery', async () => {
    await router.push('/dashboard')

    expect(router.currentRoute.value.name).toBe('Login')
    expect(router.currentRoute.value.query.redirect).toBe('/dashboard')
  })

  it('guard_whenLoggedInHitsGuestPage_shouldRedirectToDashboard', async () => {
    loginAs()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('Dashboard')
  })

  it('guard_whenNonAdminHitsAdminPage_shouldRedirectToDashboard', async () => {
    loginAs('DEVELOPER')

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('Dashboard')
  })

  it('guard_whenAdminHitsAdminPage_shouldAllowNavigation', async () => {
    loginAs('ADMIN')

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('AdminUsers')
  })

  it('guard_whenProjectTabVisitedByLoggedInUser_shouldAllowNavigation', async () => {
    loginAs()

    await router.push('/project/42/map')

    expect(router.currentRoute.value.name).toBe('ProjectMap')
    expect(router.currentRoute.value.params.id).toBe('42')
  })

  it('guard_whenAiTaskRunning_shouldConfirmBeforeLeavingAndCancelOnOk', async () => {
    loginAs()
    await router.push('/profile')
    hasActiveAiTaskMock.mockReturnValue(true)

    const navigation = router.push('/dashboard')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    const options = confirmMock.mock.calls[0][0]
    expect(options.title).toBe('AI 任务正在进行中')
    expect(options.content).toBe('离开当前页面将终止本次 AI 回答，确定要离开吗？')
    expect(options.okText).toBe('停止并离开')
    expect(options.okType).toBe('danger')
    expect(options.cancelText).toBe('继续等待')

    options.onOk()
    await navigation

    expect(cancelActiveAiTaskMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('Dashboard')
  })

  it('guard_whenAiTaskRunningAndUserCancels_shouldAbortNavigation', async () => {
    loginAs()
    await router.push('/profile')
    hasActiveAiTaskMock.mockReturnValue(true)

    const navigation = router.push('/dashboard')
    await flushPromises()

    const options = confirmMock.mock.calls[0][0]
    options.onCancel()
    await navigation

    expect(cancelActiveAiTaskMock).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/profile')
  })

  it('guard_whenConfirmAlreadyOpen_shouldSuspendFollowUpNavigation', async () => {
    loginAs('ADMIN')
    await router.push('/dashboard')
    hasActiveAiTaskMock.mockReturnValue(true)

    const firstNav = router.push('/profile')
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledTimes(1)

    const followUpNav = router.push('/admin/audit-log')
    await followUpNav

    // 弹窗打开期间的后续导航直接中止，停留在原页面
    expect(router.currentRoute.value.path).toBe('/dashboard')

    confirmMock.mock.calls[0][0].onOk()
    await firstNav

    // 终止动作（取消 AI 任务）照常执行；被后续导航取代的第一次导航按 vue-router
    // 语义判为 cancelled，页面停留在原路由
    expect(cancelActiveAiTaskMock).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.path).toBe('/dashboard')
  })

  it('guard_whenSecondTaskCheckAfterCancel_shouldConfirmAgain', async () => {
    loginAs()
    await router.push('/dashboard')
    hasActiveAiTaskMock.mockReturnValue(true)

    const firstNav = router.push('/profile')
    await flushPromises()
    confirmMock.mock.calls[0][0].onCancel()
    await firstNav

    const secondNav = router.push('/profile')
    await flushPromises()

    // 第一次取消后到二次导航之间无拦截标志残留，应再次弹确认
    expect(confirmMock).toHaveBeenCalledTimes(2)
    confirmMock.mock.calls[1][0].onOk()
    await secondNav
    expect(router.currentRoute.value.path).toBe('/profile')
  })

  it('guard_whenAnonymousOnRegisterPageHitsProtectedPage_shouldLandOnLoginInsteadOfStaying', async () => {
    // 从非 Login 页出发：若重定向目标解析回“当前页”，最终会停留在 Register
    await router.push('/register')

    await router.push('/dashboard')

    expect(router.currentRoute.value.name).toBe('Login')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/dashboard')
  })

  it('guard_whenNonAdminOnProfilePageHitsAdminPage_shouldLandOnDashboardInsteadOfStaying', async () => {
    // 从非 guest 页出发：若重定向目标解析回“当前页”，最终会停留在 Profile
    loginAs('DEVELOPER')
    await router.push('/profile')

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('Dashboard')
    expect(router.currentRoute.value.path).toBe('/dashboard')
  })

  it('guard_whenLoggedInWithoutUserRecordHitsAdminPage_shouldRedirectToDashboardWithoutCrash', async () => {
    // 只有 token、无 user 记录：缺少可选链时守卫读取 null.role 会抛错
    useAuthStore().setAuth('tok-guard', null)

    await router.push('/admin/users').catch(() => {})

    expect(router.currentRoute.value.name).toBe('Dashboard')
  })

  it('guard_whenRouterFreshlyLoaded_shouldAllowFirstNavigationAndConfirmWhenTaskStarts', async () => {
    vi.resetModules()
    const { default: freshRouter } = await import('./index')
    loginAs()

    // 全新模块 leaveConfirmOpen 初始为 false：无 AI 任务时首次导航直接放行
    await freshRouter.push('/dashboard')
    expect(freshRouter.currentRoute.value.name).toBe('Dashboard')

    hasActiveAiTaskMock.mockReturnValue(true)
    const navigation = freshRouter.push('/profile')
    await flushPromises()

    // 初始值若被改写为 true，首个带 AI 任务的导航会被直接挂起、弹窗一次都不会出现
    expect(confirmMock).toHaveBeenCalledTimes(1)
    const options = confirmMock.mock.calls[0][0]
    expect(options.content).toBe('离开当前页面将终止本次 AI 回答，确定要离开吗？')
    options.onCancel()
    await navigation
    expect(freshRouter.currentRoute.value.name).toBe('Dashboard')
  })
})
