import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import zhCN from '../locales/zh-CN'
import enUS from '../locales/en-US'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }))

vi.mock('../api', () => ({ default: { get: getMock } }))

const { iconNames } = vi.hoisted(() => ({
  iconNames: [
    'DashboardOutlined', 'AppstoreOutlined', 'AimOutlined', 'ReadOutlined',
    'SafetyOutlined', 'WarningOutlined', 'BulbOutlined', 'SettingOutlined',
    'MenuFoldOutlined', 'MenuUnfoldOutlined', 'BellOutlined', 'SearchOutlined',
    'UserOutlined', 'LogoutOutlined', 'GithubOutlined', 'ProjectOutlined',
    'FileTextOutlined', 'TeamOutlined', 'AuditOutlined', 'RobotOutlined'
  ]
}))
vi.mock('@ant-design/icons-vue', () => Object.fromEntries(
  iconNames.map(name => [name, { name, emits: ['click'], render: () => null }])
))

import DefaultLayout from './DefaultLayout.vue'
import { useAuthStore } from '../stores/auth'

const routeStub = { render: () => null }

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'Login', component: routeStub },
      { path: '/dashboard', name: 'Dashboard', component: routeStub },
      { path: '/profile', name: 'Profile', component: routeStub },
      { path: '/admin', name: 'AdminRoot', component: routeStub },
      { path: '/admin/dashboard', name: 'AdminDashboard', component: routeStub },
      { path: '/admin/users', name: 'AdminUsers', component: routeStub },
      { path: '/admin/audit-log', name: 'AdminAuditLog', component: routeStub },
      { path: '/admin/ai-models', name: 'AdminAiModels', component: routeStub },
      { path: '/admin/:tab', name: 'AdminFallback', component: routeStub },
      { path: '/project', name: 'ProjectRoot', component: routeStub },
      { path: '/project/:id', name: 'ProjectDetail', component: routeStub },
      { path: '/project/:id/overview', name: 'ProjectOverview', component: routeStub },
      { path: '/project/:id/map', name: 'ProjectMap', component: routeStub },
      { path: '/project/:id/story', name: 'ProjectStory', component: routeStub },
      { path: '/project/:id/rules', name: 'ProjectRules', component: routeStub },
      { path: '/project/:id/unknown', name: 'ProjectUnknown', component: routeStub },
      { path: '/plain', component: routeStub }
    ]
  })
}

/** antd 组件全部桩掉，暴露模板需要的 props/事件用于交互断言 */
function buildStubs(focusSpy) {
  return {
    'a-layout': { name: 'ALayout', template: '<div class="a-layout"><slot /></div>' },
    'a-layout-sider': {
      name: 'ALayoutSider',
      props: ['collapsed', 'trigger', 'width', 'breakpoint', 'collapsible'],
      emits: ['update:collapsed', 'breakpoint'],
      template: '<div class="a-layout-sider"><slot /></div>'
    },
    'a-layout-header': { name: 'ALayoutHeader', template: '<div class="a-layout-header"><slot /></div>' },
    'a-layout-content': { name: 'ALayoutContent', template: '<div class="a-layout-content"><slot /></div>' },
    'a-layout-footer': { name: 'ALayoutFooter', template: '<div class="a-layout-footer"><slot /></div>' },
    'a-menu': {
      name: 'AMenu',
      props: ['selectedKeys', 'theme', 'mode'],
      emits: ['click', 'update:selectedKeys'],
      template: '<div class="a-menu"><slot /></div>'
    },
    // 渲染 icon 插槽（真实 antd 会渲染），否则模板里的 12 个图标插槽函数永远不执行
    'a-menu-item': { name: 'AMenuItem', props: ['key', 'danger'], template: '<div class="a-menu-item"><slot name="icon" /><slot /></div>' },
    'a-menu-item-group': {
      name: 'AMenuItemGroup',
      props: ['title'],
      template: '<div class="a-menu-item-group" :data-title="title"><slot /></div>'
    },
    'a-menu-divider': { name: 'AMenuDivider', template: '<div class="a-menu-divider" />' },
    'a-breadcrumb': { name: 'ABreadcrumb', template: '<div class="a-breadcrumb"><slot /></div>' },
    'a-breadcrumb-item': { name: 'ABreadcrumbItem', template: '<div class="a-breadcrumb-item"><slot /></div>' },
    'a-tooltip': { name: 'ATooltip', props: ['title'], template: '<div class="a-tooltip"><slot /></div>' },
    'a-dropdown': { name: 'ADropdown', props: ['trigger'], template: '<div class="a-dropdown"><slot /><slot name="overlay" /></div>' },
    'a-badge': { name: 'ABadge', props: ['count', 'dot'], template: '<div class="a-badge"><slot /></div>' },
    'a-button': {
      name: 'AButton',
      props: ['type'],
      emits: ['click'],
      template: '<button class="a-button" @click="$emit(\'click\')"><slot /></button>'
    },
    'a-empty': {
      name: 'AEmpty',
      props: ['description', 'imageStyle'],
      template: '<div class="a-empty">{{ description }}</div>'
    },
    'a-avatar': { name: 'AAvatar', props: ['size'], template: '<div class="a-avatar"><slot /></div>' },
    'a-modal': {
      name: 'AModal',
      props: ['open', 'footer', 'closable', 'width', 'wrapClassName'],
      emits: ['update:open', 'cancel'],
      template: '<div class="a-modal"><slot /></div>'
    },
    'a-input': {
      name: 'AInput',
      props: ['value', 'placeholder', 'size', 'allowClear'],
      emits: ['update:value', 'input', 'keydown'],
      methods: { focus: focusSpy },
      template: '<div class="a-input"><slot name="prefix" /><slot name="suffix" /><slot /></div>'
    },
    'a-tag': { name: 'ATag', props: ['color', 'size'], template: '<div class="a-tag"><slot /></div>' }
  }
}

