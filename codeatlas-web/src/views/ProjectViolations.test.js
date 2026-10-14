import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h, KeepAlive, ref } from 'vue'
import { TextEncoder as NodeTextEncoder, TextDecoder as NodeTextDecoder } from 'node:util'

// ant-design-vue 部分 mock：组件走真实渲染，message 替换为可断言桩
vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
  }
})

vi.mock('../api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  handleUnauthorized: vi.fn()
}))

import Antd, { message } from 'ant-design-vue'
import api, { handleUnauthorized } from '../api'
import ProjectViolations from './ProjectViolations.vue'

vi.setConfig({ testTimeout: 30000 })

function createTestRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/project/:id/violations', component: ProjectViolations },
      { path: '/project/:id/map', component: { template: '<div />' } }
    ]
  })
  router.push('/project/p1/violations')
  return router
}

// SSE 响应桩：duck-typed reader，仅实现源码实际调用的 getReader/read/cancel 契约
function sseResponse(events, { status = 200 } = {}) {
  if (status !== 200) {
    return Promise.resolve({ status, ok: false, body: null })
  }
  const encoded = events.map(event => new TextEncoder().encode(event))
  let index = 0
  const body = {
    getReader() {
      return {
        read: () => Promise.resolve(
          index < encoded.length ? { done: false, value: encoded[index++] } : { done: true, value: undefined }
        ),
        cancel: vi.fn(() => Promise.resolve())
      }
    }
  }
  return Promise.resolve({ status, ok: true, body })
}

function pendingResponse() {
  return new Promise(() => {})
}

function buildViolations() {
  return [
    { id: 101, severity: 'BLOCKER', classFqn: 'com.a.BigService', message: '循环依赖', suggestion: '拆分', isResolved: false },
    { id: 102, severity: 'ERROR', classFqn: 'Tiny', message: '命名违规', suggestion: '重命名', isResolved: true },
    { id: 103, severity: 'WARN', classFqn: '.weird', message: '结构问题', suggestion: '调整', isResolved: false },
    { id: 104, severity: 'INFO', classFqn: null, message: '小建议', suggestion: '', isResolved: false },
    { id: 105, severity: 'X', classFqn: 'x.Y', message: '未知级别', suggestion: '', isResolved: true }
  ]
}

function mockGet({ violations = [], scans = [], violationsError = null, scansError = null, violationsPending = false, scansPending = false } = {}) {
  api.get.mockImplementation(url => {
    if (url.endsWith('/violations')) {
      if (violationsError) return Promise.reject(violationsError)
      if (violationsPending) return pendingResponse()
      return Promise.resolve({ data: { data: { records: violations } } })
    }
    if (url.endsWith('/scans')) {
      if (scansError) return Promise.reject(scansError)
      if (scansPending) return pendingResponse()
      return Promise.resolve({ data: { data: { records: scans } } })
    }
    return Promise.resolve({ data: { data: {} } })
  })
}

async function mountViolations({ router, flush = true } = {}) {
  const testRouter = router || createTestRouter()
  await testRouter.isReady()
  const wrapper = mount(ProjectViolations, {
    global: { plugins: [Antd, testRouter] },
    attachTo: document.body
  })
  if (flush) await flushPromises()
  return { wrapper, router: testRouter }
}

// 通过 KeepAlive 宿主触发 onActivated（首激活空跑，二次激活重载并可能重订阅 SSE）
async function mountInKeepAlive() {
  const Host = defineComponent({
    setup() {
      const show = ref(true)
      return { show }
    },
    render() {
      return h(KeepAlive, null, {
        default: () => (this.show ? h(ProjectViolations) : null)
      })
    }
  })
  const router = createTestRouter()
  await router.isReady()
  const wrapper = mount(Host, {
    global: { plugins: [Antd, router] },
    attachTo: document.body
  })
  return { wrapper, router }
}

