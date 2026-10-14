import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { nextTick } from 'vue'
import {
  Spin as ASpin, Row as ARow, Col as ACol, Card as ACard, Tag as ATag,
  Button as AButton, Result as AResult, Space as ASpace, Empty as AEmpty,
  Popconfirm as APopconfirm, message
} from 'ant-design-vue'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}))

vi.mock('../../api', () => ({ default: apiMock }))

import AdminAiModels from './AdminAiModels.vue'

enableAutoUnmount(afterEach)

const globalComponents = {
  ASpin, ARow, ACol, ACard, ATag, AButton, AResult, ASpace, AEmpty, APopconfirm
}

function makeModelsData(overrides = {}) {
  return {
    models: [
      {
        id: 'deepseek-chat', name: 'DeepSeek Chat', provider: 'deepseek',
        current: true, available: true, contextWindow: 131072, stale: false
      },
      {
        id: 'claude-sonnet', name: 'Claude Sonnet', provider: 'claude',
        current: false, available: true, contextWindow: 2097152, stale: true
      },
      {
        id: 'legacy-model', name: 'Legacy Model', provider: 'openai',
        current: false, available: false, contextWindow: null, stale: false
      }
    ],
    current: 'deepseek-chat',
    listSource: 'live',
    ...overrides
  }
}

function mountView() {
  return mount(AdminAiModels, { global: { components: globalComponents } })
}

