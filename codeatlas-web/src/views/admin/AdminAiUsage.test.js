import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import {
  Table as ATable, Card as ACard, PageHeader as APageHeader, Button as AButton,
  Tag as ATag, Tooltip as ATooltip, Row as ARow, Col as ACol, Statistic as AStatistic,
  Form as AForm, FormItem as AFormItem, Input as AInput, Select as ASelect,
  RangePicker as ARangePicker
} from 'ant-design-vue'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}))

vi.mock('../../api', () => ({ default: apiMock }))

import AdminAiUsage from './AdminAiUsage.vue'

enableAutoUnmount(afterEach)

const globalComponents = {
  ATable, ACard, APageHeader, AButton, ATag, ATooltip, ARow, ACol, AStatistic,
  AForm, AFormItem, AInput, ASelect, ARangePicker
}

function makeRecords() {
  return [
    {
      id: 1, createdAt: '2026-10-17T09:15:30', userId: 1, username: 'zferry',
      model: 'deepseek-v4-pro', stage: 'ARCHITECTURE_STORY', projectId: 3,
      promptTokens: 1200, completionTokens: 800, totalTokens: 2000,
      latencyMs: 4500, cost: 0.016, success: 1, errorMessage: null
    },
    {
      id: 2, createdAt: null, userId: null, username: null,
      model: 'claude-sonnet-4-6', stage: 'CONTEXT_QA', projectId: null,
      promptTokens: 0, completionTokens: 0, totalTokens: 0,
      latencyMs: 0, cost: 0, success: 0, errorMessage: 'AI call failed'
    },
    {
      id: 3, createdAt: '2026-10-17T10:00:00', userId: 2, username: 'alice',
      model: 'deepseek-v4-flash', stage: 'UNKNOWN_STAGE', projectId: 1,
      promptTokens: 10, completionTokens: 5, totalTokens: 15,
      latencyMs: 10, cost: 0.00012, success: 1, errorMessage: null
    }
  ]
}

function makeSummary() {
  return {
    totals: { calls: 3, successCalls: 2, totalTokens: 2015, totalCost: 0.01612 },
    byModel: [], byStage: [], byUser: [],
    budget: { usedTokens: 1_000_000, monthlyLimit: 10_000_000, usagePercent: 10 }
  }
}

function mockApi() {
  apiMock.get.mockImplementation((url) => {
    if (url === '/admin/ai-usage') {
      return Promise.resolve({ data: { data: { records: makeRecords(), total: 3 } } })
    }
    if (url === '/admin/ai-usage/summary') {
      return Promise.resolve({ data: { data: makeSummary() } })
    }
    return Promise.resolve({ data: { data: {} } })
  })
}

function mountView() {
  return mount(AdminAiUsage, { global: { components: globalComponents } })
}

