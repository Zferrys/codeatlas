import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import darkAlgorithm from 'ant-design-vue/es/theme/themes/dark'
import defaultAlgorithm from 'ant-design-vue/es/theme/themes/default'
import antEnUS from 'ant-design-vue/es/locale/en_US'
import antZhCN from 'ant-design-vue/es/locale/zh_CN'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'
import App from './App.vue'

vi.mock('./layouts/AuthLayout.vue', () => ({
  default: { name: 'AuthLayout', template: '<div class="stub-auth-layout"><slot /></div>' }
}))
vi.mock('./layouts/DefaultLayout.vue', () => ({
  default: { name: 'DefaultLayout', template: '<div class="stub-default-layout"><slot /></div>' }
}))
vi.mock('./components/common/ErrorBoundary.vue', () => ({
  default: { name: 'ErrorBoundary', template: '<div class="stub-error-boundary"><slot /></div>' }
}))

const EmptyView = { name: 'EmptyView', render: () => null }

const AConfigProviderStub = {
  name: 'AConfigProvider',
  props: ['theme', 'locale'],
  template: '<div class="config-provider-stub"><slot /></div>'
}

// 捕获 MutationObserver 实例，测试内手动触发回调，避免依赖 jsdom 的微任务时序
const observers = []
class MutationObserverStub {
  constructor(callback) {
    this.callback = callback
    this.observe = vi.fn()
    this.disconnect = vi.fn()
    observers.push(this)
  }
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'Login', component: EmptyView, meta: { guest: true } },
      { path: '/dashboard', name: 'Dashboard', component: EmptyView, meta: { requiresAuth: true } },
      { path: '/plain', name: 'Plain', component: EmptyView }
    ]
  })
}

async function mountApp({ path = '/dashboard', locale = 'zh-CN' } = {}) {
  const router = createTestRouter()
  await router.push(path)
  const i18n = createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS }
  })
  const wrapper = mount(App, {
    global: {
      plugins: [router, i18n],
      stubs: {
        'router-view': true,
        'a-config-provider': AConfigProviderStub
      }
    }
  })
  return { wrapper, router, i18n, config: wrapper.findComponent({ name: 'AConfigProvider' }) }
}

describe('App.vue', () => {
  beforeEach(() => {
    observers.length = 0
    vi.stubGlobal('MutationObserver', MutationObserverStub)
    document.documentElement.removeAttribute('data-theme')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.documentElement.removeAttribute('data-theme')
  })

  it('App_whenGuestRoute_shouldRenderAuthLayoutInsideErrorBoundary', async () => {
    const { wrapper } = await mountApp({ path: '/login' })

    expect(wrapper.find('.stub-error-boundary').exists()).toBe(true)
    expect(wrapper.find('.stub-auth-layout').exists()).toBe(true)
    expect(wrapper.find('.stub-default-layout').exists()).toBe(false)
  })

  it('App_whenProtectedRoute_shouldRenderDefaultLayout', async () => {
    const { wrapper } = await mountApp({ path: '/dashboard' })

    expect(wrapper.find('.stub-default-layout').exists()).toBe(true)
    expect(wrapper.find('.stub-auth-layout').exists()).toBe(false)
  })

  it('App_whenPlainRoute_shouldRenderBareRouterViewWithoutLayouts', async () => {
    const { wrapper } = await mountApp({ path: '/plain' })

    expect(wrapper.find('.stub-auth-layout').exists()).toBe(false)
    expect(wrapper.find('.stub-default-layout').exists()).toBe(false)
    expect(wrapper.find('.stub-error-boundary').exists()).toBe(true)
  })

  it('App_whenMounted_shouldObserveThemeAttributeOnDocumentElement', async () => {
    await mountApp({ path: '/dashboard' })

    expect(observers).toHaveLength(1)
    expect(observers[0].observe).toHaveBeenCalledTimes(1)
    expect(observers[0].observe).toHaveBeenCalledWith(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme']
    })
  })

  it('App_whenThemeAttributeChanges_shouldSwitchAntdAlgorithm', async () => {
    const { wrapper, config } = await mountApp({ path: '/dashboard' })

    expect(config.props('theme').algorithm).toBe(defaultAlgorithm)

    document.documentElement.setAttribute('data-theme', 'dark')
    observers[0].callback()
    await wrapper.vm.$nextTick()
    expect(config.props('theme').algorithm).toBe(darkAlgorithm)

    document.documentElement.setAttribute('data-theme', 'light')
    observers[0].callback()
    await wrapper.vm.$nextTick()
    expect(config.props('theme').algorithm).toBe(defaultAlgorithm)
  })

  it('App_whenBootedInDarkTheme_shouldSyncDarkOnMount', async () => {
    document.documentElement.setAttribute('data-theme', 'dark')

    const { config } = await mountApp({ path: '/dashboard' })

    expect(config.props('theme').algorithm).toBe(darkAlgorithm)
  })

  it('App_whenLocaleIsEnglish_shouldUseEnglishAntdLocale', async () => {
    const { wrapper, i18n, config } = await mountApp({ path: '/dashboard', locale: 'en-US' })

    expect(config.props('locale')).toBe(antEnUS)

    i18n.global.locale.value = 'zh-CN'
    await wrapper.vm.$nextTick()
    expect(config.props('locale')).toBe(antZhCN)
  })

  it('App_whenDefaultLocale_shouldUseChineseAntdLocale', async () => {
    const { config } = await mountApp({ path: '/dashboard' })

    expect(config.props('locale')).toBe(antZhCN)
  })

  it('App_whenUnmounted_shouldDisconnectThemeObserver', async () => {
    const { wrapper } = await mountApp({ path: '/dashboard' })

    wrapper.unmount()

    expect(observers[0].disconnect).toHaveBeenCalledTimes(1)
  })
})
