import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, KeepAlive, ref } from 'vue'

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

import Antd, { message } from 'ant-design-vue'
import api, { handleUnauthorized } from '../api'
import { runAiTask, cancelActiveAiTask, activeTask, AI_TASK_CANCELLED } from '../composables/useAiTask'
import ReportPanel from './ReportPanel.vue'

vi.setConfig({ testTimeout: 30000 })

// a-dropdown 的 overlay 默认懒渲染（未展开不挂载），替换为透传桩保证
// 导出菜单模板始终渲染，从而可断言 handleExport 接线
const DropdownPassthrough = defineComponent({
  name: 'ADropdownStub',
  setup(_, { slots }) {
    return () => h('div', { class: 'dropdown-stub' }, [slots.default?.(), slots.overlay?.()])
  }
})

function reportRecord(overrides = {}) {
  return {
    id: 9,
    title: '架构报告',
    content: '# 大标题\n\n正文 **加粗**',
    createdAt: '2026-01-02T10:00:00',
    confidence: 0.9,
    ...overrides
  }
}

function mountReport(propsOverrides = {}, mountOptions = {}) {
  const props = {
    projectId: 'p1',
    type: 'ARCHITECTURE',
    kind: 'architecture-report',
    title: '架构解读',
    description: '报告说明文案',
    ...propsOverrides
  }
  return mount(ReportPanel, {
    props,
    global: { plugins: [Antd], stubs: { ADropdown: DropdownPassthrough }, ...(mountOptions.global || {}) },
    attachTo: document.body
  })
}

// 通过 KeepAlive 宿主触发 onActivated（首激活空跑，二次激活重载）
function mountInKeepAlive(propsOverrides = {}) {
  const props = {
    projectId: 'p1',
    type: 'ARCHITECTURE',
    kind: 'architecture-report',
    title: '架构解读',
    description: '报告说明文案',
    ...propsOverrides
  }
  const Host = defineComponent({
    setup() {
      const show = ref(true)
      return { show }
    },
    render() {
      return h(KeepAlive, null, {
        default: () => (this.show ? h(ReportPanel, props) : null)
      })
    }
  })
  return mount(Host, {
    global: { plugins: [Antd], stubs: { ADropdown: DropdownPassthrough } },
    attachTo: document.body
  })
}

function mockInsights(records) {
  return api.get.mockImplementation(url => {
    if (url.endsWith('/insights')) {
      return Promise.resolve({ data: { data: { records } } })
    }
    return Promise.resolve({ data: { data: {} } })
  })
}

