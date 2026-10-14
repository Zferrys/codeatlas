import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// ant-design-vue 部分 mock：组件走真实渲染，message/Modal.confirm 替换为可断言桩
vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
    Modal: Object.assign({}, actual.Modal, { confirm: vi.fn() })
  }
})

vi.mock('../api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  handleUnauthorized: vi.fn()
}))

// AI 任务单例含真实定时器/全局状态，隔离为本文件的桩
vi.mock('../composables/useAiTask', async () => {
  const { ref: vueRef } = await import('vue')
  return {
    AI_TASK_CANCELLED: Symbol('AI_TASK_CANCELLED'),
    activeTask: vueRef(null),
    runAiTask: vi.fn(),
    cancelActiveAiTask: vi.fn()
  }
})

// 地图子组件由独立测试覆盖，且 G6/three 在 jsdom 下无意义：以轻量桩替代
vi.mock('../components/map/CodeMap.vue', () => ({
  default: {
    name: 'CodeMap',
    props: ['projectId', 'hideTest', 'hideUtil', 'focusFqn', 'focusNonce'],
    emits: ['node-click'],
    template: '<div class="code-map-stub" />'
  }
}))

vi.mock('../components/map/CodeMap3D.vue', () => ({
  default: {
    name: 'CodeMap3D',
    props: ['projectId', 'hideTest', 'hideUtil', 'focusFqn', 'focusNonce'],
    emits: ['node-click'],
    template: '<div class="code-map-3d-stub" />'
  }
}))

import Antd, { message, Modal } from 'ant-design-vue'
import api from '../api'
import { runAiTask, cancelActiveAiTask, activeTask, AI_TASK_CANCELLED } from '../composables/useAiTask'
import ProjectMap from './ProjectMap.vue'

vi.setConfig({ testTimeout: 30000 })

function mockApi({ scanData = { scanStatus: 'COMPLETED' }, scanError = null, history = [], historyError = null, historyByScope = null } = {}) {
  api.get.mockImplementation((url, config) => {
    if (url.endsWith('/scans/status')) {
      if (scanError) return Promise.reject(scanError)
      return Promise.resolve({ data: { data: scanData } })
    }
    if (url.endsWith('/chat-history')) {
      if (historyError) return Promise.reject(historyError)
      if (historyByScope) return Promise.resolve({ data: { data: historyByScope[config?.params?.scope] || [] } })
      return Promise.resolve({ data: { data: history } })
    }
    return Promise.resolve({ data: { data: {} } })
  })
}

async function mountMap({ flush = true, route = '/project/p1/map' } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/project/:id/map', component: ProjectMap }]
  })
  router.push(route)
  await router.isReady()
  const wrapper = mount(ProjectMap, {
    global: { plugins: [Antd, router] },
    attachTo: document.body
  })
  if (flush) await flushPromises()
  return { wrapper, router }
}

// antd 会在两个 CJK 字符的按钮文案中插入空格（"提 问"），匹配前统一去除空白
function bodyButton(text, root = document.body) {
  const target = text.replace(/\s/g, '')
  return [...root.querySelectorAll('button')]
    .find(button => button.textContent.replace(/\s/g, '').includes(target))
}

// 以原生 input 事件驱动 antd 输入组件，触发父组件 v-model 更新回调
function nativeInput(element, value) {
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
}

// 通过真实输入事件填写问答框（覆盖 a-textarea 的 v-model:value 回调）
async function typeQuestion(text) {
  nativeInput(document.body.querySelector('.ant-modal textarea'), text)
  await flushPromises()
}

async function openImpactModal(wrapper) {
  wrapper.vm.openImpactModal()
  await flushPromises()
}

async function openQaModal(wrapper) {
  wrapper.vm.openQaPanel()
  await flushPromises()
}

function sampleNode(overrides = {}) {
  return {
    id: 'com.example.OrderService',
    label: 'OrderService',
    layer: 'SERVICE',
    methods: 12,
    lineCount: 340,
    group: 'order-module',
    ...overrides
  }
}

function historyItem(overrides = {}) {
  return {
    id: 1,
    question: '这个类的职责是什么？',
    answer: '负责订单编排',
    verification: null,
    contextFqn: 'com.example.OrderService',
    createdAt: '2026-01-02T10:00:00',
    ...overrides
  }
}

