import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import Antd, { message } from 'ant-design-vue'

beforeAll(() => {
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
  vi.stubGlobal('IntersectionObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() { return [] }
  })
})

const { get, put } = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn()
}))

vi.mock('@/api', () => ({
  default: { get, put },
  handleUnauthorized: vi.fn()
}))

import ProjectSettings from './ProjectSettings.vue'

function createTestRouter(path = '/projects/7') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
  })
  router.push(path)
  return router
}

async function mountView(path = '/projects/7') {
  const router = createTestRouter(path)
  await router.isReady()
  const wrapper = mount(ProjectSettings, {
    global: { plugins: [router, Antd] }
  })
  return wrapper
}

function projectResponse(overrides = {}) {
  return {
    data: {
      data: {
        id: 7,
        name: '演示项目',
        description: '演示描述',
        language: 'Java',
        sourceType: 'GIT',
        totalClasses: 42,
        healthScore: 88,
        createdAt: '2026-02-03T10:30:00',
        updatedAt: '2026-03-04T18:05:00',
        ...overrides
      }
    }
  }
}

describe('ProjectSettings', () => {
  let successSpy
  let errorSpy
  let warningSpy

  beforeEach(() => {
    get.mockReset().mockResolvedValue(projectResponse())
    put.mockReset().mockResolvedValue({ data: { data: {} } })
    localStorage.clear()
    successSpy = vi.spyOn(message, 'success').mockImplementation(() => {})
    errorSpy = vi.spyOn(message, 'error').mockImplementation(() => {})
    warningSpy = vi.spyOn(message, 'warning').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('fetchProject_whenLoadingTakesTime_shouldShowSkeletonThenForm', async () => {
    let resolveGet
    get.mockImplementationOnce(() => new Promise(resolve => { resolveGet = resolve }))

    const wrapper = await mountView()
    expect(wrapper.find('.ant-skeleton').exists()).toBe(true)
    expect(wrapper.find('.settings-card').exists()).toBe(false)

    resolveGet(projectResponse())
    await flushPromises()

    expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
    expect(wrapper.find('.settings-card').exists()).toBe(true)
    expect(get).toHaveBeenCalledWith('/projects/7')
  })

  it('fetchProject_whenSuccess_shouldFillFormAndDescriptions', async () => {
    const wrapper = await mountView()
    await flushPromises()

    const input = wrapper.find('input')
    const textarea = wrapper.find('textarea')
    expect(input.element.value).toBe('演示项目')
    expect(textarea.element.value).toBe('演示描述')

    const text = wrapper.text()
    expect(text).toContain('Java')
    expect(text).toContain('GIT')
    expect(text).toContain('42')
    expect(text).toContain('88 分')
    expect(text).not.toContain('-') // 日期均已格式化，源字段缺失才显示 '-'
  })

  it('fetchProject_whenOptionalFieldsMissing_shouldRenderFallbacks', async () => {
    get.mockResolvedValueOnce(projectResponse({
      name: null,
      description: undefined,
      language: null,
      sourceType: null,
      totalClasses: 0,
      healthScore: null,
      createdAt: null,
      updatedAt: null
    }))

    const wrapper = await mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Java') // language 回退默认值
    expect(text).toContain('0 分') // healthScore ?? 0
    expect(text).toContain('-') // sourceType / 日期回退
    expect(text).toContain('0') // totalClasses || 0
  })

  it('fetchProject_whenFails_shouldShowErrorStateWithServerMessage', async () => {
    get.mockRejectedValueOnce({ response: { data: { message: '项目不存在' } } })

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).toContain('项目不存在')
  })

  it('fetchProject_whenFailsWithoutServerMessage_shouldShowDefaultErrorText', async () => {
    get.mockRejectedValueOnce(new Error('network down'))

    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('加载项目信息失败')
  })

  it('fetchProject_whenRetryClicked_shouldRefetchAndRenderForm', async () => {
    get.mockRejectedValueOnce(new Error('boom'))

    const wrapper = await mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')

    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(get).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.settings-card').exists()).toBe(true)
  })

  it('saveSettings_whenNameBlank_shouldWarnAndSkipRequest', async () => {
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.find('input').setValue('   ')
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('保存修改'))
    await saveBtn.trigger('click')
    await flushPromises()

    expect(warningSpy).toHaveBeenCalledWith('项目名称不能为空')
    expect(put).not.toHaveBeenCalled()
  })

  it('saveSettings_whenSuccess_shouldPutPayloadAndToast', async () => {
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.find('input').setValue('新名称')
    await wrapper.find('textarea').setValue('新描述')
    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('保存修改'))
    await saveBtn.trigger('click')
    await flushPromises()

    expect(put).toHaveBeenCalledWith('/projects/7', {
      name: '新名称',
      description: '新描述'
    })
    expect(successSpy).toHaveBeenCalledWith('项目信息已更新')
  })

  it('saveSettings_whenFails_shouldToastServerMessage', async () => {
    put.mockRejectedValueOnce({ response: { data: { message: '名称重复' } } })

    const wrapper = await mountView()
    await flushPromises()

    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('保存修改'))
    await saveBtn.trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('名称重复')
  })

  it('saveSettings_whenFailsWithoutServerMessage_shouldToastDefaultText', async () => {
    put.mockRejectedValueOnce(new Error('500'))

    const wrapper = await mountView()
    await flushPromises()

    const saveBtn = wrapper.findAll('button').find(b => b.text().includes('保存修改'))
    await saveBtn.trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('保存失败')
  })

  it('saveSettings_whenProjectClearedBeforeLocalSync_shouldSkipUpdateWithoutCrash', async () => {
    const wrapper = await mountView()
    await flushPromises()

    wrapper.vm.project = null // 保存期间数据源被清空：防御性守卫跳过本地同步且不抛错
    await wrapper.vm.saveSettings()

    expect(put).toHaveBeenCalledWith('/projects/7', {
      name: '演示项目',
      description: '演示描述'
    })
    expect(successSpy).toHaveBeenCalledWith('项目信息已更新')
    expect(errorSpy).not.toHaveBeenCalled() // 无守卫时项目为 null 会触发兜底错误提示
  })
})