async function mountLayout({ path = '/dashboard', user = { id: 1, username: 'alice', role: 'DEVELOPER' }, theme = null } = {}) {
  if (theme) localStorage.setItem('codeatlas_theme', theme)
  const router = createTestRouter()
  await router.push(path)
  const pinia = createPinia()
  setActivePinia(pinia)
  const authStore = useAuthStore()
  if (user) authStore.setAuth('tok-layout', user)
  const i18n = createI18n({
    legacy: false,
    locale: 'zh-CN',
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS }
  })
  const focusSpy = vi.fn()
  const wrapper = mount(DefaultLayout, {
    global: {
      plugins: [router, pinia, i18n],
      stubs: buildStubs(focusSpy)
    },
    slots: { default: '<div class="layout-content-slot">内容区</div>' }
  })
  return { wrapper, router, authStore, focusSpy }
}

const siderMenu = wrapper => wrapper.findAllComponents({ name: 'AMenu' })[0]
const themeTooltip = wrapper => wrapper.findAllComponents({ name: 'ATooltip' })[1]
const searchModal = wrapper => wrapper.findComponent({ name: 'AModal' })
const searchInput = wrapper => wrapper.findComponent({ name: 'AInput' })
const breadcrumbLabels = wrapper => wrapper.findAll('.a-breadcrumb-item').map(node => node.text())

/** 走一遍真实的防抖链路：输入 → 300ms 后 doSearch 完成 */
async function runSearch(wrapper, query, response) {
  getMock.mockResolvedValue(response)
  searchInput(wrapper).vm.$emit('update:value', query)
  searchInput(wrapper).vm.$emit('input')
  // 必须用真实计时器等待防抖：若在假计时器窗口内推进 300ms，
  // 结果元素会带着「假时钟」时间戳创建 Vue 事件 invoker，
  // 之后 trigger('click') 时 test-utils 写入的 _vts（真实时间+1）
  // 小于 invoker.attached（假时间+300），Vue 会静默丢弃这次点击
  await new Promise(resolve => setTimeout(resolve, 320))
  await flushPromises()
}

// 组件在 document 上挂了全局 keydown 监听；不自动卸载会让之前用例的 wrapper
// 残留监听器继续响应后续用例的键盘事件（焦点 spy 计数被污染）
enableAutoUnmount(afterEach)

