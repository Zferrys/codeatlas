import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import Antd, { message } from 'ant-design-vue'

vi.mock('../api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  },
  handleUnauthorized: vi.fn()
}))

import api from '../api'
import Login from './Login.vue'
import zhCN from '../locales/zh-CN.js'
import enUS from '../locales/en-US.js'

const DummyRouteComponent = { template: '<div class="dummy-route" />' }

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: Login },
      { path: '/register', component: DummyRouteComponent },
      { path: '/dashboard', component: DummyRouteComponent },
      { path: '/projects', component: DummyRouteComponent }
    ]
  })
}

function createTestI18n() {
  return createI18n({
    legacy: false,
    locale: 'zh-CN',
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS }
  })
}

async function mountLogin(path = '/login') {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(Login, {
    global: { plugins: [router, createPinia(), createTestI18n(), Antd] }
  })
  await flushPromises()
  return { wrapper, router }
}

const LOGIN_SUCCESS_RESPONSE = {
  data: {
    data: { token: 'jwt-token', userId: 7, username: 'zferrys', role: 'DEVELOPER' }
  }
}

async function fillAndSubmit(wrapper, username, password) {
  await wrapper.find('input[placeholder="用户名"]').setValue(username)
  await wrapper.find('input[placeholder="密码"]').setValue(password)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  await flushPromises()
}

describe('Login.vue', () => {
  let messageSuccess
  let messageInfo

  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn().mockImplementation(query => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn()
    })))
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    messageSuccess = vi.spyOn(message, 'success').mockImplementation(() => {})
    messageInfo = vi.spyOn(message, 'info').mockImplementation(() => {})
    vi.mocked(api.post).mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('Login_whenMounted_shouldRenderLocalizedForm', async () => {
    const { wrapper } = await mountLogin()

    expect(wrapper.find('.form-title').text()).toBe('欢迎回来')
    expect(wrapper.find('.form-subtitle').text()).toBe('登录您的 CodeAtlas 账号')
    expect(wrapper.find('input[placeholder="用户名"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="密码"]').exists()).toBe(true)
    expect(wrapper.find('.submit-btn').text()).toContain('登')
    expect(wrapper.find('.switch-auth a').attributes('href')).toBe('/register')
    expect(wrapper.find('.forgot-link').text()).toBe('忘记密码？')
  })

  it('handleLogin_whenCredentialsValid_shouldCallApiAndNavigateToDashboard', async () => {
    api.post.mockResolvedValue(LOGIN_SUCCESS_RESPONSE)
    const { wrapper, router } = await mountLogin()
    const pushSpy = vi.spyOn(router, 'push')

    await fillAndSubmit(wrapper, 'zferrys', 'secret-123')

    expect(api.post).toHaveBeenCalledWith('/auth/login', {
      username: 'zferrys',
      password: 'secret-123'
    })
    expect(messageSuccess).toHaveBeenCalledWith('登录成功')
    expect(pushSpy).toHaveBeenCalledWith('/dashboard')
    expect(wrapper.findComponent({ name: 'AButton' }).props('loading')).toBe(false)
  })

  it('handleLogin_whenRedirectQueryPresent_shouldNavigateToRedirectTarget', async () => {
    api.post.mockResolvedValue(LOGIN_SUCCESS_RESPONSE)
    const { wrapper, router } = await mountLogin('/login?redirect=%2Fprojects')
    const pushSpy = vi.spyOn(router, 'push')

    await fillAndSubmit(wrapper, 'zferrys', 'secret-123')

    expect(pushSpy).toHaveBeenCalledWith('/projects')
  })

  it('handleLogin_whenRequestPending_shouldToggleSubmitButtonLoading', async () => {
    let resolveLogin
    api.post.mockImplementation(() => new Promise(resolve => { resolveLogin = resolve }))
    const { wrapper, router } = await mountLogin()
    const pushSpy = vi.spyOn(router, 'push')
    const submitButton = wrapper.findComponent({ name: 'AButton' })

    expect(submitButton.props('loading')).toBe(false)

    await wrapper.find('input[placeholder="用户名"]').setValue('zferrys')
    await wrapper.find('input[placeholder="密码"]').setValue('secret-123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(submitButton.props('loading')).toBe(true)

    resolveLogin(LOGIN_SUCCESS_RESPONSE)
    await flushPromises()
    await flushPromises()

    expect(submitButton.props('loading')).toBe(false)
    expect(pushSpy).toHaveBeenCalledWith('/dashboard')
  })

  it('handleLogin_whenLoginFails_shouldNotShowSuccessNorNavigate', async () => {
    api.post.mockRejectedValue(new Error('invalid credentials'))
    const { wrapper, router } = await mountLogin()
    const pushSpy = vi.spyOn(router, 'push')

    await fillAndSubmit(wrapper, 'zferrys', 'wrong-pass')

    expect(messageSuccess).not.toHaveBeenCalled()
    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.findComponent({ name: 'AButton' }).props('loading')).toBe(false)
  })

  it('onForgotPassword_whenLinkClicked_shouldShowContactAdminInfo', async () => {
    const { wrapper } = await mountLogin()

    await wrapper.find('.forgot-link').trigger('click')

    expect(messageInfo).toHaveBeenCalledWith('请联系管理员重置密码')
  })

  it('rememberMe_whenCheckboxToggled_shouldUpdateModelBinding', async () => {
    const { wrapper } = await mountLogin()

    expect(wrapper.vm.rememberMe).toBe(false)

    await wrapper.find('input[type="checkbox"]').setValue(true)

    expect(wrapper.vm.rememberMe).toBe(true)
  })
})
