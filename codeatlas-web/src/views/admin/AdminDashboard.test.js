import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { nextTick } from 'vue'
import {
  Spin as ASpin, Row as ARow, Col as ACol, Card as ACard, Progress as AProgress,
  Tag as ATag, Button as AButton, Result as AResult, Space as ASpace
} from 'ant-design-vue'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}))

vi.mock('../../api', () => ({ default: apiMock }))

import AdminDashboard from './AdminDashboard.vue'

enableAutoUnmount(afterEach)

const globalComponents = {
  ASpin, ARow, ACol, ACard, AProgress, ATag, AButton, AResult, ASpace
}

function makeStatusData(overrides = {}) {
  return {
    uptimeFormatted: '3天2小时',
    startedAt: '2026-03-01 08:00:00',
    totalProjects: 12,
    workspaceSizeMb: 345.6,
    lastCleanupFreedMb: 120,
    lastCleanupFileCount: 8,
    lastCleanupTime: '2026-03-04 06:30:00',
    diskFreePercent: 50,
    diskTotalGb: 500,
    diskFreeGb: 250,
    heapUsedPercent: 40,
    heapMaxMb: 2048,
    heapTotalMb: 1024,
    heapFreeMb: 512,
    ...overrides
  }
}

function mountView() {
  return mount(AdminDashboard, { global: { components: globalComponents } })
}