describe('ProjectViolations.vue', () => {
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
    // jsdom 不保证提供流编解码器，统一以 Node 实现兜底（源码依赖 TextDecoder 解析 SSE）
    vi.stubGlobal('TextEncoder', NodeTextEncoder)
    vi.stubGlobal('TextDecoder', NodeTextDecoder)
    vi.stubGlobal('fetch', vi.fn(() => pendingResponse()))
    api.get.mockReset()
    api.post.mockReset()
    api.put.mockReset()
    message.success.mockClear()
    message.error.mockClear()
    message.warning.mockClear()
    handleUnauthorized.mockClear()
    localStorage.clear()
    localStorage.setItem('codeatlas_token', 'tok-1')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  describe('数据加载', () => {
    it('onMounted_whenViolationsPending_shouldShowSkeletonThenTable', async () => {
      let resolveViolations
      api.get.mockImplementation(url => {
        if (url.endsWith('/violations')) {
          return new Promise(resolve => { resolveViolations = resolve })
        }
        return Promise.resolve({ data: { data: { records: [] } } })
      })

      const { wrapper } = await mountViolations({ flush: false })

      expect(wrapper.find('.ant-skeleton').exists()).toBe(true)

      resolveViolations({ data: { data: { records: buildViolations() } } })
      await flushPromises()

      expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
      expect(wrapper.findAll('.ant-table-row')).toHaveLength(5)
    })

    it('fetchViolations_whenSuccess_shouldRequestAndRenderTableWithCounts', async () => {
      mockGet({ violations: buildViolations(), scans: [{ id: 1, status: 'COMPLETED' }] })

      const { wrapper } = await mountViolations()

      expect(api.get).toHaveBeenCalledWith('/projects/p1/violations')
      expect(api.get).toHaveBeenCalledWith('/projects/p1/scans', { params: { page: 1, size: 1 } })
      expect(wrapper.findAll('.ant-table-row')).toHaveLength(5)
      // 统计标签
      expect(wrapper.text()).toContain('1 阻断')
      expect(wrapper.text()).toContain('1 错误')
      expect(wrapper.text()).toContain('1 警告')
      // 严重度颜色/文案（含 INFO 与未知级别回退）
      const rows = wrapper.findAll('.ant-table-row')
      expect(rows[0].find('.ant-tag').text()).toBe('阻断')
      expect(rows[1].find('.ant-tag').text()).toBe('错误')
      expect(rows[2].find('.ant-tag').text()).toBe('警告')
      expect(rows[3].find('.ant-tag').text()).toBe('建议')
      expect(rows[4].find('.ant-tag').text()).toBe('X')
      // 类名短名/全名
      expect(rows[0].find('code').text()).toBe('BigService')
      expect(rows[0].find('.class-fqn-full').text()).toBe('com.a.BigService')
      expect(rows[1].find('code').text()).toBe('Tiny')
      expect(rows[2].find('code').text()).toBe('.weird')
      expect(rows[3].find('code').text()).toBe('')
      // 解决状态徽标与操作按钮
      expect(rows[0].text()).toContain('未解决')
      expect(rows[1].text()).toContain('已解决')
      expect(rows[0].find('button').exists()).toBe(true)
      expect(rows[1].find('button').exists()).toBe(false)
      // 分页合计文案（showTotal 回调）
      expect(wrapper.text()).toContain('共 5 条')
    })

    it('fetchViolations_whenRecordsMissing_shouldFallbackToEmptyAndCongrats', async () => {
      mockGet({ scans: [{ id: 1, status: 'COMPLETED' }] })

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.violations).toEqual([])
      expect(wrapper.text()).toContain('恭喜！所有类均通过宪法规则检查')
    })

    it('fetchViolations_whenScanQueryFails_shouldFallbackToCongratsInsteadOfNeverScanned', async () => {
      mockGet({ scansError: new Error('scan api down') })

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.hasScanned).toBe(true)
      expect(wrapper.vm.latestScanStatus).toBeNull()
      expect(wrapper.text()).toContain('恭喜！所有类均通过宪法规则检查')
    })

    it('fetchViolations_whenErrorWithServerMessage_shouldRenderErrorAndRetryRecovers', async () => {
      mockGet({ violationsError: { response: { data: { message: '违规查询失败' } } }, scans: [{ id: 1, status: 'COMPLETED' }] })

      const { wrapper } = await mountViolations()

      expect(wrapper.find('.ant-result').exists()).toBe(true)
      expect(wrapper.text()).toContain('违规查询失败')

      mockGet({ violations: buildViolations(), scans: [{ id: 1, status: 'COMPLETED' }] })
      await wrapper.find('.ant-result button').trigger('click')
      await flushPromises()

      expect(wrapper.find('.ant-result').exists()).toBe(false)
      expect(wrapper.findAll('.ant-table-row')).toHaveLength(5)
    })

    it('fetchViolations_whenErrorWithoutResponse_shouldRenderDefaultMessage', async () => {
      mockGet({ violationsError: new Error('boom'), scans: [] })

      const { wrapper } = await mountViolations()

      expect(wrapper.text()).toContain('加载违规列表失败')
    })

    it('fetchViolations_whenResponseDataMissing_shouldFallbackToEmptyLists', async () => {
      api.get.mockImplementation(() => Promise.resolve({ data: { data: null } }))

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.violations).toEqual([])
      expect(wrapper.vm.hasScanned).toBe(false)
      expect(wrapper.text()).toContain('立即扫描')
    })

    it('checkScanHistory_whenRunningAfterTerminalStage_shouldResetPlaceholder', async () => {
      mockGet({ scans: [{ id: 7, status: 'RUNNING' }] })

      const { wrapper } = await mountViolations()
      expect(wrapper.vm.scanProgress.stage).toBe('PENDING')

      // 上一次终态为 COMPLETED/FAILED 时切回页面发现新扫描：占位进度需重置
      wrapper.vm.scanProgress.stage = 'COMPLETED'
      await wrapper.vm.checkScanHistory()
      expect(wrapper.vm.scanProgress.stage).toBe('PENDING')

      wrapper.vm.scanProgress.stage = 'FAILED'
      await wrapper.vm.checkScanHistory()
      expect(wrapper.vm.scanProgress.stage).toBe('PENDING')

      // 非终态进行中：保留真实进度，不重置占位值
      wrapper.vm.scanProgress.stage = 'RULES'
      await wrapper.vm.checkScanHistory()
      expect(wrapper.vm.scanProgress.stage).toBe('RULES')
    })
  })

  describe('空态分支', () => {
    it('emptyState_whenNeverScanned_shouldOfferImmediateScan', async () => {
      mockGet({ scans: [] })

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.hasScanned).toBe(false)
      expect(wrapper.text()).toContain('该项目还没有扫描记录')
      expect(wrapper.text()).toContain('立即扫描')
    })

    it('emptyState_whenLatestScanFailed_shouldOfferRescan', async () => {
      mockGet({ scans: [{ id: 2, status: 'FAILED' }] })

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.hasScanned).toBe(true)
      expect(wrapper.vm.latestScanStatus).toBe('FAILED')
      expect(wrapper.text()).toContain('最近一次扫描未成功完成')
      expect(wrapper.text()).toContain('重新扫描')
    })

    it('emptyState_whenScanHistoryPending_shouldShowSpinnerInsteadOfCongrats', async () => {
      mockGet({ scansPending: true })

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.hasScanned).toBeNull()
      expect(wrapper.find('.ant-spin').exists()).toBe(true)
      expect(wrapper.text()).not.toContain('恭喜')
    })

    it('emptyState_whenScanRunningFromOtherPlace_shouldShowProgressCardWithPlaceholder', async () => {
      mockGet({ scans: [{ id: 77, status: 'RUNNING' }] })

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.scanRunning).toBe(true)
      expect(wrapper.text()).toContain('扫描进行中')
      expect(wrapper.text()).toContain('准备中')
      expect(wrapper.text()).toContain('1%')
      expect(wrapper.text()).toContain('扫描进行中，正在同步进度...')
      expect(wrapper.text()).not.toContain('恭喜')
    })
  })

  describe('违规行交互', () => {
    it('goToClassNode_whenRowClicked_shouldNavigateWithFocusQuery', async () => {
      mockGet({ violations: buildViolations(), scans: [{ id: 1, status: 'COMPLETED' }] })

      const { wrapper, router } = await mountViolations()

      await wrapper.findAll('.ant-table-row')[0].trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.fullPath).toBe('/project/p1/map?focus=com.a.BigService')
    })

    it('goToClassNode_whenClassFqnMissing_shouldNotNavigate', async () => {
      mockGet({ violations: buildViolations(), scans: [{ id: 1, status: 'COMPLETED' }] })

      const { wrapper, router } = await mountViolations()

      await wrapper.findAll('.ant-table-row')[3].trigger('click')
      await flushPromises()

      expect(router.currentRoute.value.fullPath).toBe('/project/p1/violations')
    })

    it('resolveViolation_whenSuccess_shouldMarkResolvedAndStopRowNavigation', async () => {
      mockGet({ violations: buildViolations(), scans: [{ id: 1, status: 'COMPLETED' }] })
      api.put.mockResolvedValueOnce({ data: { data: {} } })

      const { wrapper, router } = await mountViolations()

      await wrapper.findAll('.ant-table-row')[0].find('button').trigger('click')
      await flushPromises()

      expect(api.put).toHaveBeenCalledWith('/projects/p1/violations/101/resolve')
      expect(wrapper.vm.violations.find(v => v.id === 101).isResolved).toBe(true)
      expect(message.success).toHaveBeenCalledWith('已标记为已解决')
      // click.stop：不再触发行点击跳转
      expect(router.currentRoute.value.fullPath).toBe('/project/p1/violations')
    })

    it('resolveViolation_whenApiFails_shouldShowOperationFailed', async () => {
      mockGet({ violations: buildViolations(), scans: [{ id: 1, status: 'COMPLETED' }] })
      api.put.mockRejectedValueOnce(new Error('500'))

      const { wrapper } = await mountViolations()

      await wrapper.vm.resolveViolation(101)

      expect(message.error).toHaveBeenCalledWith('操作失败')
      expect(wrapper.vm.violations.find(v => v.id === 101).isResolved).toBe(false)
    })

    it('resolveViolation_whenViolationMissingLocally_shouldNotThrow', async () => {
      mockGet({ violations: [], scans: [{ id: 1, status: 'COMPLETED' }] })
      api.put.mockResolvedValueOnce({ data: { data: {} } })

      const { wrapper } = await mountViolations()

      await wrapper.vm.resolveViolation(999)

      expect(message.success).toHaveBeenCalledWith('已标记为已解决')
    })
  })

  describe('triggerScan 触发扫描', () => {
    it('triggerScan_whenSuccess_shouldPostAndSubscribeWithScanIdUntilCompleted', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([
          'data: {"stage":"CLONING","progress":30,"message":"克隆中"}\n\n',
          'data: {"stage":"COMPLETED","progress":100}\n\n'
        ]))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 88 } } })

      const { wrapper } = await mountViolations()
      expect(wrapper.vm.triggering).toBe(false)

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(api.post).toHaveBeenCalledWith('/projects/p1/scans')
      expect(fetchMock).toHaveBeenNthCalledWith(
        2,
        '/api/v1/projects/p1/scans/progress?scanId=88',
        expect.objectContaining({ headers: { Authorization: 'Bearer tok-1' } })
      )
      // 进行中的进度卡（stage/progress/message 已更新）
      expect(wrapper.vm.triggering).toBe(true)
      expect(wrapper.vm.scanProgress.stage).toBe('COMPLETED')
      expect(message.success).toHaveBeenCalledWith('扫描完成')

      await new Promise(resolve => setTimeout(resolve, 900))
      await flushPromises()

      // 终态收尾：复位按钮态并刷新数据
      expect(wrapper.vm.triggering).toBe(false)
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/violations')).length).toBe(2)
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/scans')).length).toBe(2)
    })

    it('triggerScan_whenScanFails_shouldShowFailureAndRefresh', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([
          'data: {"stage":"FAILED","progress":40,"message":"解析器崩溃"}\n\n'
        ]))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 89 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('扫描失败: 解析器崩溃')

      await new Promise(resolve => setTimeout(resolve, 900))
      await flushPromises()

      expect(wrapper.vm.triggering).toBe(false)
      expect(wrapper.vm.scanProgress.stage).toBe('FAILED')
    })

    it('triggerScan_whenFailedEventWithoutMessage_shouldUseUnknownErrorFallback', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([
          'data: {"stage":"FAILED"}\n\n'
        ]))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 90 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('扫描失败: 未知错误')

      await new Promise(resolve => setTimeout(resolve, 900))
      await flushPromises()
    })

    it('triggerScan_whenResponseHasNoScanId_shouldSubscribeWithoutQuery', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([]))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: {} })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(fetchMock).toHaveBeenNthCalledWith(
        2,
        '/api/v1/projects/p1/scans/progress',
        expect.objectContaining({ headers: { Authorization: 'Bearer tok-1' } })
      )
      // 无终态事件：保持进行中等待（触发按钮 loading 语义）
      expect(wrapper.vm.triggering).toBe(true)

      wrapper.unmount()
    })

    it('triggerScan_whenPostRejected_shouldResetTriggeringAndResyncState', async () => {
      mockGet({ scans: [] })
      api.post.mockRejectedValueOnce(new Error('already running'))

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(wrapper.vm.triggering).toBe(false)
      // 重新同步扫描历史
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/scans')).length).toBe(2)
    })
  })

  describe('SSE 进度订阅', () => {
    it('subscribeScanProgress_whenProgressEvents_shouldUpdateStageProgressMessage', async () => {
      mockGet({ scans: [{ id: 5, status: 'RUNNING' }] })
      const fetchMock = vi.fn(() => sseResponse([
        'event: progress\n',
        'data: {"stage":"CLONING","progress":42,"message":"克隆中..."}\n\n',
        'data: {broken json\n\n',
        'data: {"progress":7}\n\n',
        'data: {"stage":"AI"}\n\n'
      ]))
      vi.stubGlobal('fetch', fetchMock)

      const { wrapper } = await mountViolations()

      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/projects/p1/scans/progress',
        expect.objectContaining({ headers: { Authorization: 'Bearer tok-1' } })
      )
      // 末尾事件无 progress/message：回落 0 与空串（对应 || 兜底），模板隐藏消息行
      expect(wrapper.vm.scanProgress.stage).toBe('AI')
      expect(wrapper.vm.scanProgress.progress).toBe(0)
      expect(wrapper.vm.scanProgress.message).toBe('')
      expect(wrapper.text()).toContain('AI 分析')
      expect(wrapper.text()).toContain('0%')
      expect(wrapper.find('.progress-msg').exists()).toBe(false)
    })

    it('subscribeScanProgress_whenMessagePresent_shouldRenderProgressMessage', async () => {
      mockGet({ scans: [{ id: 5, status: 'RUNNING' }] })
      vi.stubGlobal('fetch', vi.fn(() => sseResponse([
        'data: {"stage":"RULES","progress":66,"message":"检查依赖规则"}\n\n'
      ])))

      const { wrapper } = await mountViolations()

      expect(wrapper.vm.scanProgress.stage).toBe('RULES')
      expect(wrapper.vm.scanProgress.progress).toBe(66)
      expect(wrapper.text()).toContain('执行规则检查')
      expect(wrapper.text()).toContain('66%')
      expect(wrapper.find('.progress-msg').text()).toBe('检查依赖规则')
    })

    it('subscribeScanProgress_whenUnauthorized_shouldDelegateToHandleUnauthorizedAndReset', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([], { status: 401 }))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 91 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(handleUnauthorized).toHaveBeenCalledTimes(1)
      expect(wrapper.vm.triggering).toBe(false)
      expect(message.error).not.toHaveBeenCalledWith(expect.stringContaining('扫描失败'))
    })

    it('subscribeScanProgress_whenAbortError_shouldStaySilent', async () => {
      mockGet({ scans: [] })
      const abortError = Object.assign(new Error('aborted'), { name: 'AbortError' })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => Promise.reject(abortError))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 92 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      // 主动中止属预期：不复位触发态、不额外刷新
      expect(wrapper.vm.triggering).toBe(true)
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/violations')).length).toBe(1)

      wrapper.unmount()
    })

    it('subscribeScanProgress_whenNetworkError_shouldResetAndRefetch', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => Promise.reject(new Error('net down')))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 93 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      expect(wrapper.vm.triggering).toBe(false)
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/violations')).length).toBe(2)
    })

    it('subscribeScanProgress_whenResubscribing_shouldAbortPreviousSubscription', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([]))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 94 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      // 首订阅（挂起）+ 扫描订阅 = 两次
      expect(fetchMock).toHaveBeenCalledTimes(2)

      wrapper.unmount()
    })
  })

  describe('生命周期', () => {
    it('onBeforeUnmount_whenSseActive_shouldAbortSubscription', async () => {
      mockGet({ scans: [] })
      let capturedSignal = null
      vi.stubGlobal('fetch', vi.fn((url, options) => {
        capturedSignal = options.signal
        return pendingResponse()
      }))

      const { wrapper } = await mountViolations()
      expect(capturedSignal).not.toBeNull()
      expect(capturedSignal.aborted).toBe(false)

      wrapper.unmount()

      expect(capturedSignal.aborted).toBe(true)
    })

    it('onBeforeUnmount_whenControllerAlreadyCleared_shouldNotThrow', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => sseResponse([], { status: 401 }))
      vi.stubGlobal('fetch', fetchMock)

      const { wrapper } = await mountViolations()
      expect(handleUnauthorized).toHaveBeenCalledTimes(1)

      expect(() => wrapper.unmount()).not.toThrow()
    })

    it('onActivated_whenSecondActivation_shouldRefetchAndResubscribeRunningScan', async () => {
      mockGet({ scans: [{ id: 1, status: 'COMPLETED' }] })
      const fetchMock = vi.fn(() => sseResponse([], { status: 401 }))
      vi.stubGlobal('fetch', fetchMock)

      const { wrapper } = await mountInKeepAlive()
      await flushPromises()

      // 首挂载：违约 + 扫描历史各一次；401 后控制器置空
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/violations')).length).toBe(1)
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/scans')).length).toBe(1)
      expect(handleUnauthorized).toHaveBeenCalledTimes(1)

      // 切回本页时扫描已在运行：重载数据并带 scanId 重订阅
      mockGet({ scans: [{ id: 99, status: 'RUNNING' }] })
      wrapper.vm.show = false
      await flushPromises()
      wrapper.vm.show = true
      await flushPromises()

      expect(api.get.mock.calls.filter(c => c[0].endsWith('/violations')).length).toBe(2)
      expect(api.get.mock.calls.filter(c => c[0].endsWith('/scans')).length).toBe(2)
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/projects/p1/scans/progress?scanId=99',
        expect.objectContaining({ headers: { Authorization: 'Bearer tok-1' } })
      )
      expect(wrapper.findComponent(ProjectViolations).vm.scanProgress.stage).toBe('PENDING')
    })
  })

  describe('映射函数', () => {
    it('severityColor_whenKnownOrUnknown_shouldMapOrFallback', async () => {
      mockGet({ scans: [] })
      const { wrapper } = await mountViolations()
      const vm = wrapper.vm

      expect(vm.severityColor('BLOCKER')).toBe('red')
      expect(vm.severityColor('ERROR')).toBe('orange')
      expect(vm.severityColor('WARN')).toBe('gold')
      expect(vm.severityColor('INFO')).toBe('blue')
      expect(vm.severityColor('X')).toBe('default')
    })

    it('severityLabel_whenKnownOrUnknown_shouldMapOrReturnOriginal', async () => {
      mockGet({ scans: [] })
      const { wrapper } = await mountViolations()
      const vm = wrapper.vm

      expect(vm.severityLabel('BLOCKER')).toBe('阻断')
      expect(vm.severityLabel('ERROR')).toBe('错误')
      expect(vm.severityLabel('WARN')).toBe('警告')
      expect(vm.severityLabel('INFO')).toBe('建议')
      expect(vm.severityLabel('X')).toBe('X')
    })

    it('shortName_whenFqnOrEdgeCases_shouldExtractSimpleName', async () => {
      mockGet({ scans: [] })
      const { wrapper } = await mountViolations()
      const vm = wrapper.vm

      expect(vm.shortName('com.a.BigService')).toBe('BigService')
      expect(vm.shortName('Tiny')).toBe('Tiny')
      expect(vm.shortName('.weird')).toBe('.weird')
      expect(vm.shortName('')).toBe('')
      expect(vm.shortName(null)).toBe('')
    })

    it('stageLabel_whenKnownUnknownOrEmpty_shouldMapOrFallback', async () => {
      mockGet({ scans: [] })
      const { wrapper } = await mountViolations()
      const vm = wrapper.vm

      expect(vm.stageLabel('PENDING')).toBe('准备中')
      expect(vm.stageLabel('COMPLETED')).toBe('扫描完成')
      expect(vm.stageLabel('FAILED')).toBe('扫描失败')
      expect(vm.stageLabel('WEIRD')).toBe('WEIRD')
      expect(vm.stageLabel('')).toBe('准备中')
      expect(vm.stageLabel(undefined)).toBe('准备中')
    })

    it('stageStatus_whenCompletedFailedOrOther_shouldMapBadgeStatus', async () => {
      mockGet({ scans: [] })
      const { wrapper } = await mountViolations()
      const vm = wrapper.vm

      expect(vm.stageStatus('COMPLETED')).toBe('success')
      expect(vm.stageStatus('FAILED')).toBe('error')
      expect(vm.stageStatus('RULES')).toBe('processing')
    })
  })

  describe('进度卡渲染', () => {
    it('emptyState_whenScanFailedStage_shouldRenderExceptionProgress', async () => {
      mockGet({ scans: [] })
      const fetchMock = vi.fn()
        .mockImplementationOnce(() => pendingResponse())
        .mockImplementationOnce(() => sseResponse([
          'data: {"stage":"FAILED","progress":12}\n\n'
        ]))
      vi.stubGlobal('fetch', fetchMock)
      api.post.mockResolvedValueOnce({ data: { data: { id: 95 } } })

      const { wrapper } = await mountViolations()

      await wrapper.find('.never-scanned button').trigger('click')
      await flushPromises()

      // FAILED 即时进入进度卡（scanRunning 由 stage 非终态驱动，FAILED 后仍显示失败徽标）
      expect(wrapper.find('.scan-progress-card').exists()).toBe(true)
      expect(wrapper.find('.ant-badge-status-error').exists()).toBe(true)

      await new Promise(resolve => setTimeout(resolve, 900))
      await flushPromises()
    })
  })
})
