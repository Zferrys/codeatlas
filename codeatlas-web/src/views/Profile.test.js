import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
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
import Profile from './Profile.vue'
import zhCN from '../locales/zh-CN.js'
import enUS from '../locales/en-US.js'

function createTestI18n() {
  return createI18n({
    legacy: false,
    locale: 'zh-CN',
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS }
  })
}

let wrapper = null

async function mountProfile() {
  const i18n = createTestI18n()
  wrapper = mount(Profile, {
    global: { plugins: [i18n, Antd] }
  })
  return { wrapper, i18n }
}

function userResponse(user) {
  return { data: { data: user } }
}

function findButtonByText(wrapper, text) {
  return wrapper.findAll('button').find(node => node.text().includes(text))
}

// 弹窗内容经 Teleport 渲染到 body：直接对输入元素派发 input 事件驱动 v-model
async function setTeleportedInput(placeholder, value) {
  const element = document.body.querySelector(`input[placeholder="${placeholder}"]`)
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

describe('Profile.vue', () => {
  let messageInfo
  let messageSuccess
  let messageWarning
  let messageError

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
    messageInfo = vi.spyOn(message, 'info').mockImplementation(() => {})
    messageSuccess = vi.spyOn(message, 'success').mockImplementation(() => {})
    messageWarning = vi.spyOn(message, 'warning').mockImplementation(() => {})
    messageError = vi.spyOn(message, 'error').mockImplementation(() => {})
    vi.mocked(api.get).mockReset()
    vi.mocked(api.put).mockReset()
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.restoreAllMocks()
    document.documentElement.removeAttribute('data-theme')
  })

  it('onMounted_whenUserFetched_shouldRenderProfileDetails', async () => {
    api.get.mockResolvedValue(userResponse({
      id: 3,
      username: 'zferrys',
      email: 'zferrys@example.com',
      role: 'DEVELOPER',
      createdAt: '2026-01-15T08:30:00',
      status: 1
    }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/auth/me')
    expect(wrapper.find('.profile-username').text()).toBe('zferrys')
    expect(wrapper.find('.ant-avatar').text()).toBe('Z')
    expect(wrapper.find('.profile-role-tag').text()).toBe('开发者')
    const infoValues = wrapper.findAll('.info-value').map(node => node.text())
    expect(infoValues).toEqual(['zferrys@example.com', '#3', '2026-01-15 08:30'])
    expect(wrapper.findAll('.info-item')[3].text()).toContain('正常')
    expect(wrapper.find('.info-icon.status-active').exists()).toBe(true)
  })

  it('onMounted_whenUserInfoFieldsEmpty_shouldRenderFallbackPlaceholders', async () => {
    api.get.mockResolvedValue(userResponse({
      id: 0,
      username: '',
      email: '',
      role: '',
      createdAt: null,
      status: 0
    }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.find('.profile-username').text()).toBe('--')
    expect(wrapper.find('.ant-avatar').text()).toBe('U')
    expect(wrapper.vm.roleLabel).toBe('')
    expect(wrapper.vm.roleColor).toBe('default')
    expect(wrapper.findAll('.info-value').map(node => node.text()))
      .toEqual(['未设置', '#0', '--'])
    expect(wrapper.findAll('.info-item')[3].text()).toContain('禁用')
    expect(wrapper.find('.info-icon.status-inactive').exists()).toBe(true)
  })

  it('onMounted_whenFetchFails_shouldKeepPlaceholderProfile', async () => {
    api.get.mockRejectedValue(new Error('unauthorized'))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/auth/me')
    expect(wrapper.find('.profile-username').text()).toBe('--')
    expect(wrapper.findAll('.info-value').map(node => node.text()))
      .toEqual(['未设置', '#0', '--'])
  })

  it('roleLabel_whenRoleVariants_shouldTranslateKnownRolesAndFallbackToRaw', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u', role: 'ARCHITECT' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.vm.roleLabel).toBe('架构师')

    wrapper.vm.userInfo.role = 'ADMIN'
    expect(wrapper.vm.roleLabel).toBe('管理员')

    wrapper.vm.userInfo.role = 'VIEWER'
    expect(wrapper.vm.roleLabel).toBe('观察者')

    wrapper.vm.userInfo.role = 'GHOST'
    expect(wrapper.vm.roleLabel).toBe('GHOST')

    wrapper.vm.userInfo.role = ''
    expect(wrapper.vm.roleLabel).toBe('')
  })

  it('roleColor_whenRoleVariants_shouldReturnMappedOrDefaultColor', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u', role: 'ADMIN' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.vm.roleColor).toBe('red')

    wrapper.vm.userInfo.role = 'DEVELOPER'
    expect(wrapper.vm.roleColor).toBe('blue')

    wrapper.vm.userInfo.role = 'VIEWER'
    expect(wrapper.vm.roleColor).toBe('default')

    wrapper.vm.userInfo.role = 'GHOST'
    expect(wrapper.vm.roleColor).toBe('default')
  })

  it('userInitial_whenUsernameChanges_shouldUppercaseFirstCharOrFallBackToU', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'zferrys' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.vm.userInitial).toBe('Z')

    wrapper.vm.userInfo.username = 'alice'
    expect(wrapper.vm.userInitial).toBe('A')

    wrapper.vm.userInfo.username = ''
    expect(wrapper.vm.userInitial).toBe('U')
  })

  it('formatDate_whenDateProvided_shouldFormatWithZeroPadding', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.vm.formatDate('2026-03-05T09:07:00')).toBe('2026-03-05 09:07')
    expect(wrapper.vm.formatDate(null)).toBe('--')
    expect(wrapper.vm.formatDate('')).toBe('--')
  })

  it('onThemeToggle_whenSwitchChanged_shouldPersistThemeOnDocumentAndStorage', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()
    const themeSwitch = wrapper.findComponent({ name: 'ASwitch' })

    themeSwitch.vm.$emit('change', true)
    await nextTick()

    expect(wrapper.vm.isDark).toBe(true)
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem('codeatlas_theme')).toBe('dark')

    themeSwitch.vm.$emit('change', false)
    await nextTick()

    expect(wrapper.vm.isDark).toBe(false)
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem('codeatlas_theme')).toBe('light')
  })

  it('Profile_whenDocumentAlreadyInDarkTheme_shouldInitializeSwitchAsChecked', async () => {
    document.documentElement.setAttribute('data-theme', 'dark')
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.vm.isDark).toBe(true)
    expect(wrapper.findComponent({ name: 'ASwitch' }).props('checked')).toBe(true)
  })

  it('onLocaleChange_whenLanguageSelected_shouldUpdateI18nAndPersistLocale', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper, i18n } = await mountProfile()
    await flushPromises()

    wrapper.findComponent({ name: 'ASelect' }).vm.$emit('change', 'en-US')
    await nextTick()

    expect(wrapper.vm.locale).toBe('en-US')
    expect(i18n.global.locale.value).toBe('en-US')
    expect(localStorage.getItem('codeatlas_locale')).toBe('en-US')
  })

  it('Profile_whenLocaleStored_shouldInitializeSelectWithStoredLocale', async () => {
    localStorage.setItem('codeatlas_locale', 'en-US')
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    expect(wrapper.vm.locale).toBe('en-US')
    expect(wrapper.findComponent({ name: 'ASelect' }).props('value')).toBe('en-US')
  })

  it('onChangeEmail_whenButtonClicked_shouldShowComingSoonInfo', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    await findButtonByText(wrapper, '更换邮箱').trigger('click')

    expect(messageInfo).toHaveBeenCalledWith('邮箱修改功能将在后续版本上线')
  })

  it('passwordModal_whenChangePasswordClickedAndCancelled_shouldToggleModalState', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    await findButtonByText(wrapper, '修改密码').trigger('click')
    expect(wrapper.vm.showPasswordModal).toBe(true)
    await nextTick()
    expect(document.body.querySelector('input[placeholder="输入当前密码"]')).not.toBeNull()

    const passwordModal = wrapper.findComponent({ name: 'AModal' })
    passwordModal.vm.$emit('cancel')
    await nextTick()

    expect(wrapper.vm.showPasswordModal).toBe(false)

    wrapper.vm.showPasswordModal = true
    await nextTick()
    passwordModal.vm.$emit('update:open', false)
    await nextTick()

    expect(wrapper.vm.showPasswordModal).toBe(false)
  })

  it('handlePasswordChange_whenOldOrNewPasswordMissing_shouldWarnAndSkipRequest', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    await wrapper.vm.handlePasswordChange()
    expect(messageWarning).toHaveBeenCalledWith('请填写完整信息')

    wrapper.vm.passwordForm.oldPassword = 'old-pass-1'
    await wrapper.vm.handlePasswordChange()
    expect(messageWarning).toHaveBeenCalledTimes(2)

    expect(api.put).not.toHaveBeenCalled()
  })

  it('handlePasswordChange_whenConfirmationMismatch_shouldShowErrorAndSkipRequest', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    wrapper.vm.passwordForm.oldPassword = 'old-pass-1'
    wrapper.vm.passwordForm.newPassword = 'new-pass-123'
    wrapper.vm.passwordForm.confirmPassword = 'other-pass-999'
    await wrapper.vm.handlePasswordChange()

    expect(messageError).toHaveBeenCalledWith('两次输入的新密码不一致')
    expect(api.put).not.toHaveBeenCalled()
  })

  it('handlePasswordChange_whenNewPasswordTooShort_shouldWarnAndSkipRequest', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    wrapper.vm.passwordForm.oldPassword = 'old-pass-1'
    wrapper.vm.passwordForm.newPassword = 'abc12'
    wrapper.vm.passwordForm.confirmPassword = 'abc12'
    await wrapper.vm.handlePasswordChange()

    expect(messageWarning).toHaveBeenCalledWith('新密码至少6位')
    expect(api.put).not.toHaveBeenCalled()
  })

  it('handlePasswordChange_whenSubmittedThroughModal_shouldUpdatePasswordAndResetForm', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    api.put.mockResolvedValue({ data: {} })
    const { wrapper } = await mountProfile()
    await flushPromises()

    await findButtonByText(wrapper, '修改密码').trigger('click')
    await nextTick()
    await setTeleportedInput('输入当前密码', 'old-pass-1')
    await setTeleportedInput('至少6位', 'new-pass-123')
    await setTeleportedInput('再次输入新密码', 'new-pass-123')

    expect(wrapper.vm.passwordForm).toMatchObject({
      oldPassword: 'old-pass-1',
      newPassword: 'new-pass-123',
      confirmPassword: 'new-pass-123'
    })

    wrapper.findComponent({ name: 'AModal' }).vm.$emit('ok')
    await flushPromises()

    expect(api.put).toHaveBeenCalledWith('/auth/password', {
      oldPassword: 'old-pass-1',
      newPassword: 'new-pass-123'
    })
    expect(messageSuccess).toHaveBeenCalledWith('密码修改成功，请重新登录')
    expect(wrapper.vm.showPasswordModal).toBe(false)
    expect(wrapper.vm.passwordForm).toMatchObject({
      oldPassword: '',
      newPassword: '',
      confirmPassword: ''
    })
    expect(wrapper.vm.passwordLoading).toBe(false)
  })

  it('handlePasswordChange_whenRequestFails_shouldResetLoadingAndKeepModalOpen', async () => {
    api.get.mockResolvedValue(userResponse({ id: 1, username: 'u' }))
    let rejectChange
    api.put.mockImplementation(() => new Promise((resolve, reject) => { rejectChange = reject }))
    const { wrapper } = await mountProfile()
    await flushPromises()

    wrapper.vm.showPasswordModal = true
    wrapper.vm.passwordForm.oldPassword = 'old-pass-1'
    wrapper.vm.passwordForm.newPassword = 'new-pass-123'
    wrapper.vm.passwordForm.confirmPassword = 'new-pass-123'

    const pending = wrapper.vm.handlePasswordChange()
    await nextTick()
    expect(wrapper.vm.passwordLoading).toBe(true)

    rejectChange(new Error('server error'))
    await pending
    await flushPromises()

    expect(messageSuccess).not.toHaveBeenCalled()
    expect(wrapper.vm.passwordLoading).toBe(false)
    expect(wrapper.vm.showPasswordModal).toBe(true)
    expect(wrapper.vm.passwordForm.newPassword).toBe('new-pass-123')
  })
})