describe('ProjectMap.vue', () => {
  beforeAll(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
  })

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
    const nativeGetComputedStyle = window.getComputedStyle.bind(window)
    vi.stubGlobal('getComputedStyle', element => nativeGetComputedStyle(element))
    api.get.mockReset()
    api.post.mockReset()
    api.delete.mockReset()
    runAiTask.mockReset()
    cancelActiveAiTask.mockReset()
    activeTask.value = null
    message.success.mockClear()
    message.error.mockClear()
    message.warning.mockClear()
    Modal.confirm.mockClear()
    mockApi()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  describe('挂载与扫描状态检查', () => {
    it('onMounted_whenScanRunning_shouldShowPreparingAlert', async () => {
      mockApi({ scanData: { scanStatus: 'RUNNING' } })

      const { wrapper } = await mountMap()

      expect(api.get).toHaveBeenCalledWith('/projects/p1/scans/status')
      expect(wrapper.vm.mapPreparing).toBe(true)
      expect(wrapper.text()).toContain('图谱数据正在准备中')
    })

    it('onMounted_whenNeverScanned_shouldShowPreparingAlert', async () => {
      mockApi({ scanData: { scanStatus: 'NONE' } })

      const { wrapper } = await mountMap()

      expect(wrapper.vm.mapPreparing).toBe(true)
    })

    it('onMounted_whenAiAnalysisRunning_shouldShowPreparingAlert', async () => {
      mockApi({ scanData: { scanStatus: 'COMPLETED', aiAnalysisRunning: true } })

      const { wrapper } = await mountMap()

      expect(wrapper.vm.mapPreparing).toBe(true)
    })

    it('onMounted_whenIdleCompleted_shouldNotShowPreparingAlert', async () => {
      mockApi({ scanData: { scanStatus: 'COMPLETED' } })

      const { wrapper } = await mountMap()

      expect(wrapper.vm.mapPreparing).toBe(false)
      expect(wrapper.text()).not.toContain('图谱数据正在准备中')
    })

    it('onMounted_whenStatusDataMissing_shouldStayIdle', async () => {
      mockApi({ scanData: null })

      const { wrapper } = await mountMap()

      expect(wrapper.vm.mapPreparing).toBe(false)
    })

    it('onMounted_whenStatusRequestFails_shouldIgnoreError', async () => {
      mockApi({ scanError: new Error('status down') })

      const { wrapper } = await mountMap()

      expect(wrapper.vm.mapPreparing).toBe(false)
    })

    it('watchRouteFocus_whenFocusQueryChanges_shouldFocusNodeAndBumpNonce', async () => {
      const { wrapper, router } = await mountMap()

      expect(wrapper.vm.focusFqn).toBeNull()
      expect(wrapper.vm.focusNonce).toBe(0)

      await router.push('/project/p1/map?focus=com.example.OrderService')
      await flushPromises()
      expect(wrapper.vm.focusFqn).toBe('com.example.OrderService')
      expect(wrapper.vm.focusNonce).toBe(1)

      const codeMap = wrapper.findComponent({ name: 'CodeMap' })
      expect(codeMap.props('focusFqn')).toBe('com.example.OrderService')
      expect(codeMap.props('focusNonce')).toBe(1)

      // 清除 focus 参数：回到未聚焦态
      await router.push('/project/p1/map')
      await flushPromises()
      expect(wrapper.vm.focusFqn).toBeNull()

      // 同一 FQN 重复跳转：nonce 继续自增以触发重新聚焦
      await router.push('/project/p1/map?focus=com.example.OrderService')
      await flushPromises()
      expect(wrapper.vm.focusNonce).toBe(2)
    })

    it('watchRouteFocus_whenMountedWithFocusQuery_shouldApplyImmediately', async () => {
      const { wrapper } = await mountMap({ route: '/project/p1/map?focus=com.a.B' })

      expect(wrapper.vm.focusFqn).toBe('com.a.B')
      expect(wrapper.vm.focusNonce).toBe(1)
    })
  })

  describe('渲染模式与工具栏', () => {
    it('toolbar_whenRenderMode2d_shouldMountCodeMapOnly', async () => {
      const { wrapper } = await mountMap()

      expect(wrapper.findComponent({ name: 'CodeMap' }).exists()).toBe(true)
      expect(wrapper.findComponent({ name: 'CodeMap3D' }).exists()).toBe(false)
      expect(wrapper.findComponent({ name: 'CodeMap' }).props('projectId')).toBe('p1')
    })

    it('toolbar_whenSwitchTo3d_shouldMountCodeMap3D', async () => {
      const { wrapper } = await mountMap()

      const radioInputs = wrapper.findAll('.ant-radio-button-wrapper input')
      radioInputs[1].element.click()
      await flushPromises()

      expect(wrapper.vm.renderMode).toBe('3d')
      expect(wrapper.findComponent({ name: 'CodeMap' }).exists()).toBe(false)
      expect(wrapper.findComponent({ name: 'CodeMap3D' }).exists()).toBe(true)

      // 切回 2D
      radioInputs[0].element.click()
      await flushPromises()
      expect(wrapper.vm.renderMode).toBe('2d')
      expect(wrapper.findComponent({ name: 'CodeMap' }).exists()).toBe(true)
    })

    it('toolbar_whenHideFlagsToggled_shouldPassToProps', async () => {
      const { wrapper } = await mountMap()

      const checkboxes = wrapper.findAll('.ant-checkbox-input')
      expect(checkboxes).toHaveLength(2)
      checkboxes[0].element.click()
      checkboxes[1].element.click()
      await flushPromises()

      expect(wrapper.vm.hideTest).toBe(true)
      expect(wrapper.vm.hideUtil).toBe(true)
      const codeMap = wrapper.findComponent({ name: 'CodeMap' })
      expect(codeMap.props('hideTest')).toBe(true)
      expect(codeMap.props('hideUtil')).toBe(true)
    })

    it('toolbar_whenImpactButtonWithoutData_shouldBeDisabledUntilNodeSelected', async () => {
      const { wrapper } = await mountMap()

      const impactButton = wrapper.findAll('button').find(button => button.text().includes('影响模拟'))
      expect(impactButton.attributes('disabled')).toBeDefined()

      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()

      expect(wrapper.findAll('button').find(button => button.text().includes('影响模拟')).attributes('disabled')).toBeUndefined()
    })

    it('toolbar_whenImpactButtonClicked_shouldOpenModalAndLoadImpactHistory', async () => {
      const { wrapper } = await mountMap()
      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()

      await wrapper.findAll('button').find(button => button.text().includes('影响模拟')).trigger('click')
      await flushPromises()

      expect(wrapper.vm.showImpactModal).toBe(true)
      expect(api.get).toHaveBeenCalledWith('/projects/p1/chat-history', { params: { scope: 'IMPACT' } })
    })

    it('toolbar_whenQaButtonClicked_shouldOpenGlobalQaModal', async () => {
      const { wrapper } = await mountMap()

      await wrapper.findAll('button').find(button => button.text().includes('上下文问答')).trigger('click')
      await flushPromises()

      expect(wrapper.vm.showQaModal).toBe(true)
      expect(wrapper.vm.qaTarget).toBeNull()
      expect(api.get).toHaveBeenCalledWith('/projects/p1/chat-history', { params: { scope: 'GLOBAL' } })
    })

    it('toolbar_whenSearchSubmitted_shouldNotThrow', async () => {
      const { wrapper } = await mountMap()

      const search = wrapper.findComponent({ name: 'AInputSearch' })
      await search.vm.$emit('update:value', 'OrderService')
      await search.vm.$emit('search', 'OrderService')

      expect(wrapper.vm.searchQuery).toBe('OrderService')
    })
  })

  describe('节点点击与详情抽屉', () => {
    it('onNodeClick_whenNodeProvided_shouldOpenDrawerWithDetails', async () => {
      const { wrapper } = await mountMap()

      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()

      expect(wrapper.vm.selectedNode.label).toBe('OrderService')
      expect(wrapper.vm.drawerVisible).toBe(true)
      expect(wrapper.vm.hasData).toBe(true)
      expect(document.body.textContent).toContain('com.example.OrderService')
      expect(document.body.textContent).toContain('OrderService')
      expect(document.body.textContent).toContain('12')
      expect(document.body.textContent).toContain('340')
      expect(document.body.textContent).toContain('order-module')
      // SERVICE 层标签（自定义十六进制色不走预设类名，按文本定位）
      const layerTag = [...document.body.querySelectorAll('.ant-tag')].find(tag => tag.textContent.trim() === 'SERVICE')
      expect(layerTag).toBeTruthy()
    })

    it('onNodeClick_whenNull_shouldClearSelectionAndCloseDrawer', async () => {
      const { wrapper } = await mountMap()
      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()
      expect(wrapper.vm.drawerVisible).toBe(true)

      // 抽屉关闭按钮/遮罩触发 @close
      wrapper.findComponent({ name: 'ADrawer' }).vm.$emit('close')
      await flushPromises()
      expect(wrapper.vm.drawerVisible).toBe(false)

      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()
      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', null)
      await flushPromises()

      expect(wrapper.vm.selectedNode).toBeNull()
      expect(wrapper.vm.drawerVisible).toBe(false)
      expect(document.body.textContent).toContain('请点击地图节点查看详情')
    })

    it('onNodeClick_whenFieldsMissing_shouldRenderFallbackValues', async () => {
      const { wrapper } = await mountMap()

      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', { id: 'com.a.Ghost' })
      await flushPromises()

      expect(document.body.textContent).toContain('UNKNOWN')
      const drawerText = document.body.textContent
      expect(drawerText).toContain('com.a.Ghost')
      expect(drawerText).toContain('-')
    })

    it('onNodeClick_whenMapPreparing_shouldClearPreparingState', async () => {
      mockApi({ scanData: { scanStatus: 'RUNNING' } })
      const { wrapper } = await mountMap()
      expect(wrapper.vm.mapPreparing).toBe(true)

      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()

      expect(wrapper.vm.mapPreparing).toBe(false)
    })

    it('drawer_whenAskAboutClassClicked_shouldOpenQaWithTarget', async () => {
      const { wrapper } = await mountMap()
      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()

      bodyButton('对此类提问').click()
      await flushPromises()

      expect(wrapper.vm.showQaModal).toBe(true)
      expect(wrapper.vm.qaTarget.id).toBe('com.example.OrderService')
      expect(document.body.textContent).toContain('上下文问答 — OrderService')
      expect(api.get).toHaveBeenCalledWith('/projects/p1/chat-history', { params: { scope: 'CLASS' } })
    })

    it('drawer_whenImpactSimClicked_shouldPrefillTargetClass', async () => {
      const { wrapper } = await mountMap()
      wrapper.findComponent({ name: 'CodeMap' }).vm.$emit('node-click', sampleNode())
      await flushPromises()

      // 工具栏也有同名按钮：限定在抽屉内点击
      bodyButton('影响模拟', document.body.querySelector('.ant-drawer')).click()
      await flushPromises()

      expect(wrapper.vm.showImpactModal).toBe(true)
      expect(wrapper.vm.impactForm.targetClass).toBe('com.example.OrderService')
    })

    it('runImpactSim_whenNodeHasNoId_shouldFallbackToLabel', async () => {
      const { wrapper } = await mountMap()

      wrapper.vm.runImpactSim({ label: 'OnlyLabel' })
      await flushPromises()

      expect(wrapper.vm.impactForm.targetClass).toBe('OnlyLabel')
    })

    it('runImpactSim_whenNodeMissing_shouldFallbackToEmptyString', async () => {
      const { wrapper } = await mountMap()

      wrapper.vm.runImpactSim(null)
      await flushPromises()

      expect(wrapper.vm.impactForm.targetClass).toBe('')
    })

    it('getLayerColor_whenKnownOrUnknownLayer_shouldMapOrFallback', async () => {
      const { wrapper } = await mountMap()
      const vm = wrapper.vm

      expect(vm.getLayerColor('CONTROLLER')).toBe('#1890ff')
      expect(vm.getLayerColor('SERVICE')).toBe('#52c41a')
      expect(vm.getLayerColor('REPOSITORY')).toBe('#fa8c16')
      expect(vm.getLayerColor('DOMAIN')).toBe('#13c2c2')
      expect(vm.getLayerColor('DTO')).toBe('#722ed1')
      expect(vm.getLayerColor('CONFIG')).toBe('#f5222d')
      expect(vm.getLayerColor('UTIL')).toBe('#8c8c8c')
      expect(vm.getLayerColor('SECURITY')).toBe('#eb2f96')
      expect(vm.getLayerColor('EXCEPTION')).toBe('#fa541c')
      expect(vm.getLayerColor('FILTER')).toBe('#2f54eb')
      expect(vm.getLayerColor(undefined)).toBe('#d9d9d9')
    })
  })

  describe('影响模拟', () => {
    it('doImpactSim_whenTargetClassEmpty_shouldWarnAndSkipRequest', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      bodyButton('开始分析').click()
      await flushPromises()

      expect(message.warning).toHaveBeenCalledWith('请输入目标类')
      expect(runAiTask).not.toHaveBeenCalled()
    })

    it('doImpactSim_whenSuccess_shouldRenderResultAndReloadHistory', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      const paths = Array.from({ length: 25 }, (unused, index) => ({ source: `A${index}`, target: `B${index}`, depth: index }))
      let capturedOptions
      runAiTask.mockImplementation(async options => {
        capturedOptions = options
        return {
          targetClass: 'com.example.OrderService',
          changeType: 'MODIFY_METHOD',
          totalImpacted: 11,
          impactPaths: paths,
          aiAnalysis: '**高风险** 修改需谨慎'
        }
      })
      // 表单经真实交互填写：输入框/下拉/文本域的 v-model 回调全部走一遍
      const targetInput = [...document.body.querySelectorAll('.ant-modal input')]
        .find(input => input.placeholder.includes('com.example.OrderService'))
      nativeInput(targetInput, 'com.example.OrderService')
      document.body.querySelector('.ant-select-selector').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
      await flushPromises()
      const addFieldOption = [...document.body.querySelectorAll('.ant-select-item-option')]
        .find(option => option.textContent.includes('添加字段'))
      expect(addFieldOption).toBeTruthy()
      addFieldOption.click()
      await flushPromises()
      const methodInput = [...document.body.querySelectorAll('.ant-modal input')].find(input => input.placeholder === '可选')
      nativeInput(methodInput, 'doRun')
      nativeInput(document.body.querySelector('.ant-modal textarea'), '改签名')
      await flushPromises()

      expect(wrapper.vm.impactForm).toEqual({
        targetClass: 'com.example.OrderService',
        changeType: 'ADD_FIELD',
        targetMethod: 'doRun',
        description: '改签名'
      })
      bodyButton('开始分析').click()
      await flushPromises()

      expect(capturedOptions.projectId).toBe('p1')
      expect(capturedOptions.label).toBe('影响模拟')
      expect(wrapper.vm.impactResult.totalImpacted).toBe(11)
      const text = document.body.textContent
      expect(text).toContain('HIGH')
      expect(text).toContain('11')
      // 影响链路最多展示 20 条
      expect(document.body.querySelectorAll('.impact-paths .ant-tag')).toHaveLength(20)
      expect(text).toContain('AI 风险分析')
      expect(document.body.querySelector('.ai-analysis-box .markdown-body')).not.toBeNull()
      expect(document.body.querySelector('.ai-analysis-box').innerHTML).toContain('<strong>高风险</strong>')
      // 完成后刷新历史
      expect(api.get.mock.calls.filter(call => call[0].endsWith('/chat-history')).length).toBeGreaterThanOrEqual(2)

      // execute 执行体：POST 到影响模拟端点，携带任务头与取消信号，返回 data.data
      const signal = new AbortController().signal
      api.post.mockResolvedValueOnce({ data: { data: { totalImpacted: 1 } } })
      const result = await capturedOptions.execute({ taskId: 'ai-1', signal })
      expect(api.post).toHaveBeenCalledWith('/projects/p1/ai/impact-simulate', wrapper.vm.impactForm, {
        headers: { 'X-Ai-Task-Id': 'ai-1' },
        signal
      })
      expect(result).toEqual({ totalImpacted: 1 })
    })

    it('doImpactSim_whenResultEmpty_shouldRenderLowRiskAndEmptyPaths', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockResolvedValueOnce({
        targetClass: 'com.a.X',
        changeType: 'ADD_FIELD',
        totalImpacted: 0,
        impactPaths: [],
        aiAnalysis: ''
      })
      wrapper.vm.impactForm.targetClass = 'com.a.X'
      bodyButton('开始分析').click()
      await flushPromises()

      const text = document.body.textContent
      expect(text).toContain('LOW')
      expect(text).toContain('未发现影响链路')
      expect(document.body.querySelector('.ai-analysis-box')).toBeNull()
    })

    it('doImpactSim_whenMediumImpact_shouldRenderOrangeMediumTag', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockResolvedValueOnce({
        targetClass: 'com.a.M', changeType: 'MODIFY_METHOD', totalImpacted: 7, impactPaths: [{ source: 'a', target: 'b', depth: 1 }]
      })
      wrapper.vm.impactForm.targetClass = 'com.a.M'
      bodyButton('开始分析').click()
      await flushPromises()

      const text = document.body.textContent
      expect(text).toContain('MEDIUM')
      expect(document.body.querySelector('.impact-paths')).not.toBeNull()
    })

    it('doImpactSim_whenCancelled_shouldReturnWithoutResult', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockResolvedValueOnce(AI_TASK_CANCELLED)
      wrapper.vm.impactForm.targetClass = 'com.a.C'
      bodyButton('开始分析').click()
      await flushPromises()

      expect(wrapper.vm.impactResult).toBeNull()
      expect(message.error).not.toHaveBeenCalled()
    })

    it('doImpactSim_whenErrorWithServerMessage_shouldShowReason', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockRejectedValueOnce({ response: { data: { message: '模型不可用' } } })
      wrapper.vm.impactForm.targetClass = 'com.a.E'
      bodyButton('开始分析').click()
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('影响分析失败: 模型不可用')
    })

    it('doImpactSim_whenErrorWithoutResponse_shouldShowNetworkFallback', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockRejectedValueOnce(new Error('boom'))
      wrapper.vm.impactForm.targetClass = 'com.a.E'
      bodyButton('开始分析').click()
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('影响分析失败: 网络错误')
    })

    it('doImpactSim_whenLoading_shouldRenderTaskProgressAndTerminate', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      wrapper.vm.impactForm.targetClass = 'com.a.L'
      bodyButton('开始分析').click()
      await flushPromises()

      expect(wrapper.vm.impactLoading).toBe(true)
      expect(bodyButton('分析中...')).toBeTruthy()
      expect(document.body.textContent).toContain('正在分析依赖影响链路…')
      expect(document.body.textContent).toContain('已等待 0 秒')

      activeTask.value = { id: 't1', label: '影响模拟', stage: '解析依赖链路', elapsedSec: 5 }
      await flushPromises()
      expect(document.body.textContent).toContain('解析依赖链路')
      expect(document.body.textContent).toContain('已等待 5 秒')

      bodyButton('终止').click()
      expect(cancelActiveAiTask).toHaveBeenCalledTimes(1)
    })

    it('requestCloseImpact_whenLoading_shouldConfirmBeforeClosing', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      wrapper.vm.impactForm.targetClass = 'com.a.L'
      bodyButton('开始分析').click()
      await flushPromises()

      wrapper.vm.requestCloseImpact()
      expect(Modal.confirm).toHaveBeenCalledTimes(1)
      expect(wrapper.vm.showImpactModal).toBe(true)
      expect(cancelActiveAiTask).not.toHaveBeenCalled()

      Modal.confirm.mock.calls.at(-1)[0].onOk()
      await flushPromises()
      expect(cancelActiveAiTask).toHaveBeenCalledTimes(1)
      expect(wrapper.vm.showImpactModal).toBe(false)
    })

    it('requestCloseImpact_whenIdle_shouldCloseAndResetResult', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)
      wrapper.vm.impactResult = { targetClass: 'x', changeType: 'y', totalImpacted: 0, impactPaths: [] }

      wrapper.vm.requestCloseImpact()
      await flushPromises()

      expect(Modal.confirm).not.toHaveBeenCalled()
      expect(wrapper.vm.showImpactModal).toBe(false)
      expect(wrapper.vm.impactResult).toBeNull()
    })

    it('impactResult_whenRestartClicked_shouldReturnToForm', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      runAiTask.mockResolvedValueOnce({ targetClass: 'com.a.R', changeType: 'MODIFY_METHOD', totalImpacted: 2, impactPaths: [] })
      wrapper.vm.impactForm.targetClass = 'com.a.R'
      bodyButton('开始分析').click()
      await flushPromises()

      bodyButton('重新分析').click()
      await flushPromises()

      expect(wrapper.vm.impactResult).toBeNull()
      expect(bodyButton('开始分析')).toBeTruthy()
    })

    it('restoreImpactHistory_whenSnapshotPresent_shouldRestoreFullForm', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      wrapper.vm.restoreImpactHistory(historyItem({
        verification: JSON.stringify({
          targetClass: 'com.a.Snap', changeType: 'REMOVE_CLASS', targetMethod: 'run',
          description: '删除类', totalImpacted: 9, impactPaths: [{ source: 'x', target: 'y', depth: 2 }]
        }),
        answer: '快照回答'
      }))

      expect(wrapper.vm.impactForm).toEqual({
        targetClass: 'com.a.Snap',
        changeType: 'REMOVE_CLASS',
        targetMethod: 'run',
        description: '删除类'
      })
      expect(wrapper.vm.impactResult.totalImpacted).toBe(9)
      expect(wrapper.vm.impactResult.impactPaths).toHaveLength(1)
      expect(wrapper.vm.impactResult.aiAnalysis).toBe('快照回答')
    })

    it('restoreImpactHistory_whenSnapshotMissingOrInvalid_shouldFallbackToContextFqnAndDefaults', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      wrapper.vm.restoreImpactHistory(historyItem({ verification: 'not-json', contextFqn: 'com.a.Fallback', answer: '' }))

      expect(wrapper.vm.impactForm.targetClass).toBe('com.a.Fallback')
      expect(wrapper.vm.impactForm.changeType).toBe('MODIFY_METHOD')
      expect(wrapper.vm.impactForm.targetMethod).toBe('')
      expect(wrapper.vm.impactForm.description).toBe('')
      expect(wrapper.vm.impactResult.totalImpacted).toBe(0)
      expect(wrapper.vm.impactResult.impactPaths).toEqual([])

      // 快照与 contextFqn 均缺失：目标类回落空串
      wrapper.vm.restoreImpactHistory({ verification: null, contextFqn: null, answer: null })
      expect(wrapper.vm.impactForm.targetClass).toBe('')
      expect(wrapper.vm.impactResult.aiAnalysis).toBe('')
    })

    it('impactHistory_whenItemsExist_shouldRenderListAndRestoreOnClick', async () => {
      mockApi({
        history: [
          historyItem({ id: 1, question: '第一个问题', createdAt: '2026-01-02T10:00:00' }),
          historyItem({ id: 2, question: '第二个问题', createdAt: null })
        ]
      })
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      // 展开折叠面板（v-model:activeKey 回调）
      wrapper.findAllComponents({ name: 'ACollapse' })[0].vm.$emit('update:activeKey', ['h'])
      await flushPromises()

      expect(document.body.textContent).toContain('历史记录（2）')
      expect(document.body.textContent).toContain('第一个问题')
      expect(document.body.textContent).toContain('第二个问题')

      const items = document.body.querySelectorAll('.history-item')
      expect(items).toHaveLength(2)
      items[0].click()
      await flushPromises()

      expect(wrapper.vm.impactForm.targetClass).toBe('com.example.OrderService')

      // 删除按钮（click.stop + 内联回调）触发删除并重载
      api.delete.mockResolvedValueOnce({ data: { data: {} } })
      items[1].querySelector('button').click()
      await flushPromises()

      expect(api.delete).toHaveBeenCalledWith('/projects/p1/chat-history/2')
    })

    it('impactHistory_whenEmpty_shouldRenderEmptyHint', async () => {
      const { wrapper } = await mountMap()
      await openImpactModal(wrapper)

      wrapper.findAllComponents({ name: 'ACollapse' })[0].vm.$emit('update:activeKey', ['h'])
      await flushPromises()

      expect(document.body.textContent).toContain('暂无历史记录')
    })

    it('loadImpactHistory_whenApiFails_shouldFallbackToEmpty', async () => {
      mockApi({ historyError: new Error('history down') })
      const { wrapper } = await mountMap()

      await wrapper.vm.loadImpactHistory()

      expect(wrapper.vm.impactHistory).toEqual([])
    })

    it('loadImpactHistory_whenDataMissing_shouldFallbackToEmpty', async () => {
      mockApi({ history: null })
      const { wrapper } = await mountMap()

      await wrapper.vm.loadImpactHistory()

      expect(wrapper.vm.impactHistory).toEqual([])
    })

    it('deleteHistory_whenSuccess_shouldRemoveAndReload', async () => {
      const { wrapper } = await mountMap()
      api.delete.mockResolvedValueOnce({ data: { data: {} } })

      const before = api.get.mock.calls.filter(call => call[0].endsWith('/chat-history')).length
      await wrapper.vm.deleteHistory(9, wrapper.vm.loadImpactHistory)

      expect(api.delete).toHaveBeenCalledWith('/projects/p1/chat-history/9')
      expect(api.get.mock.calls.filter(call => call[0].endsWith('/chat-history')).length).toBe(before + 1)
    })

    it('deleteHistory_whenApiFails_shouldShowReason', async () => {
      const { wrapper } = await mountMap()
      api.delete.mockRejectedValueOnce({ response: { data: { message: '无权限' } } })

      await wrapper.vm.deleteHistory(9, wrapper.vm.loadImpactHistory)

      expect(message.error).toHaveBeenCalledWith('删除失败: 无权限')
    })

    it('deleteHistory_whenApiFailsWithoutResponse_shouldShowNetworkFallback', async () => {
      const { wrapper } = await mountMap()
      api.delete.mockRejectedValueOnce(new Error('boom'))

      await wrapper.vm.deleteHistory(9, wrapper.vm.loadImpactHistory)

      expect(message.error).toHaveBeenCalledWith('删除失败: 网络错误')
    })

    it('formatHistoryTime_whenFalsyOrDate_shouldReturnEmptyOrFormatted', async () => {
      const { wrapper } = await mountMap()
      const vm = wrapper.vm

      expect(vm.formatHistoryTime(null)).toBe('')
      expect(vm.formatHistoryTime('')).toBe('')
      expect(vm.formatHistoryTime('2026-01-02T10:00:00')).toContain('10:00')
    })

    it('parseJson_whenNullOrInvalidOrValid_shouldParseOrReturnNull', async () => {
      const { wrapper } = await mountMap()
      const vm = wrapper.vm

      expect(vm.parseJson(null)).toBeNull()
      expect(vm.parseJson('')).toBeNull()
      expect(vm.parseJson('{bad')).toBeNull()
      expect(vm.parseJson('{"a":1}')).toEqual({ a: 1 })
    })

    it('shortName_whenFqnOrEmpty_shouldExtractLastSegment', async () => {
      const { wrapper } = await mountMap()
      const vm = wrapper.vm

      expect(vm.shortName('com.example.OrderService')).toBe('OrderService')
      expect(vm.shortName('OrderService')).toBe('OrderService')
      expect(vm.shortName('')).toBe('')
      expect(vm.shortName(null)).toBe('')
    })
  })

  describe('上下文问答', () => {
    it('openQaPanel_whenNoTarget_shouldShowGlobalModeAlert', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      expect(document.body.textContent).toContain('全局搜索模式')
      expect(document.body.textContent).toContain('AI 将自动搜索代码库中的相关类来回答问题')
      const textarea = document.body.querySelector('.ant-modal textarea')
      expect(textarea.placeholder).toContain('支付相关的代码有哪些')
    })

    it('openQaPanel_whenTargetSet_shouldShowClassInfoAndSwitchToGlobal', async () => {
      const { wrapper } = await mountMap()
      wrapper.vm.selectedNode = sampleNode()
      wrapper.vm.openQaPanel()
      await flushPromises()

      expect(wrapper.vm.qaTarget.id).toBe('com.example.OrderService')
      expect(document.body.textContent).toContain('上下文问答 — OrderService')
      expect(document.body.textContent).toContain('切换为全局搜索')

      bodyButton('切换为全局搜索').click()
      await flushPromises()

      expect(wrapper.vm.qaTarget).toBeNull()
      expect(wrapper.vm.qaAnswer).toBe('')
      expect(wrapper.vm.qaVerification).toBeNull()
    })

    it('doContextQa_whenQuestionEmpty_shouldWarnAndSkip', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      bodyButton('提问').click()
      await flushPromises()

      expect(message.warning).toHaveBeenCalledWith('请输入问题')
      expect(runAiTask).not.toHaveBeenCalled()
    })

    it('doContextQa_whenSuccessWithClassTarget_shouldCallAiWithClassFqn', async () => {
      const { wrapper } = await mountMap()
      wrapper.vm.selectedNode = sampleNode()
      wrapper.vm.openQaPanel()
      await flushPromises()

      let capturedOptions
      runAiTask.mockImplementation(async options => {
        capturedOptions = options
        return {
          answer: '**职责**：订单编排',
          verification: { unsourced: ['A'], relationMismatches: ['B'] }
        }
      })
      await typeQuestion('这个类做什么？')
      expect(wrapper.vm.qaQuestion).toBe('这个类做什么？')
      bodyButton('提问').click()
      await flushPromises()

      expect(capturedOptions.label).toBe('类问答 — OrderService')
      expect(wrapper.vm.qaAnswer).toContain('订单编排')
      expect(document.body.querySelector('.qa-answer-box').innerHTML).toContain('<strong>职责</strong>')
      // 校验徽标：2 项异常
      expect(document.body.textContent).toContain('证据校验：2 项异常')
      expect(document.body.querySelector('.qa-verify-line .ant-tag-orange')).not.toBeNull()
      expect(wrapper.vm.qaVerifyDetail).toBe('未溯源引用: A；关系声明存疑: B')

      const signal = new AbortController().signal
      api.post.mockResolvedValueOnce({ data: { data: { answer: 'x' } } })
      const result = await capturedOptions.execute({ taskId: 'ai-2', signal })
      expect(api.post).toHaveBeenCalledWith(
        '/projects/p1/ai/context-qa',
        { question: '这个类做什么？', classFqn: 'com.example.OrderService' },
        { headers: { 'X-Ai-Task-Id': 'ai-2' }, signal }
      )
      expect(result).toEqual({ answer: 'x' })
    })

    it('doContextQa_whenGlobalAndVerificationPassed_shouldRenderPassedBadge', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      let capturedOptions
      runAiTask.mockImplementation(async options => {
        capturedOptions = options
        return { answer: '全局回答', verification: { classRefsChecked: 3, relationClaimsChecked: 2 } }
      })
      wrapper.vm.qaQuestion = '支付相关代码？'
      bodyButton('提问').click()
      await flushPromises()

      expect(capturedOptions.label).toBe('全局问答')
      expect(document.body.textContent).toContain('证据校验通过')
      expect(document.body.querySelector('.qa-verify-line .ant-tag-green')).not.toBeNull()
      const tooltipTitle = document.body.querySelector('.qa-verify-line').textContent
      expect(tooltipTitle).toBeDefined()
      // 无类目标时 classFqn 为 null
      const signal = new AbortController().signal
      api.post.mockResolvedValueOnce({ data: { data: {} } })
      await capturedOptions.execute({ taskId: 'ai-3', signal })
      expect(api.post).toHaveBeenCalledWith(
        '/projects/p1/ai/context-qa',
        { question: '支付相关代码？', classFqn: null },
        { headers: { 'X-Ai-Task-Id': 'ai-3' }, signal }
      )
    })

    it('qaVerification_whenCorrected_shouldRenderCorrectedBadge', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockResolvedValueOnce({ answer: '修正后回答', verification: { corrected: true, unsourced: ['A'] } })
      wrapper.vm.qaQuestion = '问题'
      bodyButton('提问').click()
      await flushPromises()

      expect(document.body.textContent).toContain('证据校验：已自动修正')
      expect(document.body.querySelector('.qa-verify-line .ant-tag-blue')).not.toBeNull()
    })

    it('qaVerification_whenOnlyUnsourcedIssue_shouldRenderSinglePartDetail', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockResolvedValueOnce({ answer: 'a', verification: { unsourced: ['X'] } })
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()

      expect(wrapper.vm.qaVerifyDetail).toBe('未溯源引用: X')
      expect(wrapper.vm.qaVerifyText).toBe('证据校验：1 项异常')
    })

    it('qaVerification_whenAbsent_shouldHideVerifyLine', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockResolvedValueOnce({ answer: '无校验回答' })
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()

      expect(wrapper.vm.qaVerification).toBeNull()
      expect(wrapper.vm.qaVerifyText).toBe('')
      expect(wrapper.vm.qaVerifyColor).toBe('green')
      expect(wrapper.vm.qaVerifyDetail).toBe('')
      expect(document.body.querySelector('.qa-verify-line')).toBeNull()
    })

    it('doContextQa_whenAnswerMissing_shouldFallbackText', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockResolvedValueOnce(null)
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()

      expect(wrapper.vm.qaAnswer).toBe('未获取到回答')
    })

    it('doContextQa_whenCancelled_shouldReturnEarly', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockResolvedValueOnce(AI_TASK_CANCELLED)
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()

      expect(wrapper.vm.qaAnswer).toBe('')
      expect(message.error).not.toHaveBeenCalled()
    })

    it('doContextQa_whenError_shouldShowReasonOrNetworkFallback', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockRejectedValueOnce({ response: { data: { message: '超时' } } })
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()
      expect(message.error).toHaveBeenCalledWith('问答失败: 超时')

      runAiTask.mockRejectedValueOnce(new Error('boom'))
      wrapper.vm.qaQuestion = 'q2'
      bodyButton('提问').click()
      await flushPromises()
      expect(message.error).toHaveBeenCalledWith('问答失败: 网络错误')
    })

    it('doContextQa_whenLoading_shouldRenderProgressAndTerminate', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()

      expect(wrapper.vm.qaLoading).toBe(true)
      expect(bodyButton('AI 思考中...')).toBeTruthy()
      expect(document.body.textContent).toContain('AI 正在思考…')

      activeTask.value = { id: 't2', label: '全局问答', stage: '检索相关类', elapsedSec: 3 }
      await flushPromises()
      expect(document.body.textContent).toContain('检索相关类')
      expect(document.body.textContent).toContain('已等待 3 秒')

      // CJK 双字文案被 antd 插入空格（"终 止"），按去空白后比对
      const terminateButtons = [...document.body.querySelectorAll('button')]
        .filter(button => button.textContent.replace(/\s/g, '') === '终止')
      terminateButtons.at(-1).click()
      expect(cancelActiveAiTask).toHaveBeenCalled()
    })

    it('requestCloseQa_whenLoading_shouldConfirmBeforeClosing', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      wrapper.vm.qaQuestion = 'q'
      bodyButton('提问').click()
      await flushPromises()

      wrapper.vm.requestCloseQa()
      expect(Modal.confirm).toHaveBeenCalledTimes(1)
      expect(wrapper.vm.showQaModal).toBe(true)

      Modal.confirm.mock.calls.at(-1)[0].onOk()
      await flushPromises()
      expect(cancelActiveAiTask).toHaveBeenCalledTimes(1)
      expect(wrapper.vm.showQaModal).toBe(false)
    })

    it('requestCloseQa_whenIdle_shouldCloseDirectly', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      wrapper.vm.requestCloseQa()
      await flushPromises()

      expect(Modal.confirm).not.toHaveBeenCalled()
      expect(wrapper.vm.showQaModal).toBe(false)
    })

    it('qaHistory_whenItemsExist_shouldRenderTagsAndRestoreOnClick', async () => {
      mockApi({
        historyByScope: {
          GLOBAL: [
            historyItem({
              id: 3, question: '全局问题', contextFqn: null,
              answer: '全局答案', verification: JSON.stringify({ corrected: true })
            }),
            historyItem({ id: 4, question: '带类问题', contextFqn: 'com.a.B', verification: 'bad-json' })
          ]
        }
      })
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)
      wrapper.findAllComponents({ name: 'ACollapse' })[0].vm.$emit('update:activeKey', ['h'])
      await flushPromises()

      expect(document.body.textContent).toContain('历史记录（2）')
      expect(document.body.textContent).toContain('全局问题')
      expect(document.body.textContent).toContain('带类问题')
      // contextFqn 存在时渲染短名标签
      expect(document.body.querySelectorAll('.history-question .ant-tag-purple')).toHaveLength(1)

      const items = document.body.querySelectorAll('.history-item')
      items[0].click()
      await flushPromises()
      expect(wrapper.vm.qaQuestion).toBe('全局问题')
      expect(wrapper.vm.qaAnswer).toBe('全局答案')
      expect(wrapper.vm.qaVerification).toEqual({ corrected: true })

      // 非法 verification：解析失败回落 null
      items[1].click()
      await flushPromises()
      expect(wrapper.vm.qaVerification).toBeNull()
    })

    it('restoreQaHistory_whenFieldsMissing_shouldFallbackToEmptyStrings', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)

      wrapper.vm.restoreQaHistory({ question: null, answer: null, verification: null })

      expect(wrapper.vm.qaQuestion).toBe('')
      expect(wrapper.vm.qaAnswer).toBe('')
      expect(wrapper.vm.qaVerification).toBeNull()
    })

    it('qaHistory_whenDeleteClicked_shouldReloadWithoutRestore', async () => {
      mockApi({ history: [historyItem({ id: 8, question: '待删除' })] })
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)
      wrapper.findAllComponents({ name: 'ACollapse' })[0].vm.$emit('update:activeKey', ['h'])
      await flushPromises()

      api.delete.mockResolvedValueOnce({ data: { data: {} } })
      const before = api.get.mock.calls.filter(call => call[0].endsWith('/chat-history')).length

      document.body.querySelector('.history-item button').click()
      await flushPromises()

      expect(api.delete).toHaveBeenCalledWith('/projects/p1/chat-history/8')
      // click.stop：未触发 restore，问题保持为空
      expect(wrapper.vm.qaQuestion).toBe('')
      expect(api.get.mock.calls.filter(call => call[0].endsWith('/chat-history')).length).toBe(before + 1)
    })

    it('qaHistory_whenEmpty_shouldRenderEmptyHint', async () => {
      const { wrapper } = await mountMap()
      await openQaModal(wrapper)
      wrapper.findAllComponents({ name: 'ACollapse' })[0].vm.$emit('update:activeKey', ['h'])
      await flushPromises()

      expect(document.body.textContent).toContain('暂无历史记录')
    })

    it('loadQaHistory_whenApiFails_shouldFallbackToEmpty', async () => {
      mockApi({ historyError: new Error('down') })
      const { wrapper } = await mountMap()

      await wrapper.vm.loadQaHistory()

      expect(wrapper.vm.qaHistory).toEqual([])
    })

    it('loadQaHistory_whenDataMissing_shouldFallbackToEmpty', async () => {
      api.get.mockImplementation(url => {
        if (url.endsWith('/scans/status')) return Promise.resolve({ data: { data: { scanStatus: 'COMPLETED' } } })
        return Promise.resolve({ data: { data: null } })
      })
      const { wrapper } = await mountMap()

      await wrapper.vm.loadQaHistory()

      expect(wrapper.vm.qaHistory).toEqual([])
    })

    it('renderSimpleMd_whenEmptyOrContent_shouldReturnEmptyOrSanitizedHtml', async () => {
      const { wrapper } = await mountMap()
      const vm = wrapper.vm

      expect(vm.renderSimpleMd('')).toBe('')
      expect(vm.renderSimpleMd(null)).toBe('')
      expect(vm.renderSimpleMd('# 标题')).toContain('<h1')
      expect(vm.renderSimpleMd('<script>alert(1)</script>')).not.toContain('<script')
    })

    it('onSearch_whenCalled_shouldBeNoop', async () => {
      const { wrapper } = await mountMap()

      expect(() => wrapper.vm.onSearch('anything')).not.toThrow()
    })
  })
})