describe('AdminDashboard', () => {
  let setIntervalSpy
  let clearIntervalSpy

  beforeEach(() => {
    // ant-design-vue 的 Row/Col 依赖 matchMedia，jsdom 缺失
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
    // 拦截真实定时器，避免测试结束后残留 interval
    setIntervalSpy = vi.spyOn(globalThis, 'setInterval').mockImplementation(() => 12345)
    clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => {})
    apiMock.get.mockReset()
    apiMock.get.mockResolvedValue({ data: { data: makeStatusData() } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('AdminDashboard_whenMounted_shouldFetchStatusAndStartAutoRefresh', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledWith('/admin/system-status')
    expect(setIntervalSpy).toHaveBeenCalledTimes(1)
    expect(setIntervalSpy.mock.calls[0][1]).toBe(30000)
    expect(wrapper.vm.autoRefreshing).toBe(true)
    expect(wrapper.find('.refresh-hint').text()).toBe('每 30s 自动刷新')
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.error).toBeNull()

    // 在线标签与各统计卡片数值
    expect(wrapper.findComponent(ATag).text()).toBe('在线')
    const text = wrapper.text()
    expect(text).toContain('3天2小时')
    expect(text).toContain('自 2026-03-01 08:00:00')
    expect(text).toContain('345.6 MB')
    expect(text).toContain('120 MB / 8 文件')
    expect(text).toContain('最近: 2026-03-04 06:30:00')
    expect(text).toContain('250.0 GB')
    expect(text).toContain('250 GB')
    expect(text).toContain('512 MB (40%)')

    // 两个环形进度条
    const progresses = wrapper.findAllComponents(AProgress)
    expect(progresses).toHaveLength(2)
    expect(progresses[0].props('percent')).toBe(50)
    expect(progresses[0].props('status')).toBe('normal')
    expect(progresses[1].props('percent')).toBe(40)
    expect(progresses[1].props('status')).toBe('normal')
  })

  it('AdminDashboard_whenStatusMissing_shouldUseFallbackComputedValues', async () => {
    apiMock.get.mockImplementation(() => new Promise(() => {}))
    const wrapper = mountView()
    await nextTick() // onMounted 里 loading=true 晚于首次渲染

    expect(wrapper.vm.loading).toBe(true)
    expect(wrapper.vm.status).toBeNull()
    expect(wrapper.vm.diskStatus).toBe('normal')
    expect(wrapper.vm.diskStatusColor).toBe('inherit')
    expect(wrapper.vm.diskUsedColor).toBe('inherit')
    expect(wrapper.vm.heapStatus).toBe('normal')
    expect(wrapper.vm.heapUsedColor).toBe('inherit')
    expect(wrapper.findComponent(ASpin).props('spinning')).toBe(true)
    expect(wrapper.findComponent(ATag).exists()).toBe(false)
    expect(wrapper.findAll('.ds-item')).toHaveLength(0)
  })

  it('AdminDashboard_diskIndicators_shouldFollowThresholds', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.vm.status = makeStatusData({ diskFreePercent: 10 })
    expect(wrapper.vm.diskStatus).toBe('exception')
    expect(wrapper.vm.diskStatusColor).toBe('#ff4d4f')
    // 已用 90% > 80%
    expect(wrapper.vm.diskUsedColor).toBe('#ff4d4f')

    wrapper.vm.status = makeStatusData({ diskFreePercent: 15 })
    expect(wrapper.vm.diskStatus).toBe('active')
    expect(wrapper.vm.diskStatusColor).toBe('#faad14')
    expect(wrapper.vm.diskUsedColor).toBe('#ff4d4f')

    wrapper.vm.status = makeStatusData({ diskFreePercent: 20 })
    expect(wrapper.vm.diskStatus).toBe('active')
    expect(wrapper.vm.diskStatusColor).toBe('#faad14')

    wrapper.vm.status = makeStatusData({ diskFreePercent: 25 })
    expect(wrapper.vm.diskStatus).toBe('normal')
    expect(wrapper.vm.diskStatusColor).toBe('#52c41a')
    // 已用 75% 未超过阈值
    expect(wrapper.vm.diskUsedColor).toBe('inherit')
  })

  it('AdminDashboard_heapIndicators_shouldFollowThresholds', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.vm.status = makeStatusData({ heapUsedPercent: 90 })
    expect(wrapper.vm.heapStatus).toBe('exception')
    expect(wrapper.vm.heapUsedColor).toBe('#ff4d4f')

    wrapper.vm.status = makeStatusData({ heapUsedPercent: 70 })
    expect(wrapper.vm.heapStatus).toBe('active')
    expect(wrapper.vm.heapUsedColor).toBe('#faad14')

    wrapper.vm.status = makeStatusData({ heapUsedPercent: 69 })
    expect(wrapper.vm.heapStatus).toBe('normal')
    expect(wrapper.vm.heapUsedColor).toBe('inherit')
  })

  it('AdminDashboard_whenIndicatorsAlerting_shouldPassStatusAndColorsToDom', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.vm.status = makeStatusData({ diskFreePercent: 5, heapUsedPercent: 95 })
    await flushPromises()

    const progresses = wrapper.findAllComponents(AProgress)
    expect(progresses[0].props('percent')).toBe(95)
    expect(progresses[0].props('status')).toBe('exception')
    expect(progresses[1].props('percent')).toBe(95)
    expect(progresses[1].props('status')).toBe('exception')

    const coloredValues = wrapper.findAll('.usage-value')
      .filter((n) => (n.attributes('style') || '').includes('color'))
      .map((n) => n.attributes('style'))
    expect(coloredValues.some((s) => s.includes('rgb(255, 77, 79)'))).toBe(true)
  })

  it('AdminDashboard_whenFallbacksNeeded_shouldRenderDashesAndHideDescriptions', async () => {
    apiMock.get.mockResolvedValue({
      data: {
        data: makeStatusData({
          uptimeFormatted: '',
          startedAt: null,
          lastCleanupTime: 'never'
        })
      }
    })
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('--')
    expect(text).not.toContain('自 2026')
    expect(text).not.toContain('最近:')
  })

  it('AdminDashboard_whenLoadFailsWithServerMessage_shouldShowMessageAndRetry', async () => {
    apiMock.get.mockRejectedValueOnce({
      response: { data: { message: '后端异常' } }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.error).toBe('后端异常')
    expect(wrapper.vm.status).toBeNull()
    expect(wrapper.vm.loading).toBe(false)
    const result = wrapper.findComponent(AResult)
    expect(result.exists()).toBe(true)
    expect(result.props('subTitle')).toBe('后端异常')

    // 重试按钮第二次加载成功
    apiMock.get.mockResolvedValue({ data: { data: makeStatusData() } })
    await wrapper.find('.ant-result button').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.error).toBeNull()
    expect(wrapper.findComponent(AResult).exists()).toBe(false)
    expect(wrapper.findComponent(ATag).text()).toBe('在线')
  })

  it('AdminDashboard_whenLoadFailsWithoutResponse_shouldShowDefaultMessage', async () => {
    apiMock.get.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.error).toBe('加载系统状态失败')
    expect(wrapper.findComponent(AResult).props('subTitle')).toBe('加载系统状态失败')
  })

  it('AdminDashboard_whenRefreshClicked_shouldRefetchAndToggleLoading', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.page-header button').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.loading).toBe(false)
  })

  it('AdminDashboard_whenAutoRefreshTimerFires_shouldRefetchStatus', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(1)
    const tick = setIntervalSpy.mock.calls[0][0]
    tick()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/system-status')
  })

  it('AdminDashboard_whenStopAutoRefreshCalledTwice_shouldClearTimerOnceAndHideHint', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.refresh-hint').exists()).toBe(true)

    wrapper.vm.stopAutoRefresh()
    await flushPromises()

    expect(wrapper.vm.autoRefreshing).toBe(false)
    expect(clearIntervalSpy).toHaveBeenCalledTimes(1)
    expect(clearIntervalSpy).toHaveBeenCalledWith(12345)
    expect(wrapper.find('.refresh-hint').exists()).toBe(false)

    // 重复调用时 refreshTimer 已为 null，不再清空
    wrapper.vm.stopAutoRefresh()
    expect(clearIntervalSpy).toHaveBeenCalledTimes(1)
  })

  it('AdminDashboard_whenUnmounted_shouldStopAutoRefresh', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.unmount()

    expect(clearIntervalSpy).toHaveBeenCalledWith(12345)
    expect(wrapper.vm.autoRefreshing).toBe(false)
  })
})