describe('AdminAiUsage', () => {
  beforeEach(() => {
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
    apiMock.get.mockReset()
    mockApi()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('AdminAiUsage_whenMounted_shouldLoadFirstPageAndSummary', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledWith('/admin/ai-usage', {
      params: { page: 1, size: 20 }
    })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/ai-usage/summary', { params: {} })
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(3)
    expect(wrapper.vm.pagination.total).toBe(3)
    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.summary.totals.calls).toBe(3)
    expect(wrapper.vm.summary.budget.usagePercent).toBe(10)
    expect(wrapper.text()).toContain('区间调用次数')
    expect(wrapper.text()).toContain('本月预算使用率')
  })

  it('AdminAiUsage_rows_shouldRenderUserModelStageStatusAndFallbacks', async () => {
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].text()).toContain('zferry')
    expect(rows[0].text()).toContain('deepseek-v4-pro')
    expect(rows[0].text()).toContain('架构叙事')
    expect(rows[0].text()).toContain('2026-10-17 09:15:30')
    expect(rows[0].text()).toContain('0.01600')
    // 无用户名/项目/时间时回退 '-'
    expect(rows[1].text()).toContain('-')
    expect(rows[1].text()).toContain('失败')
    // 未知阶段原样展示
    expect(rows[2].text()).toContain('UNKNOWN_STAGE')
    expect(rows[2].text()).toContain('成功')
  })

  it('AdminAiUsage_helpers_shouldFormatValuesAndMapStages', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.stageLabel('ARCHITECTURE_STORY')).toBe('架构叙事')
    // 后端审计阶段常量是 ANTI_PATTERN_DETECT（insight 类型才是 ANTI_PATTERN）
    expect(wrapper.vm.stageLabel('ANTI_PATTERN_DETECT')).toBe('反模式检测')
    expect(wrapper.vm.stageColor('ANTI_PATTERN_DETECT')).toBe('orange')
    expect(wrapper.vm.stageLabel('UNKNOWN_STAGE')).toBe('UNKNOWN_STAGE')
    expect(wrapper.vm.stageLabel(null)).toBe('-')
    expect(wrapper.vm.stageColor('CONTEXT_QA')).toBe('green')
    expect(wrapper.vm.stageColor('UNKNOWN_STAGE')).toBe('default')
    expect(wrapper.vm.formatDate(null)).toBe('-')
    expect(wrapper.vm.formatDate('2026-03-05T06:07:08')).toBe('2026-03-05 06:07:08')
    expect(wrapper.vm.formatNumber(12000)).toBe('12,000')
    expect(wrapper.vm.formatNumber(null)).toBe('-')
    expect(wrapper.vm.formatCost(0.016)).toBe('0.01600')
    expect(wrapper.vm.formatCost(null)).toBe('-')
    expect(wrapper.vm.stageOptions).toHaveLength(7)
    expect(wrapper.vm.successOptions).toHaveLength(2)
  })

  it('AdminAiUsage_whenFiltersApplied_shouldPassAllParamsAndResetPage', async () => {
    const wrapper = mountView()
    await flushPromises()
    wrapper.vm.pagination.current = 3

    wrapper.vm.filters.model = ' deepseek-v4-pro '
    wrapper.vm.filters.stage = 'CONTEXT_QA'
    wrapper.vm.filters.username = ' zferry '
    wrapper.vm.filters.success = true
    wrapper.vm.filters.timeRange = ['2026-10-01 00:00:00', '2026-10-18 00:00:00']
    await wrapper.vm.onSearch()
    await flushPromises()

    const expectedFilters = {
      model: 'deepseek-v4-pro',
      stage: 'CONTEXT_QA',
      username: 'zferry',
      success: true,
      startTime: '2026-10-01 00:00:00',
      endTime: '2026-10-18 00:00:00'
    }
    expect(apiMock.get).toHaveBeenCalledWith('/admin/ai-usage', {
      params: { page: 1, size: 20, ...expectedFilters }
    })
    expect(apiMock.get).toHaveBeenCalledWith('/admin/ai-usage/summary', {
      params: expectedFilters
    })
    expect(wrapper.vm.pagination.current).toBe(1)
  })

  it('AdminAiUsage_whenReset_shouldClearFiltersAndReload', async () => {
    const wrapper = mountView()
    await flushPromises()

    wrapper.vm.filters.model = 'deepseek-v4-pro'
    wrapper.vm.filters.stage = 'CONTEXT_QA'
    wrapper.vm.filters.success = false
    wrapper.vm.filters.timeRange = ['2026-10-01 00:00:00', '2026-10-18 00:00:00']
    await wrapper.vm.onReset()
    await flushPromises()

    expect(wrapper.vm.filters.model).toBe('')
    expect(wrapper.vm.filters.stage).toBeUndefined()
    expect(wrapper.vm.filters.success).toBeUndefined()
    expect(wrapper.vm.filters.timeRange).toBeNull()
    expect(apiMock.get).toHaveBeenCalledWith('/admin/ai-usage', {
      params: { page: 1, size: 20 }
    })
  })

  it('AdminAiUsage_whenTableChanged_shouldUpdatePagingAndReload', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findComponent(ATable).vm.$emit('change', { current: 2, pageSize: 50 })
    await flushPromises()

    expect(wrapper.vm.pagination.current).toBe(2)
    expect(wrapper.vm.pagination.pageSize).toBe(50)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/ai-usage', {
      params: { page: 2, size: 50 }
    })
  })

  it('AdminAiUsage_whenRefreshClicked_shouldReloadListAndSummary', async () => {
    const wrapper = mountView()
    await flushPromises()
    const initialCalls = apiMock.get.mock.calls.length

    await wrapper.find('.ant-page-header button').trigger('click')
    await flushPromises()

    expect(apiMock.get.mock.calls.length).toBe(initialCalls + 2)
  })

  it('AdminAiUsage_whenLoadFails_shouldSwallowErrorAndStopLoading', async () => {
    apiMock.get.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.records).toEqual([])
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(0)
  })

  it('AdminAiUsage_whenSummaryPayloadIncomplete_shouldFallbackToEmptyObjects', async () => {
    apiMock.get.mockImplementation((url) => {
      if (url === '/admin/ai-usage/summary') {
        return Promise.resolve({ data: { data: {} } })
      }
      return Promise.resolve({ data: { data: { records: [], total: 0 } } })
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.summary.totals).toEqual({})
    expect(wrapper.vm.summary.budget).toEqual({})
  })
})
