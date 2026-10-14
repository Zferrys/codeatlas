import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent } from 'vue'
import Antd, { message, Modal, Progress } from 'ant-design-vue'

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn()
}))

vi.mock('@/api', () => ({
  default: { get, post },
  handleUnauthorized: vi.fn()
}))

import ProjectOverview from './ProjectOverview.vue'
import { handleUnauthorized } from '@/api'

enableAutoUnmount(afterEach)

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

let fetchMock

const sseBody = text => new ReadableStream({
  start(controller) {
    controller.enqueue(new TextEncoder().encode(text))
    controller.close()
  }
})

/** 保持打开的 SSE 流：事件已交付但连接不关闭（读取循环挂在 read 上） */
const openSseBody = text => new ReadableStream({
  start(controller) {
    controller.enqueue(new TextEncoder().encode(text))
  }
})

const sseResponse = (text, status = 200) => ({
  status,
  ok: status >= 200 && status < 300,
  body: sseBody(text)
})

function mockApi(opts = {}) {
  const {
    project = { id: 1, healthScore: 70, totalClasses: 10 },
    records = [],
    health = { data: { data: { healthScore: 88, dimensions: { architectureCompliance: 90 } } } },
    healthFails = false,
    projectsFails = false,
    status = { data: { data: {} } },
    statusImpl = null
  } = opts
  get.mockImplementation(url => {
    if (url === '/projects/1') {
      if (projectsFails) return Promise.reject(new Error('projects down'))
      return Promise.resolve({ data: { data: project } })
    }
    if (url === '/projects/1/scans') {
      return Promise.resolve({ data: { data: { records } } })
    }
    if (url === '/projects/1/health-score') {
      return healthFails ? Promise.reject(new Error('health down')) : Promise.resolve(health)
    }
    if (url === '/projects/1/scans/status') {
      return statusImpl ? statusImpl() : Promise.resolve(status)
    }
    return Promise.reject(new Error('unexpected url ' + url))
  })
}

async function mountOverview(path = '/projects/1') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:id', component: { template: '<div/>' } },
      { path: '/noproject', component: { template: '<div/>' } }
    ]
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(ProjectOverview, {
    global: { plugins: [router, Antd] }
  })
  await flushPromises()
  return { wrapper, router }
}

const findButtonByText = (scope, text) => {
  const target = text.replace(/\s+/g, '')
  return scope.findAll('button').find(b => b.text().replace(/\s+/g, '').includes(target))
}

const clickButtonByText = async (scope, text) => {
  const button = findButtonByText(scope, text)
  expect(button, `button "${text}" should exist`).toBeTruthy()
  await button.trigger('click')
  await flushPromises()
  return button
}

const scanStreamPayload = () => (
  'event: progress\ndata: ' +
  JSON.stringify({ stage: 'CLONING', progress: 20, message: '拉取代码' }) + '\n\n' +
  'data: ' + JSON.stringify({ stage: 'PARSING', progress: 50 }) + '\n\n' +
  'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100, message: 'done' }) + '\n\n'
)

const statusCallCount = () =>
  get.mock.calls.filter(call => String(call[0]).endsWith('/scans/status')).length

const projectCallCount = () =>
  get.mock.calls.filter(call => call[0] === '/projects/1').length