describe('DefaultLayout.vue', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
    document.documentElement.removeAttribute('data-theme')
  })

  it('DefaultLayout_whenRendered_shouldShowLogoFooterUserAndSlotContent', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    expect(wrapper.find('.app-layout').exists()).toBe(true)
    expect(wrapper.find('.logo-text').text()).toBe('CodeAtlas')
    expect(wrapper.find('.version-tag').text()).toBe('v0.1.0')
    expect(wrapper.find('.user-name').text()).toBe('alice')
    expect(wrapper.find('.a-avatar').text()).toBe('A')
    expect(wrapper.find('.layout-content-slot').text()).toBe('内容区')

    const footer = wrapper.find('.app-footer')
    expect(footer.text()).toContain(zhCN.brand.footer)
    expect(footer.find('a').attributes('href')).toBe('https://github.com/zferrys/codeatlas')

    // 工作台菜单项 + 当前选中态
    expect(wrapper.find('.a-menu-item').text()).toBe(zhCN.nav.workspace)
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['dashboard'])

    // 非管理员 & 无项目：两个分组都不渲染
    expect(wrapper.findAll('.a-menu-item-group')).toHaveLength(0)

    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace])
  })

  it('DefaultLayout_whenUserIsAdmin_shouldRenderAdminMenuGroupAndSelectAdminKey', async () => {
    const { wrapper } = await mountLayout({ path: '/admin/users', user: { id: 2, username: 'root', role: 'ADMIN' } })

    const groups = wrapper.findAll('.a-menu-item-group')
    expect(groups).toHaveLength(1)
    expect(groups[0].attributes('data-title')).toBe(zhCN.nav.admin)
    expect(groups[0].findAll('.a-menu-item').map(item => item.text())).toEqual([
      '系统概览', zhCN.nav.userManagement, zhCN.nav.auditLog, zhCN.nav.aiModels
    ])

    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['admin-users'])
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace, zhCN.nav.admin, zhCN.nav.userManagement])
    // 面包屑首项为可点击链接
    expect(wrapper.find('.a-breadcrumb-item a').text()).toBe(zhCN.nav.workspace)
  })

  it('DefaultLayout_whenInsideProject_shouldRenderProjectMenuGroupAndSelectTab', async () => {
    const { wrapper } = await mountLayout({ path: '/project/42/map' })

    const groups = wrapper.findAll('.a-menu-item-group')
    expect(groups).toHaveLength(1)
    expect(groups[0].attributes('data-title')).toBe(zhCN.nav.currentProject)
    expect(groups[0].findAll('.a-menu-item').map(item => item.text())).toEqual([
      zhCN.nav.overview, zhCN.nav.codeMap, zhCN.nav.archStory,
      zhCN.nav.rules, zhCN.nav.violations, zhCN.nav.insights, zhCN.nav.settings
    ])

    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['map'])
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace, 'Project #42', zhCN.nav.codeMap])
  })

  it('DefaultLayout_whenBreadcrumbPartsUnknown_shouldFallbackToRawSegmentsAndKeepSelection', async () => {
    const { wrapper, router } = await mountLayout({ path: '/dashboard' })

    await router.push('/admin/xyz')
    await flushPromises()
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace, zhCN.nav.admin, 'xyz'])
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['adminfallback'])

    await router.push('/project/7')
    await flushPromises()
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace, 'Project #7'])
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['detail'])

    await router.push('/project/7/unknown')
    await flushPromises()
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace, 'Project #7', 'unknown'])
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['unknown'])

    // 无名路由：watch 早退，选中态保持上一次
    await router.push('/plain')
    await flushPromises()
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace])
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['unknown'])
  })

  it('DefaultLayout_whenBreadcrumbParentOnly_shouldRenderPartialTrail', async () => {
    const { wrapper, router } = await mountLayout({ path: '/dashboard' })

    // /admin 无二级段：面包屑止步于「管理后台」
    await router.push('/admin')
    await flushPromises()
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace, zhCN.nav.admin])
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['adminroot'])

    // /project 无 id 段：面包屑只剩工作台
    await router.push('/project')
    await flushPromises()
    expect(breadcrumbLabels(wrapper)).toEqual([zhCN.nav.workspace])
    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['root'])
  })

  it('DefaultLayout_whenMenuClicked_shouldNavigateWith500msDebounce', async () => {
    const { wrapper, router } = await mountLayout({
      path: '/project/42/map',
      user: { id: 3, username: 'root', role: 'ADMIN' }
    })
    const pushSpy = vi.spyOn(router, 'push')
    const menu = siderMenu(wrapper)
    let now = 100000
    vi.spyOn(Date, 'now').mockImplementation(() => now)

    menu.vm.$emit('click', { key: 'story' })
    expect(pushSpy).toHaveBeenLastCalledWith('/project/42/story')
    await flushPromises()

    // 防抖窗口内重复点击被忽略
    const callsBefore = pushSpy.mock.calls.length
    now += 100
    menu.vm.$emit('click', { key: 'rules' })
    expect(pushSpy.mock.calls.length).toBe(callsBefore)

    now += 1000
    menu.vm.$emit('click', { key: 'dashboard' })
    expect(pushSpy).toHaveBeenLastCalledWith('/dashboard')
    await flushPromises()

    now += 1000
    menu.vm.$emit('click', { key: 'admin-dashboard' })
    expect(pushSpy).toHaveBeenLastCalledWith('/admin/dashboard')

    now += 1000
    menu.vm.$emit('click', { key: 'admin-users' })
    expect(pushSpy).toHaveBeenLastCalledWith('/admin/users')

    now += 1000
    menu.vm.$emit('click', { key: 'admin-audit-log' })
    expect(pushSpy).toHaveBeenLastCalledWith('/admin/audit-log')

    now += 1000
    menu.vm.$emit('click', { key: 'admin-ai-models' })
    expect(pushSpy).toHaveBeenLastCalledWith('/admin/ai-models')
    await flushPromises()

    // 不在项目上下文中点击项目 tab：不产生导航
    now += 1000
    const callsAfterAdmin = pushSpy.mock.calls.length
    menu.vm.$emit('click', { key: 'overview' })
    expect(pushSpy.mock.calls.length).toBe(callsAfterAdmin)
  })

  it('DefaultLayout_whenCollapseTriggered_shouldToggleSiderAndVersionTag', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })
    const sider = wrapper.findComponent({ name: 'ALayoutSider' })

    // v-show="!collapsed" 挂在 .sider-footer 上。不查 isVisible()：jsdom 29 的
    // getComputedStyle 首次调用即按元素冻结快照（_styleCache 永不失效），
    // 动态改样式后读到的是旧值；直接断言 v-show 写入的行内 display
    const siderFooterStyle = () => wrapper.find('.sider-footer').attributes('style') ?? ''

    expect(wrapper.findComponent({ name: 'MenuFoldOutlined' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'MenuUnfoldOutlined' }).exists()).toBe(false)
    expect(sider.props('collapsed')).toBe(false)
    expect(siderFooterStyle()).not.toContain('display: none')

    wrapper.findComponent({ name: 'MenuFoldOutlined' }).vm.$emit('click')
    await nextTick()
    expect(wrapper.findComponent({ name: 'MenuFoldOutlined' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'MenuUnfoldOutlined' }).exists()).toBe(true)
    expect(sider.props('collapsed')).toBe(true)
    expect(siderFooterStyle()).toContain('display: none')

    wrapper.findComponent({ name: 'MenuUnfoldOutlined' }).vm.$emit('click')
    await nextTick()
    expect(sider.props('collapsed')).toBe(false)
  })

  it('DefaultLayout_whenSiderEmitsUpdateCollapsed_shouldSyncCollapsedState', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })
    const sider = wrapper.findComponent({ name: 'ALayoutSider' })

    // a-layout-sider 的 v-model:collapsed 反向同步
    sider.vm.$emit('update:collapsed', true)
    await nextTick()
    expect(sider.props('collapsed')).toBe(true)
    expect(wrapper.findComponent({ name: 'MenuUnfoldOutlined' }).exists()).toBe(true)

    sider.vm.$emit('update:collapsed', false)
    await nextTick()
    expect(sider.props('collapsed')).toBe(false)
    expect(wrapper.findComponent({ name: 'MenuFoldOutlined' }).exists()).toBe(true)
  })

  it('DefaultLayout_whenMenuEmitsUpdateSelectedKeys_shouldSyncSelection', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    siderMenu(wrapper).vm.$emit('update:selectedKeys', ['admin-users'])
    await nextTick()

    expect(siderMenu(wrapper).props('selectedKeys')).toEqual(['admin-users'])
  })

  it('DefaultLayout_whenSearchTriggerClicked_shouldOpenModalAndRespectModelUpdate', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    expect(searchModal(wrapper).props('open')).toBe(false)
    await wrapper.find('.search-trigger').trigger('click')
    expect(searchModal(wrapper).props('open')).toBe(true)

    // a-modal 的 v-model:open 反向同步
    searchModal(wrapper).vm.$emit('update:open', false)
    await nextTick()
    expect(searchModal(wrapper).props('open')).toBe(false)
  })

  it('DefaultLayout_whenNavigatingAdminTabs_shouldUpdateSelectedKeysByRouteName', async () => {
    const { wrapper, router } = await mountLayout({ path: '/dashboard' })

    const adminTabs = [
      ['/admin/dashboard', 'admin-dashboard'],
      ['/admin/audit-log', 'admin-audit-log'],
      ['/admin/users', 'admin-users'],
      ['/admin/ai-models', 'admin-ai-models']
    ]
    for (const [path, key] of adminTabs) {
      await router.push(path)
      await flushPromises()
      expect(siderMenu(wrapper).props('selectedKeys')).toEqual([key])
    }
  })

  it('DefaultLayout_whenBreakpointBroken_shouldCollapseAndRespectMobileUserName', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })
    const sider = wrapper.findComponent({ name: 'ALayoutSider' })

    // 桌面宽度初始态：侧栏展开、用户名可见
    expect(wrapper.find('.user-name').exists()).toBe(true)

    sider.vm.$emit('breakpoint', true)
    await nextTick()
    expect(sider.props('collapsed')).toBe(true)
    // 移动端点：v-if="!collapsed || !isMobile" 两个条件都为假 → 用户名隐藏
    expect(wrapper.find('.user-name').exists()).toBe(false)

    sider.vm.$emit('breakpoint', false)
    await nextTick()
    // 回到桌面宽度：isMobile 复位为假，侧栏保持收起但用户名恢复显示
    expect(wrapper.find('.user-name').exists()).toBe(true)
  })

  it('DefaultLayout_whenThemeToggled_shouldPersistAndFlipTooltip', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    expect(themeTooltip(wrapper).props('title')).toBe(zhCN.nav.switchDark)

    themeTooltip(wrapper).findComponent({ name: 'AButton' }).vm.$emit('click')
    await nextTick()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem('codeatlas_theme')).toBe('dark')
    expect(themeTooltip(wrapper).props('title')).toBe(zhCN.nav.switchLight)

    themeTooltip(wrapper).findComponent({ name: 'AButton' }).vm.$emit('click')
    await nextTick()
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem('codeatlas_theme')).toBe('light')
    expect(themeTooltip(wrapper).props('title')).toBe(zhCN.nav.switchDark)
  })

  it('DefaultLayout_whenSavedThemeIsDark_shouldBootDark', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard', theme: 'dark' })

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(themeTooltip(wrapper).props('title')).toBe(zhCN.nav.switchLight)
  })

  it('DefaultLayout_whenSavedThemeIsLight_shouldBootLight', async () => {
    await mountLayout({ path: '/dashboard', theme: 'light' })

    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
  })

  it('DefaultLayout_whenUserMenuClicked_shouldNavigateToProfileOrLogout', async () => {
    const { wrapper, router, authStore } = await mountLayout({ path: '/dashboard' })
    const pushSpy = vi.spyOn(router, 'push')
    const logoutSpy = vi.spyOn(authStore, 'logout')

    const dropdowns = wrapper.findAllComponents({ name: 'ADropdown' })
    expect(dropdowns).toHaveLength(2)
    const userMenu = dropdowns[1].findComponent({ name: 'AMenu' })

    // 未知菜单键：不导航、不登出
    userMenu.vm.$emit('click', { key: 'help' })
    expect(pushSpy).not.toHaveBeenCalled()
    expect(logoutSpy).not.toHaveBeenCalled()

    userMenu.vm.$emit('click', { key: 'profile' })
    expect(pushSpy).toHaveBeenCalledWith('/profile')

    userMenu.vm.$emit('click', { key: 'logout' })
    expect(logoutSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy).toHaveBeenLastCalledWith('/login')
    expect(authStore.isLoggedIn).toBe(false)
    expect(localStorage.getItem('codeatlas_token')).toBeNull()
  })

  it('DefaultLayout_whenUserMissing_shouldFallbackInitialAndHideUserName', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard', user: null })

    expect(wrapper.find('.a-avatar').text()).toBe('U')
    expect(wrapper.find('.user-name').exists()).toBe(true)
    expect(wrapper.find('.user-name').text()).toBe('')
    expect(wrapper.findAll('.a-menu-item-group')).toHaveLength(0)
  })

  it('DefaultLayout_whenCtrlKPressed_shouldOpenSearchModalAndFocusInput', async () => {
    const { wrapper, focusSpy } = await mountLayout({ path: '/dashboard' })

    expect(searchModal(wrapper).props('open')).toBe(false)

    const event = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, cancelable: true })
    document.dispatchEvent(event)
    await flushPromises()

    expect(event.defaultPrevented).toBe(true)
    expect(searchModal(wrapper).props('open')).toBe(true)
    expect(focusSpy).toHaveBeenCalledTimes(1)
  })

  it('DefaultLayout_whenOtherKeysOrMetaKPressed_shouldOnlyReactToShortcut', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'x', ctrlKey: true }))
    await flushPromises()
    expect(searchModal(wrapper).props('open')).toBe(false)

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true }))
    await flushPromises()
    expect(searchModal(wrapper).props('open')).toBe(true)
  })

  it('DefaultLayout_whenSearchClosed_shouldResetQueryViaEscapeAndCancel', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()
    searchInput(wrapper).vm.$emit('update:value', 'order')
    await nextTick()
    expect(wrapper.find('.search-results').exists()).toBe(true)

    // Escape 关闭并清空
    searchInput(wrapper).vm.$emit('keydown', { key: 'Escape' })
    await nextTick()
    expect(searchModal(wrapper).props('open')).toBe(false)
    expect(wrapper.find('.search-results').exists()).toBe(false)

    // 遮罩取消关闭
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()
    expect(searchModal(wrapper).props('open')).toBe(true)
    searchModal(wrapper).vm.$emit('cancel')
    await nextTick()
    expect(searchModal(wrapper).props('open')).toBe(false)
  })

  it('DefaultLayout_whenTyping_shouldDebounceAndRenderGroupedResults', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()

    vi.useFakeTimers()
    let resolveSearch
    getMock.mockImplementation(() => new Promise(resolve => { resolveSearch = resolve }))
    searchInput(wrapper).vm.$emit('update:value', 'proj')
    searchInput(wrapper).vm.$emit('input')
    await vi.advanceTimersByTimeAsync(100)
    searchInput(wrapper).vm.$emit('update:value', 'proj2')
    searchInput(wrapper).vm.$emit('input')
    await vi.advanceTimersByTimeAsync(300)
    vi.useRealTimers()
    await flushPromises()

    // 两次输入只触发一次请求（前一个防抖定时器被清除）
    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock).toHaveBeenCalledWith('/search', { params: { q: 'proj2', type: 'all' } })
    // 请求挂起期间展示「搜索中」标记
    expect(wrapper.find('.a-tag').text()).toBe(zhCN.search.searching)

    resolveSearch({
      data: {
        data: {
          projects: [{ id: 1, name: 'proj-api', description: '后端服务' }],
          classes: [{ id: 9, simpleName: 'OrderService', fqn: 'com.demo.OrderService', layer: 'service' }]
        }
      }
    })
    await flushPromises()

    expect(wrapper.findAll('.search-group-title').map(node => node.text())).toEqual([
      zhCN.search.projectGroup, zhCN.search.classGroup
    ])
    const items = wrapper.findAll('.search-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.search-item-name').text()).toBe('proj-api')
    expect(items[0].find('.search-item-desc').text()).toBe('后端服务')
    expect(items[1].text()).toContain('OrderService')
    expect(items[1].text()).toContain('com.demo.OrderService')
    expect(items[1].find('.a-tag').text()).toBe('service')
    // 通知弹层里常驻一个「暂无通知」空态，须限定在搜索弹窗内查找
    expect(searchModal(wrapper).find('.a-empty').exists()).toBe(false)
  })

  it('DefaultLayout_whenSearchQueryBlank_shouldClearResultsWithoutRequest', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })

    vi.useFakeTimers()
    searchInput(wrapper).vm.$emit('update:value', '   ')
    searchInput(wrapper).vm.$emit('input')
    await vi.advanceTimersByTimeAsync(400)
    vi.useRealTimers()

    expect(getMock).not.toHaveBeenCalled()
    expect(wrapper.find('.search-results').exists()).toBe(false)
  })

  it('DefaultLayout_whenSearchFailsOrReturnsEmpty_shouldShowNoResults', async () => {
    const { wrapper } = await mountLayout({ path: '/dashboard' })
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()

    // 请求失败：结果清空且不再处于搜索中
    vi.useFakeTimers()
    getMock.mockRejectedValue(new Error('boom'))
    searchInput(wrapper).vm.$emit('update:value', 'failing')
    searchInput(wrapper).vm.$emit('input')
    await vi.advanceTimersByTimeAsync(300)
    vi.useRealTimers()
    await flushPromises()
    expect(wrapper.find('.a-tag').exists()).toBe(false)
    expect(wrapper.find('.search-item').exists()).toBe(false)

    // 后端 data 为空：展示空态（限定在搜索弹窗内，避开通知弹层的空态）
    await runSearch(wrapper, 'empty', { data: { data: null } })
    expect(searchModal(wrapper).find('.a-empty').text()).toBe(zhCN.search.noResults)
    expect(wrapper.find('.search-item').exists()).toBe(false)
  })

  it('DefaultLayout_whenResultClicked_shouldNavigateAndCloseSearch', async () => {
    const { wrapper, router } = await mountLayout({ path: '/dashboard' })
    const pushSpy = vi.spyOn(router, 'push')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()

    // goSearchResult 按 item.type 分流，后端返回的条目自带 type 字段
    await runSearch(wrapper, 'proj', {
      data: { data: { projects: [{ id: 1, type: 'project', name: 'proj-api', description: 'd' }], classes: [] } }
    })
    await wrapper.find('.search-item').trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/project/1/overview')
    expect(searchModal(wrapper).props('open')).toBe(false)
    expect(wrapper.find('.search-results').exists()).toBe(false)

    // 类结果（带 projectId）→ 代码地图
    await runSearch(wrapper, 'order', {
      data: { data: { projects: [], classes: [{ id: 9, type: 'class', simpleName: 'OrderService', fqn: 'com.demo.OrderService', projectId: 5 }] } }
    })
    await wrapper.find('.search-item').trigger('click')
    expect(pushSpy).toHaveBeenLastCalledWith('/project/5/map')

    // 类结果缺少 projectId → 不导航
    await runSearch(wrapper, 'orphan', {
      data: { data: { projects: [], classes: [{ id: 10, type: 'class', simpleName: 'Orphan', fqn: 'com.demo.Orphan' }] } }
    })
    const callsBefore = pushSpy.mock.calls.length
    await wrapper.find('.search-item').trigger('click')
    expect(pushSpy.mock.calls.length).toBe(callsBefore)
  })

  it('DefaultLayout_whenUnmounted_shouldRemoveGlobalKeydownListener', async () => {
    const addSpy = vi.spyOn(document, 'addEventListener')
    const removeSpy = vi.spyOn(document, 'removeEventListener')

    const { wrapper } = await mountLayout({ path: '/dashboard' })

    const addCall = addSpy.mock.calls.find(([event]) => event === 'keydown')
    expect(addCall).toBeTruthy()

    wrapper.unmount()

    expect(removeSpy.mock.calls.some(([event, listener]) => event === 'keydown' && listener === addCall[1])).toBe(true)
  })
})