describe('AdminAiModels', () => {
  beforeEach(() => {
    // ant-design-vue 的 Row/Col/触发器依赖 matchMedia 与 ResizeObserver，jsdom 缺失
    vi.stubGlobal('matchMedia', vi.fn((query) => ({
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
    apiMock.get.mockReset()
    apiMock.post.mockReset()
    apiMock.put.mockReset()
    apiMock.get.mockResolvedValue({ data: { code: 200, data: makeModelsData() } })
    vi.spyOn(message, 'success').mockImplementation(() => {})
    vi.spyOn(message, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('AdminAiModels_whenMounted_shouldFetchAndRenderModels', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledWith('/admin/ai/models')
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.error).toBeNull()
    expect(wrapper.vm.models).toHaveLength(3)
    expect(wrapper.vm.current).toBe('deepseek-chat')
    expect(wrapper.vm.currentName).toBe('DeepSeek Chat')

    // 当前模型卡片
    const currentCard = wrapper.find('.current-card')
    expect(currentCard.exists()).toBe(true)
    expect(currentCard.text()).toContain('当前使用模型')
    expect(currentCard.text()).toContain('DeepSeek Chat')
    expect(currentCard.text()).toContain('deepseek-chat')
    expect(currentCard.text()).toContain('运行中')
    expect(currentCard.text()).toContain('切换模型后所有 AI 功能')

    // 模型卡片列表
    const cards = wrapper.findAll('.model-card')
    expect(cards).toHaveLength(3)
    expect(wrapper.findAll('.model-card.is-current')).toHaveLength(1)
    expect(cards[0].text()).toContain('当前使用')

    // providerLabel / formatContext 渲染
    expect(cards[0].text()).toContain('DeepSeek')
    expect(cards[0].text()).toContain('128K 上下文')
    expect(cards[1].text()).toContain('Claude')
    expect(cards[1].text()).toContain('2M 上下文')
    expect(cards[1].text()).toContain('已不在服务商清单')

    // 非当前模型可切换；不可用模型按钮禁用
    expect(cards[0].findAllComponents(APopconfirm)).toHaveLength(0)
    expect(cards[1].findAllComponents(APopconfirm)).toHaveLength(1)
    const switchButtons = cards[2].findAll('button')
    expect(switchButtons[1].text()).toContain('设为当前模型')
    expect(switchButtons[1].attributes('disabled')).toBeDefined()

    // 空态与错误态均不显示
    expect(wrapper.findComponent(AEmpty).exists()).toBe(false)
    expect(wrapper.findComponent(AResult).exists()).toBe(false)
  })

  it('AdminAiModels_providerLabelAndFormatContext_shouldMapValues', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.providerLabel('claude')).toBe('Claude')
    expect(wrapper.vm.providerLabel('deepseek')).toBe('DeepSeek')
    expect(wrapper.vm.providerLabel('openai')).toBe('DeepSeek')

    expect(wrapper.vm.formatContext(1048576)).toBe('1M')
    expect(wrapper.vm.formatContext(2097152)).toBe('2M')
    expect(wrapper.vm.formatContext(1048575)).toBe('1024K')
    expect(wrapper.vm.formatContext(1536)).toBe('2K')
    expect(wrapper.vm.formatContext(1000)).toBe('1K')
  })

  it('AdminAiModels_currentName_whenModelMissing_shouldFallbackToIdOrNull', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.vm.current = 'ghost-id'
    expect(wrapper.vm.currentName).toBe('ghost-id')

    wrapper.vm.current = null
    expect(wrapper.vm.currentName).toBeNull()
  })

  it('AdminAiModels_whenListSourceFallback_shouldShowBanner', async () => {
    apiMock.get.mockResolvedValue({
      data: { code: 200, data: makeModelsData({ listSource: 'fallback' }) }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.ant-tag').text()).toBe('服务商清单接口不可用，展示内置清单')
  })

  it('AdminAiModels_whenPayloadCodeNot200_shouldShowPayloadMessage', async () => {
    apiMock.get.mockResolvedValue({ data: { code: 500, message: '内部错误' } })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.error).toBe('内部错误')
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.models).toEqual([])
    expect(wrapper.findComponent(AResult).props('subTitle')).toBe('内部错误')
  })

  it('AdminAiModels_whenPayloadCodeNot200WithoutMessage_shouldShowDefaultMessage', async () => {
    apiMock.get.mockResolvedValue({ data: { code: 500 } })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.error).toBe('加载模型列表失败')
    expect(wrapper.findComponent(AResult).exists()).toBe(true)
  })

  it('AdminAiModels_whenRequestFailsWithResponse_shouldShowServerMessage', async () => {
    apiMock.get.mockRejectedValue({ response: { data: { message: '权限不足' } } })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.error).toBe('权限不足')
    expect(wrapper.findComponent(AResult).props('subTitle')).toBe('权限不足')
  })

  it('AdminAiModels_whenRequestFailsWithPlainReason_shouldShowDefaultMessage', async () => {
    apiMock.get.mockRejectedValue('nope')
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.error).toBe('加载模型列表失败')
  })

  it('AdminAiModels_whenRetryClicked_shouldRefetchAndRecover', async () => {
    apiMock.get.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.findComponent(AResult).exists()).toBe(true)

    apiMock.get.mockResolvedValue({ data: { code: 200, data: makeModelsData() } })
    await wrapper.find('.ant-result button').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.error).toBeNull()
    expect(wrapper.findComponent(AResult).exists()).toBe(false)
    expect(wrapper.findAll('.model-card')).toHaveLength(3)
  })

  it('AdminAiModels_whenRefreshClicked_shouldRefetchModels', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.page-header button').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/ai/models')
  })

  it('AdminAiModels_whenModelsEmpty_shouldShowEmptyState', async () => {
    // 响应体缺少 models 字段时回退为空数组
    apiMock.get.mockResolvedValue({
      data: { code: 200, data: { current: null, listSource: 'live' } }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.model-card')).toHaveLength(0)
    const empty = wrapper.findComponent(AEmpty)
    expect(empty.exists()).toBe(true)
    expect(empty.props('description')).toBe('没有可用的 AI 模型（未配置 API Key）')
    expect(wrapper.findComponent(ASpin).props('spinning')).toBe(false)
  })

  it('AdminAiModels_whenTestModelSucceeds_shouldStoreAndRenderResult', async () => {
    let resolvePost
    apiMock.post.mockImplementation(() => new Promise((resolve) => { resolvePost = resolve }))
    const wrapper = mountView()
    await flushPromises()

    const cards = wrapper.findAll('.model-card')
    await cards[1].findAll('button')[0].trigger('click')
    await nextTick()
    expect(wrapper.vm.testing).toBe('claude-sonnet')
    expect(cards[1].findAll('button')[0].classes()).toContain('ant-btn-loading')

    resolvePost({ data: { code: 200, data: { success: true, latencyMs: 42 } } })
    await flushPromises()

    expect(apiMock.post).toHaveBeenCalledWith('/admin/ai/models/test', { model: 'claude-sonnet' })
    expect(wrapper.vm.testResults['claude-sonnet']).toEqual({ success: true, latencyMs: 42 })
    expect(wrapper.vm.testing).toBeNull()
    expect(cards[1].find('.test-result').text()).toContain('连通正常 42ms')
  })

  it('AdminAiModels_whenTestModelFails_shouldRenderErrorTag', async () => {
    apiMock.post.mockResolvedValue({
      data: { code: 200, data: { success: false, error: 'timeout' } }
    })
    const wrapper = mountView()
    await flushPromises()

    const cards = wrapper.findAll('.model-card')
    await cards[1].findAll('button')[0].trigger('click')
    await flushPromises()

    const result = cards[1].find('.test-result')
    expect(result.text()).toContain('连通失败')
    expect(result.find('.test-error').text()).toBe('timeout')
    expect(wrapper.vm.testing).toBeNull()
  })

  it('AdminAiModels_whenTestModelReturnsNon200_shouldToastMessage', async () => {
    const wrapper = mountView()
    await flushPromises()
    const cards = wrapper.findAll('.model-card')

    apiMock.post.mockResolvedValueOnce({ data: { code: 500, message: '不支持该模型' } })
    await cards[1].findAll('button')[0].trigger('click')
    await flushPromises()
    expect(message.error).toHaveBeenCalledWith('不支持该模型')

    message.error.mockClear()
    apiMock.post.mockResolvedValueOnce({ data: { code: 500 } })
    await cards[2].findAll('button')[0].trigger('click')
    await flushPromises()
    expect(message.error).toHaveBeenCalledWith('测试失败')
  })

  it('AdminAiModels_whenTestModelThrows_shouldSwallowError', async () => {
    apiMock.post.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    const cards = wrapper.findAll('.model-card')
    await cards[1].findAll('button')[0].trigger('click')
    await flushPromises()

    expect(wrapper.vm.testing).toBeNull()
    expect(wrapper.vm.testResults).toEqual({})
    expect(message.error).not.toHaveBeenCalled()
  })

  it('AdminAiModels_whenSwitchConfirmed_shouldToastAndRefetchModels', async () => {
    apiMock.put.mockResolvedValue({ data: { code: 200 } })
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(1)
    const popconfirm = wrapper.findAllComponents(APopconfirm)[0]
    await popconfirm.vm.$emit('confirm')
    await flushPromises()

    expect(apiMock.put).toHaveBeenCalledWith('/admin/ai/model', { model: 'claude-sonnet' })
    expect(message.success).toHaveBeenCalledWith('已切换到 Claude Sonnet')
    // 切换成功后重新拉取列表
    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.switching).toBeNull()
  })

  it('AdminAiModels_whenSwitchReturnsNon200_shouldToastMessage', async () => {
    const wrapper = mountView()
    await flushPromises()
    const popconfirms = wrapper.findAllComponents(APopconfirm)

    apiMock.put.mockResolvedValueOnce({ data: { code: 500, message: '切换被拒绝' } })
    await popconfirms[0].vm.$emit('confirm')
    await flushPromises()
    expect(message.error).toHaveBeenCalledWith('切换被拒绝')
    expect(apiMock.get).toHaveBeenCalledTimes(1)

    message.error.mockClear()
    apiMock.put.mockResolvedValueOnce({ data: { code: 500 } })
    await popconfirms[1].vm.$emit('confirm')
    await flushPromises()
    expect(message.error).toHaveBeenCalledWith('切换失败')
    expect(message.success).not.toHaveBeenCalled()
  })

  it('AdminAiModels_whenSwitchThrows_shouldSwallowError', async () => {
    apiMock.put.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAllComponents(APopconfirm)[0].vm.$emit('confirm')
    await flushPromises()

    expect(wrapper.vm.switching).toBeNull()
    expect(message.success).not.toHaveBeenCalled()
    expect(apiMock.get).toHaveBeenCalledTimes(1)
  })
})