describe('ProjectOverview', () => {
  let successSpy
  let errorSpy

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('codeatlas_token', 'tk-1')
    get.mockReset()
    post.mockReset()
    post.mockResolvedValue({ data: { data: { id: 99 } } })
    mockApi()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    successSpy = vi.spyOn(message, 'success').mockImplementation(() => {})
    errorSpy = vi.spyOn(message, 'error').mockImplementation(() => {})
    vi.spyOn(message, 'warning').mockImplementation(() => {})
    vi.spyOn(message, 'info').mockImplementation(() => {})
    window.URL.createObjectURL = vi.fn(() => 'blob:mock-url')
    window.URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('onActivated_whenKeptAlive_shouldSkipFirstActivationThenSilentlyRefresh', async () => {
    const Host = defineComponent({
      components: { ProjectOverview },
      template: '<KeepAlive><ProjectOverview v-if="alive" /></KeepAlive>',
      data: () => ({ alive: true })
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    const wrapper = mount(Host, { global: { plugins: [router, Antd] } })
    await flushPromises()

    const initialProjectCalls = projectCallCount()
    const initialStatusCalls = statusCallCount()

    wrapper.vm.alive = false
    await flushPromises()
    wrapper.vm.alive = true // 首次激活已在挂载时跳过，这次真正触发静默刷新
    await flushPromises()

    expect(projectCallCount()).toBeGreaterThan(initialProjectCalls)
    expect(statusCallCount()).toBeGreaterThan(initialStatusCalls)
    // 静默刷新：不出现骨架屏
    expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
    expect(wrapper.find('.health-card').exists()).toBe(true)
  })

  it('loadData_whenAllLoaded_shouldRenderHealthDimensionsTrendLayersAndScans', async () => {
    mockApi({
      project: {
        id: 1,
        healthScore: 70,
        totalClasses: 12,
        layerDistribution: [
          { name: 'Service', percent: 50, count: 6, color: '#123456' },
          { name: 'Controller', percent: 33, count: 4, color: '#654321' }
        ]
      },
      health: {
        data: {
          data: {
            healthScore: 88,
            dimensions: {
              architectureCompliance: 90,
              codeStructure: null,
              codeQuality: undefined,
              dependencyHealth: 75
            }
          }
        }
      },
      records: [
        { id: 11, status: 'COMPLETED', totalClasses: 12, totalLines: 900, totalViolations: 3, durationMs: 2100, createdAt: '2026-02-03T10:30:00' },
        { id: 12, status: 'RUNNING', totalViolations: 5, createdAt: null },
        { id: 13, status: 'WEIRD', totalViolations: 1, createdAt: '2026-02-04T09:00:00' }
      ]
    })
    const { wrapper } = await mountOverview()

    // health-score 实时值优先于项目字段
    expect(wrapper.find('.health-value').text()).toBe('88')
    expect(wrapper.find('.health-tag').text()).toBe('优秀')
    // 维度分：null/undefined 回退 0
    const dimValues = wrapper.findAll('.dim-value').map(el => el.text())
    expect(dimValues).toEqual(['90分', '0分', '0分', '75分'])
    // 类分层分布
    expect(wrapper.find('.card-total').text()).toBe('12 个类')
    expect(wrapper.findAll('.layer-name').map(el => el.text())).toEqual(['Service', 'Controller'])
    expect(wrapper.findAll('.layer-count').map(el => el.text())).toEqual(['6', '4'])
    // 违规趋势迷你图（3 条记录 → 3 个点）
    expect(wrapper.find('.trend-svg').attributes('viewBox')).toBe('0 0 200 60')
    expect(wrapper.findAll('.trend-label-item').map(el => el.text())).toEqual(['#1', '#2', '#3'])
    // 扫描历史表格
    expect(wrapper.findAll('tbody tr')).toHaveLength(3)
    const tableText = wrapper.find('table').text()
    expect(tableText).toContain('COMPLETED')
    expect(tableText).toContain('WEIRD')
    expect(tableText).toContain('月') // 已格式化时间
  })

  it('healthLevel_whenScoreBands_shouldMapLabelAcrossBoundaries', async () => {
    const cases = [
      [88, '优秀'], [80, '优秀'],
      [70, '良好'], [60, '良好'],
      [50, '一般'], [40, '一般'],
      [30, '需改进']
    ]
    for (const [score, label] of cases) {
      mockApi({ health: { data: { data: { healthScore: score, dimensions: {} } } } })
      const { wrapper } = await mountOverview()
      expect(wrapper.find('.health-tag').text(), `score=${score}`).toBe(label)
    }
  })

  it('loadData_whenHealthApiFails_shouldFallbackToProjectScore', async () => {
    mockApi({ healthFails: true, project: { id: 1, healthScore: 70, totalClasses: 4 } })
    const { wrapper } = await mountOverview()

    expect(wrapper.find('.health-value').text()).toBe('70')
    expect(wrapper.find('.health-tag').text()).toBe('良好')
    expect(wrapper.findAll('.dim-value').map(el => el.text())).toEqual(['0分', '0分', '0分', '0分'])
  })

  it('loadData_whenScoresMissing_shouldRenderZeroAndEmptyStates', async () => {
    mockApi({ healthFails: true, project: { id: 1 } })
    const { wrapper } = await mountOverview()

    expect(wrapper.find('.health-value').text()).toBe('0')
    expect(wrapper.find('.health-tag').text()).toBe('需改进')
    expect(wrapper.text()).toContain('暂无扫描数据')
    expect(wrapper.text()).toContain('暂无类数据')
    expect(wrapper.text()).toContain('暂无扫描记录，点击上方按钮触发')
  })

  it('dimensions_whenScoresStoreCleared_shouldFallbackAllZeroScores', async () => {
    mockApi()
    const { wrapper } = await mountOverview()

    wrapper.vm.dimensionScores = null // 上游异常导致数据源被清空时，防御性回退不应崩溃
    const dims = wrapper.vm.dimensions
    expect(dims).toHaveLength(4)
    dims.forEach(d => expect(d.score).toBe(0))
  })

  it('trendPoints_whenNoTrendData_shouldReturnEmptyArray', async () => {
    mockApi() // 无扫描记录 → violationTrend 为空数组，趋势图不渲染但仍可安全求值
    const { wrapper } = await mountOverview()

    expect(wrapper.vm.violationTrend).toHaveLength(0)
    expect(wrapper.vm.trendPoints).toEqual([])
    expect(wrapper.find('.trend-chart').exists()).toBe(false)
  })

  it('loadData_whenScansMissingRecords_shouldFallbackToEmptyList', async () => {
    mockApi({ records: null })
    const { wrapper } = await mountOverview()

    expect(wrapper.vm.scans).toEqual([])
    expect(wrapper.text()).toContain('暂无扫描记录，点击上方按钮触发')
  })

  it('loadData_whenProjectsRequestFails_shouldShowDashAndKeepEmptyStates', async () => {
    mockApi({ projectsFails: true })
    const { wrapper } = await mountOverview()

    // 加载失败：healthScore 保持 null → 展示占位符
    expect(wrapper.find('.health-value').text()).toBe('--')
    expect(wrapper.find('.health-tag').text()).toBe('需改进')
    expect(wrapper.text()).toContain('暂无扫描数据')
  })

  it('loadData_whenLoadingPending_shouldShowSkeleton', async () => {
    let resolveProjects
    mockApi()
    get.mockImplementation(url => {
      if (url === '/projects/1') {
        return new Promise(resolve => { resolveProjects = resolve })
      }
      if (url === '/projects/1/scans') return Promise.resolve({ data: { data: { records: [] } } })
      if (url === '/projects/1/health-score') return Promise.resolve({ data: { data: { healthScore: 88 } } })
      return Promise.resolve({ data: { data: {} } })
    })
    const { wrapper } = await mountOverview()

    expect(wrapper.find('.ant-skeleton').exists()).toBe(true)
    expect(wrapper.find('.health-card').exists()).toBe(false)

    resolveProjects({ data: { data: { id: 1, healthScore: 70 } } })
    await flushPromises()

    expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
    expect(wrapper.find('.health-card').exists()).toBe(true)
  })

  it('loadData_whenSingleScanWithoutViolations_shouldUseCenterPointAndZeroFallback', async () => {
    mockApi({ records: [{ id: 1, status: 'COMPLETED', totalViolations: null, createdAt: '2026-02-03T10:30:00' }] })
    const { wrapper } = await mountOverview()

    // 单点位于视图中心（200/2=100），值回退 0 → y = 60-10-(0/1)*40 = 50
    expect(wrapper.find('.trend-svg polyline').attributes('points')).toBe('100,50')
    expect(wrapper.findAll('circle')).toHaveLength(1)
  })

  it('loadData_whenMoreThanEightScans_shouldKeepLatestEightChronologically', async () => {
    const records = Array.from({ length: 10 }, (_, i) => ({
      id: 100 + i,
      status: 'COMPLETED',
      totalViolations: i,
      createdAt: `2026-02-${String(i + 1).padStart(2, '0')}T10:00:00`
    }))
    mockApi({ records })
    const { wrapper } = await mountOverview()

    expect(wrapper.findAll('.trend-label-item')).toHaveLength(8)
    // 8 点 → 宽度 8*50=400
    expect(wrapper.find('.trend-svg').attributes('viewBox')).toBe('0 0 400 60')
  })

  it('triggerScan_whenClicked_shouldPostSubscribeSSEAndComplete', async () => {
    mockApi()
    fetchMock.mockResolvedValue(sseResponse(scanStreamPayload()))
    const { wrapper } = await mountOverview()
    vi.useFakeTimers()

    const projectsBefore = projectCallCount()
    const statusBefore = statusCallCount()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    expect(post).toHaveBeenCalledWith('/projects/1/scans')
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/1/scans/progress?scanId=99', expect.objectContaining({
      headers: { Authorization: 'Bearer tk-1' }
    }))
    // 扫描中：进度卡展示终态事件，等待 800ms 收尾
    const card = wrapper.find('.scan-progress-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.progress-stage').text()).toContain('扫描完成')
    expect(card.find('.progress-pct').text()).toBe('100%')
    expect(card.find('.progress-msg').text()).toBe('done')
    expect(card.findComponent(Progress).props('strokeColor')).toBe('#52c41a')
    expect(successSpy).toHaveBeenCalledWith('扫描完成')

    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()

    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
    expect(projectCallCount()).toBeGreaterThan(projectsBefore) // 完成后重新拉取
    expect(statusCallCount()).toBeGreaterThan(statusBefore) // checkAiPhase 查询
  })

  it('triggerScan_whenStageFailed_shouldToastServerMessageAndReload', async () => {
    mockApi()
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'FAILED', progress: 60, message: '解析炸了' }) + '\n\n'
    ))
    const { wrapper } = await mountOverview()
    vi.useFakeTimers()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('扫描失败: 解析炸了')
    const card = wrapper.find('.scan-progress-card')
    expect(card.find('.progress-stage').text()).toContain('扫描失败')
    expect(card.findComponent(Progress).props('status')).toBe('exception')
    expect(card.findComponent(Progress).props('strokeColor')).toBe('#ff4d4f')

    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('triggerScan_whenStageFailedWithoutMessage_shouldToastUnknownError', async () => {
    mockApi()
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'FAILED', progress: 30 }) + '\n\n'
    ))
    const { wrapper } = await mountOverview()
    vi.useFakeTimers()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('扫描失败: 未知错误')
    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
  })

  it('triggerScan_whenPostFails_shouldResetScanningWithoutSSE', async () => {
    mockApi()
    post.mockRejectedValueOnce(new Error('500'))
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('subscribeScanProgress_when401_shouldHandleUnauthorizedAndStop', async () => {
    mockApi()
    fetchMock.mockResolvedValue({ status: 401 })
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')

    expect(handleUnauthorized).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('subscribeScanProgress_whenStreamEndsWithoutTerminal_shouldStopAndReload', async () => {
    mockApi()
    fetchMock.mockResolvedValue(sseResponse('data: ' + JSON.stringify({ stage: 'RULES', progress: 33 }) + '\n\n'))
    const { wrapper } = await mountOverview()

    const projectsBefore = projectCallCount()
    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
    expect(projectCallCount()).toBeGreaterThan(projectsBefore)
  })

  it('subscribeScanProgress_whenStreamOpen_shouldRenderIntermediateStage', async () => {
    mockApi()
    // 未知阶段 + 缺省 message + 流保持打开：直接观察中间态渲染
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: openSseBody('data: ' + JSON.stringify({ stage: 'MYSTERY', progress: 42 }) + '\n\n') })
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    const card = wrapper.find('.scan-progress-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.progress-stage').text()).toContain('MYSTERY') // 未知阶段原样展示
    expect(card.find('.progress-pct').text()).toBe('42%')
    expect(card.find('.progress-msg').exists()).toBe(false) // 无 message 不渲染提示行
    expect(card.findComponent(Progress).props('strokeColor')).toBe('#1677ff')
  })

  it('subscribeScanProgress_whenProgressMissing_shouldDefaultToZeroPercent', async () => {
    mockApi()
    // stage 事件缺 progress 字段：进度回退 0%（而非 undefined）
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: openSseBody('data: ' + JSON.stringify({ stage: 'CLONING', message: '拉取代码' }) + '\n\n') })
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    const card = wrapper.find('.scan-progress-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.progress-stage').text()).toContain('克隆仓库')
    expect(card.find('.progress-pct').text()).toBe('0%')
    expect(card.find('.progress-msg').text()).toBe('拉取代码')
    expect(wrapper.vm.scanProgress.progress).toBe(0)
  })

  it('subscribeScanProgress_whenMalformedEvents_shouldSkipAndContinue', async () => {
    mockApi()
    fetchMock.mockResolvedValue(sseResponse(
      'event: progress\n' +                          // event 行跳过
      'data: {broken json\n\n' +                     // JSON 解析失败跳过
      'data: ' + JSON.stringify({ progress: 10 }) + '\n\n' + // 无 stage 不更新
      'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100, message: 'ok' }) + '\n\n'
    ))
    const { wrapper } = await mountOverview()
    vi.useFakeTimers()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()

    expect(successSpy).toHaveBeenCalledWith('扫描完成')
    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('subscribeScanProgress_whenFetchFails_shouldToastMessage', async () => {
    mockApi()
    fetchMock.mockRejectedValue(new Error('boom'))
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')

    expect(errorSpy).toHaveBeenCalledWith('扫描失败: boom')
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('subscribeScanProgress_whenFetchFailsWithoutMessage_shouldToastNetworkError', async () => {
    mockApi()
    fetchMock.mockRejectedValue({ name: 'TypeError' })
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')

    expect(errorSpy).toHaveBeenCalledWith('扫描失败: 网络错误')
  })

  it('subscribeScanProgress_whenAbortedOnUnmount_shouldSilenceAndCleanup', async () => {
    mockApi()
    let aborted = false
    fetchMock.mockImplementation((url, options) => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => {
        aborted = true
        const error = new Error('The operation was aborted')
        error.name = 'AbortError'
        reject(error)
      })
    }))
    // 用 Host 包一层：只卸载 ProjectOverview 而不卸载整个应用
    // （vue-router 在最后一个 app 卸载时会重置 currentRoute，导致 route 参数丢失）
    const Host = defineComponent({
      components: { ProjectOverview },
      template: '<ProjectOverview v-if="alive" />',
      data: () => ({ alive: true })
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    const host = mount(Host, { global: { plugins: [router, Antd] } })
    await flushPromises()

    await clickButtonByText(host, '触发扫描')
    const projectsBefore = projectCallCount()
    expect(fetchMock).toHaveBeenCalled()

    host.vm.alive = false // 卸载子组件 → onBeforeUnmount → cleanupSSE → abort
    await flushPromises()

    expect(aborted).toBe(true)
    // AbortError 静默：不弹错误，但仍收尾并刷新数据
    expect(errorSpy).not.toHaveBeenCalledWith(expect.stringContaining('扫描失败'))
    expect(projectCallCount()).toBeGreaterThan(projectsBefore)
  })

  it('triggerScan_whenNoScanIdReturned_shouldSubscribeWithoutParam', async () => {
    mockApi()
    post.mockResolvedValueOnce({ data: null })
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100 }) + '\n\n'
    ))
    const { wrapper } = await mountOverview()
    vi.useFakeTimers()

    await clickButtonByText(wrapper, '触发扫描')

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/1/scans/progress', expect.anything())
    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
  })

  it('triggerIncrementalScan_whenClicked_shouldPostIncrementAndComplete', async () => {
    mockApi()
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100, message: 'ok' }) + '\n\n'
    ))
    const { wrapper } = await mountOverview()
    vi.useFakeTimers()

    await clickButtonByText(wrapper, '增量扫描')

    expect(post).toHaveBeenCalledWith('/projects/1/scans/increment')
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/1/scans/progress?scanId=99', expect.anything())

    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('triggerIncrementalScan_whenPostFails_shouldToastFailure', async () => {
    mockApi()
    post.mockRejectedValueOnce(new Error('500'))
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '增量扫描')

    expect(errorSpy).toHaveBeenCalledWith('增量扫描触发失败')
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('resumeRunningScan_whenScanRunning_shouldResubscribeWithPendingStage', async () => {
    mockApi({ status: { data: { data: { scanStatus: 'RUNNING', scanId: 5 } } } })
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: openSseBody('') })
    const { wrapper } = await mountOverview()

    const card = wrapper.find('.scan-progress-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.progress-stage').text()).toContain('准备中')
    expect(card.find('.progress-pct').text()).toBe('1%')
    expect(card.find('.progress-msg').text()).toBe('正在恢复扫描进度...')
    expect(card.findComponent(Progress).props('strokeColor')).toBe('#1677ff')
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/1/scans/progress?scanId=5', expect.anything())
  })

  it('resumeRunningScan_whenStageAlreadySet_shouldKeepExistingStage', async () => {
    mockApi()
    const { wrapper } = await mountOverview()

    wrapper.vm.scanProgress.stage = 'CLONING'
    mockApi({ status: { data: { data: { scanStatus: 'RUNNING', scanId: 6 } } } })
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: openSseBody('') })
    await wrapper.vm.resumeRunningScan()
    await flushPromises()

    expect(wrapper.vm.scanProgress.stage).toBe('CLONING') // 不覆盖已有阶段
    expect(wrapper.vm.scanProgress.progress).toBe(0)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/projects/1/scans/progress?scanId=6', expect.anything())
  })

  it('resumeRunningScan_whenAlreadyInFlight_shouldSkipDuplicate', async () => {
    let resolveStatus
    mockApi()
    get.mockImplementation(url => {
      if (url === '/projects/1') return Promise.resolve({ data: { data: { id: 1, healthScore: 70 } } })
      if (url === '/projects/1/scans') return Promise.resolve({ data: { data: { records: [] } } })
      if (url === '/projects/1/health-score') return Promise.resolve({ data: { data: { healthScore: 88 } } })
      return new Promise(resolve => { resolveStatus = resolve })
    })
    const { wrapper } = await mountOverview()

    // 首次 resume 仍挂起（status 未决）→ 再次调用被 resumeInFlight 拦截
    await wrapper.vm.resumeRunningScan()
    const before = statusCallCount()

    resolveStatus({ data: { data: {} } })
    await flushPromises()

    expect(statusCallCount()).toBe(before)
  })

  it('resumeRunningScan_whenStatusFails_shouldSilenceError', async () => {
    mockApi()
    get.mockImplementation(url => {
      if (url === '/projects/1') return Promise.resolve({ data: { data: { id: 1, healthScore: 70 } } })
      if (url === '/projects/1/scans') return Promise.resolve({ data: { data: { records: [] } } })
      if (url === '/projects/1/health-score') return Promise.resolve({ data: { data: { healthScore: 88 } } })
      return Promise.reject(new Error('status down'))
    })
    const { wrapper } = await mountOverview()

    // 状态查询失败静默，不影响页面其余数据
    expect(errorSpy).not.toHaveBeenCalled()
    expect(wrapper.find('.health-card').exists()).toBe(true)
  })

  it('resumeRunningScan_whenStatusPayloadEmpty_shouldStayIdle', async () => {
    mockApi({ status: { data: {} } }) // 响应缺 data 字段：回退空对象，视为无进行中任务
    const { wrapper } = await mountOverview()

    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
    expect(wrapper.find('.health-card').exists()).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('checkAiPhase_whenStatusPayloadEmpty_shouldNotEnterPolling', async () => {
    mockApi({ status: { data: {} } }) // checkAiPhase 同样回退空对象 → 不进入 AI 轮询
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100, message: 'ok' }) + '\n\n'
    ))
    vi.useFakeTimers()
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()

    expect(successSpy).toHaveBeenCalledWith('扫描完成')
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
    expect(wrapper.vm.scanProgress.stage).toBe('COMPLETED')
  })

  it('checkAiPhase_whenAiRunning_shouldPollUntilDoneWithCatchRetry', async () => {
    let statusMode = 'ai-running'
    mockApi({
      statusImpl: () => {
        if (statusMode === 'ai-running') return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
        if (statusMode === 'reject') return Promise.reject(new Error('down'))
        return Promise.resolve({ data: { data: { aiAnalysisRunning: false } } })
      }
    })
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100, message: 'ok' }) + '\n\n'
    ))
    vi.useFakeTimers() // 8000ms 轮询定时器须在挂载（resume 触发 startAiPhasePoll）前受控
    const { wrapper } = await mountOverview()

    // 首次 resume 查询返回 ai-running → 直接进入轮询
    expect(wrapper.find('.scan-progress-card').exists()).toBe(true)
    const card = wrapper.find('.scan-progress-card')
    expect(card.find('.progress-stage').text()).toContain('AI 分析')
    expect(card.find('.progress-pct').text()).toBe('80%')
    expect(card.find('.progress-msg').text()).toBe('AI 架构分析中，请稍候...')

    statusMode = 'reject'
    await vi.advanceTimersByTimeAsync(8000) // 第一次轮询失败 → 静默重试
    statusMode = 'done'
    await vi.advanceTimersByTimeAsync(8000) // 第二次：AI 结束

    expect(card.find('.progress-stage').text()).toContain('扫描完成')
    expect(card.find('.progress-pct').text()).toBe('100%')
    expect(successSpy).toHaveBeenCalledWith('AI 分析完成')

    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('aiPhasePoll_whenTickPayloadEmpty_shouldFallbackAndFinish', async () => {
    let calls = 0
    mockApi({
      statusImpl: () => {
        calls += 1
        if (calls === 1) return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
        return Promise.resolve({ data: {} }) // 轮询期间响应缺 data：回退空对象 → 视为已结束
      }
    })
    vi.useFakeTimers()
    const { wrapper } = await mountOverview()

    // 挂载恢复直接进入 AI 轮询
    expect(wrapper.find('.scan-progress-card').exists()).toBe(true)
    expect(wrapper.find('.progress-stage').text()).toContain('AI 分析')

    await vi.advanceTimersByTimeAsync(8000)
    expect(successSpy).toHaveBeenCalledWith('AI 分析完成')
    expect(wrapper.vm.scanProgress.progress).toBe(100)

    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('startAiPhasePollThenStop_whenCalled_shouldManageTimerWithoutLeak', async () => {
    vi.useFakeTimers()
    mockApi()
    const { wrapper } = await mountOverview()

    // 无定时器时 stop 为安全空操作；随后 start 再 stop
    wrapper.vm.stopAiPhasePoll()
    wrapper.vm.startAiPhasePoll('1')
    expect(wrapper.vm.scanProgress.stage).toBe('AI')
    wrapper.vm.startAiPhasePoll('1') // 重复 start 先 stop 旧定时器
    wrapper.vm.stopAiPhasePoll()
    wrapper.vm.stopAiPhasePoll() // 已停止后为空操作

    await vi.advanceTimersByTimeAsync(8000)
    // 定时器已清空：不再发起状态查询（挂载时 1 次以外的调用不应出现）
    expect(get.mock.calls.filter(call => String(call[0]).endsWith('/scans/status')).length).toBe(1)
  })

  it('exportDropdown_whenOpened_shouldRenderMenuAndTriggerExport', async () => {
    mockApi()
    fetchMock.mockResolvedValue({ status: 200, ok: true, blob: async () => new Blob(['x']) })
    vi.useFakeTimers()
    const { wrapper } = await mountOverview()

    // hover 触发有默认 150ms 打开延迟，需推进定时器后浮层才渲染到 body
    await wrapper.find('.ant-dropdown-trigger').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(200)
    await flushPromises()

    const menuItems = document.querySelectorAll('.ant-dropdown-menu-item')
    expect(menuItems).toHaveLength(2)
    expect(menuItems[0].textContent).toContain('PDF 格式')
    expect(menuItems[1].textContent).toContain('HTML 格式')

    menuItems[1].dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/projects/1/reports/download?format=html',
      { headers: { Authorization: 'Bearer tk-1' } }
    )
    expect(successSpy).toHaveBeenCalledWith('报告已下载')
  })

  it('checkAiPhase_whenAiStartsAfterScanComplete_shouldEnterPolling', async () => {
    let statusMode = 'done'
    mockApi({
      statusImpl: () => {
        if (statusMode === 'ai-running') return Promise.resolve({ data: { data: { aiAnalysisRunning: true } } })
        return Promise.resolve({ data: { data: { aiAnalysisRunning: false } } })
      }
    })
    fetchMock.mockResolvedValue(sseResponse(
      'data: ' + JSON.stringify({ stage: 'COMPLETED', progress: 100, message: 'ok' }) + '\n\n'
    ))
    vi.useFakeTimers()
    const { wrapper } = await mountOverview()

    await clickButtonByText(wrapper, '触发扫描')
    await flushPromises()
    statusMode = 'ai-running' // 扫描完成后的 checkAiPhase 查询将发现 AI 阶段开始
    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()

    // 扫描收尾后进入 AI 轮询态
    expect(wrapper.find('.scan-progress-card').exists()).toBe(true)
    expect(wrapper.find('.progress-stage').text()).toContain('AI 分析')

    statusMode = 'done'
    await vi.advanceTimersByTimeAsync(8000)
    expect(successSpy).toHaveBeenCalledWith('AI 分析完成')

    await vi.advanceTimersByTimeAsync(800)
    await flushPromises()
    expect(wrapper.find('.scan-progress-card').exists()).toBe(false)
  })

  it('handleExportReport_whenDownloadTriggers_shouldSaveBlob', async () => {
    mockApi()
    const blob = new Blob(['report'])
    fetchMock.mockResolvedValue({ status: 200, ok: true, blob: async () => blob })
    const { wrapper } = await mountOverview()

    await wrapper.vm.handleExportReport({ key: 'pdf' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/projects/1/reports/download?format=pdf',
      { headers: { Authorization: 'Bearer tk-1' } }
    )
    expect(window.URL.createObjectURL).toHaveBeenCalledWith(blob)
    expect(window.URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
    const clickedAnchor = HTMLAnchorElement.prototype.click.mock.instances[0]
    expect(clickedAnchor.download).toBe('codeatlas-report.pdf')
    expect(successSpy).toHaveBeenCalledWith('报告已下载')
  })

  it('handleExportReport_whenHtmlFormat_shouldNameHtmlFile', async () => {
    mockApi()
    fetchMock.mockResolvedValue({ status: 200, ok: true, blob: async () => new Blob(['x']) })
    const { wrapper } = await mountOverview()

    await wrapper.vm.handleExportReport({ key: 'html' })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/projects/1/reports/download?format=html',
      expect.anything()
    )
    expect(HTMLAnchorElement.prototype.click.mock.instances[0].download).toBe('codeatlas-report.html')
  })

  it('handleExportReport_when401_shouldHandleUnauthorized', async () => {
    mockApi()
    fetchMock.mockResolvedValue({ status: 401 })
    const { wrapper } = await mountOverview()

    await wrapper.vm.handleExportReport({ key: 'pdf' })

    expect(handleUnauthorized).toHaveBeenCalledTimes(1)
    expect(successSpy).not.toHaveBeenCalled()
  })

  it('handleExportReport_whenResponseNotOk_shouldToastExportFailed', async () => {
    mockApi()
    fetchMock.mockResolvedValue({ status: 500, ok: false })
    const { wrapper } = await mountOverview()

    await wrapper.vm.handleExportReport({ key: 'pdf' })

    expect(errorSpy).toHaveBeenCalledWith('导出失败')
  })

  it('handleExportReport_whenFetchThrows_shouldToastMessage', async () => {
    mockApi()
    fetchMock.mockRejectedValue(new Error('down'))
    const { wrapper } = await mountOverview()

    await wrapper.vm.handleExportReport({ key: 'pdf' })

    expect(errorSpy).toHaveBeenCalledWith('导出失败: down')
  })

  it('handleExportReport_whenFetchThrowsWithoutMessage_shouldToastNetworkError', async () => {
    mockApi()
    fetchMock.mockRejectedValue({ name: 'TypeError' })
    const { wrapper } = await mountOverview()

    await wrapper.vm.handleExportReport({ key: 'pdf' })

    expect(errorSpy).toHaveBeenCalledWith('导出失败: 网络错误')
  })

  it('openDimensionRule_whenDimCardClicked_shouldShowRuleModalAndClose', async () => {
    mockApi({ health: { data: { data: { healthScore: 88, dimensions: { architectureCompliance: 90 } } } } })
    const { wrapper } = await mountOverview()

    await wrapper.find('.dim-card').trigger('click')
    await flushPromises()

    const modal = wrapper.findComponent(Modal)
    expect(modal.props('open')).toBe(true)
    expect(modal.props('title')).toBe('架构合规 — 扣分规则')
    expect(modal.props('footer')).toBeNull()
    const body = document.querySelector('.rule-modal-body')
    expect(body).not.toBeNull()
    expect(body.textContent).toContain('90')
    expect(body.textContent).toContain('权重 40%')
    expect(body.textContent).toContain('每个违规扣 5 分')

    modal.vm.$emit('update:open', false)
    await flushPromises()
    expect(wrapper.findComponent(Modal).props('open')).toBe(false)
  })

  it('guards_whenProjectIdMissing_shouldSkipAllNetworkActions', async () => {
    const { wrapper } = await mountOverview('/noproject')

    await wrapper.vm.triggerScan()
    await wrapper.vm.triggerIncrementalScan()
    await wrapper.vm.handleExportReport({ key: 'pdf' })
    await wrapper.vm.resumeRunningScan()
    await flushPromises()

    expect(get).not.toHaveBeenCalled()
    expect(post).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
    // loading 初值为 true 且 loadData 提前返回，页面停留在骨架屏态
    expect(wrapper.find('.ant-skeleton').exists()).toBe(true)
  })
})
