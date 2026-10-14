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
import Register from './Register.vue'
import zhCN from '../locales/zh-CN.js'
import enUS from '../locales/en-US.js'

const DummyRouteComponent = { template: '<div class="dummy-route" />' }

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: DummyRouteComponent },
      { path: '/register', component: Register },
      { path: '/dashboard', component: DummyRouteComponent }
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

async function mountRegister() {
  const router = createTestRouter()
  await router.push('/register')
  await router.isReady()
  const wrapper = mount(Register, {
    global: { plugins: [router, createPinia(), createTestI18n(), Antd] }
  })
  await flushPromises()
  return { wrapper, router }
}

const REGISTER_SUCCESS_RESPONSE = {
  data: {
    data: { token: 'jwt-token', userId: 9, username: 'zferrys', role: 'DEVELOPER' }
  }
}

async function fillAndSubmit(wrapper, { username, email, password, confirmPassword }) {
  await wrapper.find('input[placeholder="用户名（3-50 位字符）"]').setValue(username)
  await wrapper.find('input[placeholder="邮箱（选填）"]').setValue(email)
  await wrapper.find('input[placeholder="密码（至少 6 位）"]').setValue(password)
  await wrapper.find('input[placeholder="确认密码"]').setValue(confirmPassword)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  await flushPromises()
}

describe('Register.vue', () => {
  let messageSuccess

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
    vi.mocked(api.post).mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('Register_whenMounted_shouldRenderLocalizedForm', async () => {
    const { wrapper } = await mountRegister()

    expect(wrapper.find('.form-title').text()).toBe('创建账号')
    expect(wrapper.find('.form-subtitle').text()).toBe('加入 CodeAtlas，探索代码架构之美')
    expect(wrapper.find('input[placeholder="用户名（3-50 位字符）"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="邮箱（选填）"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="密码（至少 6 位）"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="确认密码"]').exists()).toBe(true)
    expect(wrapper.find('.switch-auth a').attributes('href')).toBe('/login')
  })

  it('handleRegister_whenEmailProvided_shouldRegisterAndNavigateToDashboard', async () => {
    api.post.mockResolvedValue(REGISTER_SUCCESS_RESPONSE)
    const { wrapper, router } = await mountRegister()
    const pushSpy = vi.spyOn(router, 'push')

    await fillAndSubmit(wrapper, {
      username: 'zferrys',
      email: 'zferrys@example.com',
      password: 'secret-123',
      confirmPassword: 'secret-123'
    })

    expect(api.post).toHaveBeenCalledWith('/auth/register', {
      username: 'zferrys',
      password: 'secret-123',
      email: 'zferrys@example.com'
    })
    expect(messageSuccess).toHaveBeenCalledWith('注册成功')
    expect(pushSpy).toHaveBeenCalledWith('/dashboard')
    expect(wrapper.findComponent({ name: 'AButton' }).props('loading')).toBe(false)
  })

  it('handleRegister_whenEmailLeftBlank_shouldSendNullEmail', async () => {
    api.post.mockResolvedValue(REGISTER_SUCCESS_RESPONSE)
    const { wrapper } = await mountRegister()

    await fillAndSubmit(wrapper, {
      username: 'zferrys',
      email: '',
      password: 'secret-123',
      confirmPassword: 'secret-123'
    })

    expect(api.post).toHaveBeenCalledWith('/auth/register', {
      username: 'zferrys',
      password: 'secret-123',
      email: null
    })
  })

  it('handleRegister_whenRequestPending_shouldToggleSubmitButtonLoading', async () => {
    let resolveRegister
    api.post.mockImplementation(() => new Promise(resolve => { resolveRegister = resolve }))
    const { wrapper } = await mountRegister()
    const submitButton = wrapper.findComponent({ name: 'AButton' })

    expect(submitButton.props('loading')).toBe(false)

    await fillAndSubmit(wrapper, {
      username: 'zferrys',
      email: '',
      password: 'secret-123',
      confirmPassword: 'secret-123'
    })

    expect(submitButton.props('loading')).toBe(true)

    resolveRegister(REGISTER_SUCCESS_RESPONSE)
    await flushPromises()
    await flushPromises()

    expect(submitButton.props('loading')).toBe(false)
  })

  it('handleRegister_whenRegistrationFails_shouldNotShowSuccessNorNavigate', async () => {
    api.post.mockRejectedValue(new Error('username already taken'))
    const { wrapper, router } = await mountRegister()
    const pushSpy = vi.spyOn(router, 'push')

    await fillAndSubmit(wrapper, {
      username: 'existing-user',
      email: '',
      password: 'secret-123',
      confirmPassword: 'secret-123'
    })

    expect(messageSuccess).not.toHaveBeenCalled()
    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.findComponent({ name: 'AButton' }).props('loading')).toBe(false)
  })

  it('validateConfirmPassword_whenValueDiffersFromPassword_shouldRejectWithMismatchMessage', async () => {
    const { wrapper } = await mountRegister()
    const formRules = wrapper.findComponent({ name: 'AForm' }).props('rules')
    const confirmRule = formRules.confirmPassword.find(rule => typeof rule.validator === 'function')

    wrapper.vm.form.password = 'secret-123'

    await expect(confirmRule.validator(null, 'other-456')).rejects.toBe('两次输入的密码不一致')
  })

  it('validateConfirmPassword_whenValueMatchesPassword_shouldResolve', async () => {
    const { wrapper } = await mountRegister()
    const formRules = wrapper.findComponent({ name: 'AForm' }).props('rules')
    const confirmRule = formRules.confirmPassword.find(rule => typeof rule.validator === 'function')

    wrapper.vm.form.password = 'secret-123'

    await expect(confirmRule.validator(null, 'secret-123')).resolves.toBeUndefined()
  })
})