describe('ReportPanel.vue', () => {
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
    Object.defineProperty(window.URL, 'createObjectURL', {
      configurable: true, writable: true, value: vi.fn(() => 'blob:mock-url')
    })
    Object.defineProperty(window.URL, 'revokeObjectURL', {
      configurable: true, writable: true, value: vi.fn()
    })
    api.get.mockReset()
    api.post.mockReset()
    runAiTask.mockReset()
    cancelActiveAiTask.mockReset()
    activeTask.value = null
    message.success.mockClear()
    message.error.mockClear()
    message.warning.mockClear()
    handleUnauthorized.mockClear()
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  describe('fetchReport 加载', () => {
    it('fetchReport_whenRecordsExist_shouldRenderReportWithMarkdownAndConfidence', async () => {
      mockInsights([reportRecord()])

      const wrapper = mountReport()
      await flushPromises()

      expect(api.get).toHaveBeenCalledWith('/projects/p1/insights', { params: { type: 'ARCHITECTURE', page: 1, size: 1 } })
      expect(wrapper.find('.markdown-body').exists()).toBe(true)
      expect(wrapper.find('.markdown-body').html()).toContain('<h1')
      expect(wrapper.find('.markdown-body').html()).toContain('<strong>加粗</strong>')
      // 页头标题取报告标题，副标题含生成时间
      expect(wrapper.text()).toContain('架构报告')
      expect(wrapper.text()).toContain('AI 生成 · ')
      expect(wrapper.text()).toContain('2026')
      expect(wrapper.text()).toContain('架构解读')
      expect(wrapper.text()).toContain('置信度 90%')
      expect(wrapper.text()).toContain('报告说明文案')
      // 有报告时导出与重新生成按钮可用
      const exportBtn = wrapper.findAll('button').find(b => b.text().includes('导出'))
      expect(exportBtn.attributes('disabled')).toBeUndefined()
      expect(wrapper.find('.report-empty').exists()).toBe(false)
    })

    it('fetchReport_whenTitleAndDateAndContentMissing_shouldFallbackToDefaults', async () => {
      mockInsights([reportRecord({ title: null, createdAt: null, content: null, confidence: null })])

      const wrapper = mountReport()
      await flushPromises()

      expect(wrapper.text()).toContain('架构解读')
      expect(wrapper.text()).toContain('AI 生成 · ')
      expect(wrapper.find('.markdown-body').text()).toBe('')
      // confidence 缺失不渲染置信度标签
      expect(wrapper.text()).not.toContain('置信度')
    })

    it('fetchReport_whenConfidenceBelowThreshold_shouldRenderOrangeTag', async () => {
      mockInsights([reportRecord({ confidence: 0.5 })])

      const wrapper = mountReport()
      await flushPromises()

      const tag = wrapper.findAll('.ant-tag').find(t => t.text().includes('置信度 50%'))
      expect(tag.exists()).toBe(true)
      expect(tag.classes()).toContain('ant-tag-orange')
    })

    it('fetchReport_whenConfidenceAtThreshold_shouldRenderGreenTag', async () => {
      mockInsights([reportRecord({ confidence: 0.85 })])

      const wrapper = mountReport()
      await flushPromises()

      const tag = wrapper.findAll('.ant-tag').find(t => t.text().includes('置信度 85%'))
      expect(tag.exists()).toBe(true)
      expect(tag.classes()).toContain('ant-tag-green')
    })

    it('fetchReport_whenNoRecords_shouldRenderEmptyStateWithGenerateButton', async () => {
      mockInsights([])

      const wrapper = mountReport()
      await flushPromises()

      expect(wrapper.find('.report-empty').exists()).toBe(true)
      // antd 4.2.6 的 a-empty 在 description prop 存在时优先 prop、忽略同名插槽
      expect(wrapper.text()).toContain('暂无架构解读')
      expect(wrapper.findAll('button').some(b => b.text().includes('立即生成'))).toBe(true)
      // 插槽内容手动调用：验证其渲染描述文案（正常展示路径被 antd prop 优先级遮蔽）
      const emptyVm = wrapper.findComponent({ name: 'AEmpty' }).vm
      const slotVNodes = emptyVm.$slots.description()
      expect(slotVNodes[0].children).toBe('报告说明文案')
    })

    it('fetchReport_whenApiErrorWithServerMessage_shouldRenderErrorStateAndRetryRefetches', async () => {
      api.get.mockRejectedValueOnce({ response: { data: { message: '报告服务异常' } } })

      const wrapper = mountReport()
      await flushPromises()

      expect(wrapper.find('.report-error').exists()).toBe(true)
      expect(wrapper.text()).toContain('加载失败')
      expect(wrapper.text()).toContain('报告服务异常')

      mockInsights([reportRecord()])
      await wrapper.find('.report-error button').trigger('click')
      await flushPromises()

      expect(wrapper.find('.report-error').exists()).toBe(false)
      expect(wrapper.find('.report-content').exists()).toBe(true)
    })

    it('fetchReport_whenApiErrorWithoutResponse_shouldRenderDefaultMessage', async () => {
      api.get.mockRejectedValueOnce(new Error('boom'))

      const wrapper = mountReport()
      await flushPromises()

      expect(wrapper.text()).toContain('加载报告失败')
    })

    it('fetchReport_whenResponseDataMissing_shouldFallbackToEmptyList', async () => {
      api.get.mockResolvedValueOnce({ data: { data: null } })

      const wrapper = mountReport()
      await flushPromises()

      expect(wrapper.vm.report).toBeNull()
      expect(wrapper.find('.report-empty').exists()).toBe(true)
    })
  })

  describe('autoPoll 轮询', () => {
    it('init_whenAutoPollAndAiRunningWithExistingReport_shouldShowRegeneratingTagAndPoll', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [reportRecord()] } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()

      expect(api.get).toHaveBeenCalledWith('/projects/p1/scans/status')
      expect(wrapper.text()).toContain('AI 重新生成中...')
      expect(wrapper.find('.report-content').exists()).toBe(true)

      // 轮询中：新报告 id 出现后停止轮询并替换内容
      const listCallsBefore = api.get.mock.calls.filter(c => c[0].endsWith('/insights')).length
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [reportRecord({ id: 10, title: '新报告' })] } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: false } } })
      })
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()

      expect(api.get.mock.calls.filter(c => c[0].endsWith('/insights')).length).toBe(listCallsBefore + 1)
      expect(wrapper.text()).toContain('新报告')
      expect(wrapper.text()).not.toContain('AI 重新生成中...')

      // 已停止：再推进时间不再有新请求
      const callsAfterStop = api.get.mock.calls.length
      await vi.advanceTimersByTimeAsync(15000)
      expect(api.get.mock.calls.length).toBe(callsAfterStop)
    })

    it('init_whenAutoPollAndAiRunningWithoutReport_shouldShowProcessingPlaceholder', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [] } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()

      expect(wrapper.find('.report-processing').exists()).toBe(true)
      expect(wrapper.text()).toContain('AI 正在生成架构解读，请稍候...')

      // 空列表轮询（响应 data 缺失走兜底分支）：随后状态变为非运行 → 结束等待回到空态
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: null } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: false } } })
      })
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()

      expect(wrapper.find('.report-processing').exists()).toBe(false)
      expect(wrapper.find('.report-empty').exists()).toBe(true)
    })

    it('init_whenAutoPollAndSameReportId_shouldKeepWaitingUntilAiStops', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
      let aiRunning = true
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [reportRecord()] } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: aiRunning } } })
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()
      expect(wrapper.text()).toContain('AI 重新生成中...')

      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()
      // id 未变化且仍在运行：继续等待
      expect(wrapper.text()).toContain('AI 重新生成中...')

      aiRunning = false
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()
      expect(wrapper.text()).not.toContain('AI 重新生成中...')
    })

    it('init_whenAutoPollAndStatusRequestFails_shouldIgnoreAndShowReport', async () => {
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [reportRecord()] } } })
        return Promise.reject(new Error('status down'))
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()

      expect(wrapper.find('.report-content').exists()).toBe(true)
      expect(wrapper.text()).not.toContain('AI 重新生成中...')
    })

    it('startPolling_whenPollRequestFails_shouldRetrySilentlyOnNextTick', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
      let failOnce = true
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) {
          if (failOnce) {
            failOnce = false
            return Promise.reject(new Error('transient'))
          }
          return Promise.resolve({ data: { data: { records: [] } } })
        }
        return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()
      expect(wrapper.find('.report-processing').exists()).toBe(true)

      // 第一次轮询失败静默
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()

      // 第二次轮询成功且 AI 已结束 → 退出等待
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [] } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: false } } })
      })
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()

      expect(wrapper.find('.report-processing').exists()).toBe(false)
      expect(message.error).not.toHaveBeenCalled()
    })

    it('init_whenAutoPollAndStatusDataMissing_shouldKeepIdleWithoutTag', async () => {
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [reportRecord()] } } })
        return Promise.resolve({ data: { data: null } })
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()

      expect(wrapper.find('.report-content').exists()).toBe(true)
      expect(wrapper.text()).not.toContain('AI 重新生成中...')
    })

    it('init_whenAutoPollDisabled_shouldNotRequestScansStatus', async () => {
      mockInsights([])

      const wrapper = mountReport({ autoPoll: false })
      await flushPromises()

      expect(api.get).not.toHaveBeenCalledWith('/projects/p1/scans/status')
      expect(wrapper.find('.report-empty').exists()).toBe(true)
    })
  })

  describe('generate 生成', () => {
    it('generate_whenSuccess_shouldCallAiEndpointAndRefetchReport', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      let capturedOptions
      runAiTask.mockImplementation(async options => {
        capturedOptions = options
        return { code: 200 }
      })
      mockInsights([reportRecord({ id: 11 })])
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await flushPromises()

      expect(runAiTask).toHaveBeenCalledTimes(1)
      expect(capturedOptions.projectId).toBe('p1')
      expect(capturedOptions.label).toBe('架构解读')
      expect(message.success).toHaveBeenCalledWith('架构解读生成完成')
      // fetchReport 重新拉取后显示新报告
      expect(wrapper.find('.report-content').exists()).toBe(true)
      expect(wrapper.text()).toContain('架构报告')

      // execute 执行体：POST 到 AI 端点并携带任务头与取消信号
      const signal = new AbortController().signal
      api.post.mockResolvedValueOnce({ data: { code: 200 } })
      const data = await capturedOptions.execute({ taskId: 't-1', signal })
      expect(api.post).toHaveBeenCalledWith('/projects/p1/ai/architecture-report', null, {
        headers: { 'X-Ai-Task-Id': 't-1' },
        signal
      })
      expect(data).toEqual({ code: 200 })
    })

    it('generate_whenBusinessCodeNot200_shouldShowErrorMessage', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockResolvedValueOnce({ code: 500, message: '模型超载' })
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('模型超载')
      expect(message.success).not.toHaveBeenCalled()
    })

    it('generate_whenBusinessCodeNot200AndNoMessage_shouldFallbackMessage', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockResolvedValueOnce({ code: 500 })
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('AI 分析失败')
    })

    it('generate_whenTaskCancelled_shouldReturnEarlyWithoutMessages', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockResolvedValueOnce(AI_TASK_CANCELLED)
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await flushPromises()

      expect(message.success).not.toHaveBeenCalled()
      expect(message.error).not.toHaveBeenCalled()
    })

    it('generate_whenRequestThrows_shouldSwallowErrorAndResetLoading', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockRejectedValueOnce(new Error('network'))
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await flushPromises()

      expect(wrapper.vm.generating).toBe(false)
      expect(message.error).not.toHaveBeenCalled()
    })

    it('generate_whenGeneratingWithoutReport_shouldShowLoadingPlaceholderAndTaskProgress', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await wrapper.vm.$nextTick()

      const genButton = wrapper.findAll('button').find(b => b.text().includes('AI 分析中（约需 30–90 秒）...'))
      expect(genButton).toBeTruthy()
      expect(genButton.classes()).toContain('ant-btn-loading')
      const progress = wrapper.find('.report-task-progress')
      expect(progress.exists()).toBe(true)
      expect(progress.text()).toContain('AI 正在生成报告…')
      expect(progress.text()).toContain('已等待 0 秒')

      // 任务进度来自全局 activeTask
      activeTask.value = { id: 't2', label: '架构解读', stage: '正在汇总依赖关系', elapsedSec: 7 }
      await wrapper.vm.$nextTick()
      expect(wrapper.find('.report-task-progress').text()).toContain('正在汇总依赖关系')
      expect(wrapper.find('.report-task-progress').text()).toContain('已等待 7 秒')

      // 终止按钮接线
      await wrapper.find('.report-task-progress button').trigger('click')
      expect(cancelActiveAiTask).toHaveBeenCalledTimes(1)
    })

    it('generate_whenRegeneratingWithReport_shouldShowReprocessingTagAndDisableExport', async () => {
      mockInsights([reportRecord()])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      await wrapper.findAll('button').find(b => b.text().includes('重新生成')).trigger('click')
      await wrapper.vm.$nextTick()

      expect(wrapper.text()).toContain('AI 重新生成中...')
      const progress = wrapper.find('.report-content .report-task-progress')
      expect(progress.exists()).toBe(true)
      expect(progress.text()).toContain('AI 正在重新生成报告…')

      const exportBtn = wrapper.findAll('button').find(b => b.text().includes('导出'))
      expect(exportBtn.attributes('disabled')).toBeDefined()
      const regenBtn = wrapper.findAll('button').find(b => b.text().includes('AI 分析中...'))
      expect(regenBtn).toBeTruthy()
    })
  })

  describe('handleExport 导出', () => {
    it('handleExport_whenNoReport_shouldDoNothing', async () => {
      mockInsights([])
      vi.stubGlobal('fetch', vi.fn())
      const wrapper = mountReport()
      await flushPromises()

      await wrapper.vm.handleExport({ key: 'pdf' })

      expect(fetch).not.toHaveBeenCalled()
    })

    it('handleExport_whenSuccess_shouldDownloadBlobWithAuthHeader', async () => {
      mockInsights([reportRecord()])
      localStorage.setItem('codeatlas_token', 'tok-123')
      const blob = new Blob(['pdf-bytes'], { type: 'application/pdf' })
      const fetchMock = vi.fn(() => Promise.resolve({ status: 200, ok: true, blob: () => Promise.resolve(blob) }))
      vi.stubGlobal('fetch', fetchMock)
      const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      const wrapper = mountReport()
      await flushPromises()

      await wrapper.findComponent({ name: 'AMenu' }).vm.$emit('click', { key: 'pdf' })
      await flushPromises()

      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/projects/p1/insights/ARCHITECTURE/export?format=pdf',
        { headers: { Authorization: 'Bearer tok-123' } }
      )
      expect(window.URL.createObjectURL).toHaveBeenCalledWith(blob)
      expect(clickSpy).toHaveBeenCalledTimes(1)
      expect(window.URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
      expect(message.success).toHaveBeenCalledWith('报告已下载')
      // 下载锚点已从 DOM 移除
      expect(document.body.querySelector('a[download]')).toBeNull()
    })

    it('handleExport_whenHtmlFormat_shouldUseLowercaseTypeInFilename', async () => {
      mockInsights([reportRecord()])
      const fetchMock = vi.fn(() => Promise.resolve({ status: 200, ok: true, blob: () => Promise.resolve(new Blob(['h'])) }))
      vi.stubGlobal('fetch', fetchMock)
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

      const wrapper = mountReport()
      await flushPromises()

      let downloadName = null
      const originalAppend = document.body.appendChild.bind(document.body)
      vi.spyOn(document.body, 'appendChild').mockImplementation(node => {
        if (node.tagName === 'A') downloadName = node.download
        return originalAppend(node)
      })

      await wrapper.vm.handleExport({ key: 'html' })
      await flushPromises()

      expect(fetchMock).toHaveBeenCalledWith(
        '/api/v1/projects/p1/insights/ARCHITECTURE/export?format=html',
        { headers: { Authorization: 'Bearer null' } }
      )
      expect(downloadName).toBe('codeatlas-architecture.html')
    })

    it('handleExport_whenUnauthorized_shouldDelegateToHandleUnauthorized', async () => {
      mockInsights([reportRecord()])
      const fetchMock = vi.fn(() => Promise.resolve({ status: 401, ok: false }))
      vi.stubGlobal('fetch', fetchMock)

      const wrapper = mountReport()
      await flushPromises()

      await wrapper.vm.handleExport({ key: 'pdf' })
      await flushPromises()

      expect(handleUnauthorized).toHaveBeenCalledTimes(1)
      // 401 走统一会话处理，不再额外提示导出失败/成功
      expect(message.error).not.toHaveBeenCalled()
      expect(message.success).not.toHaveBeenCalled()
    })

    it('handleExport_whenResponseNotOk_shouldShowExportFailed', async () => {
      mockInsights([reportRecord()])
      const fetchMock = vi.fn(() => Promise.resolve({ status: 500, ok: false }))
      vi.stubGlobal('fetch', fetchMock)

      const wrapper = mountReport()
      await flushPromises()

      await wrapper.vm.handleExport({ key: 'pdf' })
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('导出失败')
      expect(handleUnauthorized).not.toHaveBeenCalled()
    })

    it('handleExport_whenFetchRejectsWithError_shouldShowReason', async () => {
      mockInsights([reportRecord()])
      vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('断网'))))

      const wrapper = mountReport()
      await flushPromises()

      await wrapper.vm.handleExport({ key: 'pdf' })
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('导出失败: 断网')
    })

    it('handleExport_whenFetchRejectsWithoutMessage_shouldShowNetworkFallback', async () => {
      mockInsights([reportRecord()])
      vi.stubGlobal('fetch', vi.fn(() => Promise.reject({})))

      const wrapper = mountReport()
      await flushPromises()

      await wrapper.vm.handleExport({ key: 'pdf' })
      await flushPromises()

      expect(message.error).toHaveBeenCalledWith('导出失败: 网络错误')
    })
  })

  describe('生命周期与属性联动', () => {
    it('watchProjectId_whenChanged_shouldResetAndRefetchForNewProject', async () => {
      api.get.mockImplementation(url => {
        if (url === '/projects/p1/insights') return Promise.resolve({ data: { data: { records: [reportRecord()] } } })
        if (url === '/projects/p2/insights') return Promise.resolve({ data: { data: { records: [reportRecord({ id: 20, title: 'P2 报告' })] } } })
        return Promise.resolve({ data: { data: {} } })
      })

      const wrapper = mountReport()
      await flushPromises()
      expect(wrapper.text()).toContain('架构报告')

      await wrapper.setProps({ projectId: 'p2' })
      await flushPromises()

      expect(wrapper.text()).toContain('P2 报告')
      expect(wrapper.text()).not.toContain('架构报告')
    })

    it('onActivated_whenSecondActivation_shouldRefetchReport', async () => {
      mockInsights([reportRecord()])
      const host = mountInKeepAlive()
      await flushPromises()

      const callsAfterMount = api.get.mock.calls.filter(c => c[0].endsWith('/insights')).length
      expect(callsAfterMount).toBe(1)

      host.vm.show = false
      await flushPromises()
      host.vm.show = true
      await flushPromises()

      expect(api.get.mock.calls.filter(c => c[0].endsWith('/insights')).length).toBe(2)
    })

    it('onBeforeUnmount_whenPolling_shouldClearTimer', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
      api.get.mockImplementation(url => {
        if (url.endsWith('/insights')) return Promise.resolve({ data: { data: { records: [] } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
      })

      const wrapper = mountReport({ autoPoll: true })
      await flushPromises()
      const pollingTimerCount = vi.getTimerCount()
      expect(pollingTimerCount).toBeGreaterThan(0)

      wrapper.unmount()

      // 断言卸载后计数器减 1（组件的轮询定时器被清理），而非归零：
      // 假定时器窗口内可能有第三方库创建的无关 interval 混入计数
      expect(vi.getTimerCount()).toBe(pollingTimerCount - 1)
    })

    it('onBeforeUnmount_whenGenerating_shouldCancelActiveAiTask', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      runAiTask.mockReturnValueOnce(new Promise(() => {}))
      await wrapper.findAll('button').find(b => b.text().includes('立即生成')).trigger('click')
      await wrapper.vm.$nextTick()
      expect(wrapper.vm.generating).toBe(true)

      wrapper.unmount()

      expect(cancelActiveAiTask).toHaveBeenCalledTimes(1)
    })

    it('onBeforeUnmount_whenNotGenerating_shouldNotCancelTask', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      wrapper.unmount()

      expect(cancelActiveAiTask).not.toHaveBeenCalled()
    })

    it('formatDate_whenFalsyOrDate_shouldReturnEmptyOrLocalizedString', async () => {
      mockInsights([])
      const wrapper = mountReport()
      await flushPromises()

      expect(wrapper.vm.formatDate(null)).toBe('')
      expect(wrapper.vm.formatDate('')).toBe('')
      expect(wrapper.vm.formatDate('2026-01-02T10:00:00')).toContain('2026')
      expect(wrapper.vm.formatDate('2026-01-02T10:00:00')).toContain('月')
    })
  })
})
