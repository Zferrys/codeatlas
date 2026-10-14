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
      '/admin/ai-models': 'AdminAiModels'
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

    expect(lazyRecords).toHaveLength(16)
    for (const record of lazyRecords) {
      const module = await record.component()
      expect(module.default).toBeTruthy()
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
    expect(options.okText).toBe('停止并离开')
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
})
