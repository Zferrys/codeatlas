import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import {
  Table as ATable, Card as ACard, PageHeader as APageHeader, Button as AButton,
  Tag as ATag, Tooltip as ATooltip
} from 'ant-design-vue'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}))

vi.mock('../../api', () => ({ default: apiMock }))

import AdminAuditLog from './AdminAuditLog.vue'

enableAutoUnmount(afterEach)

const globalComponents = { ATable, ACard, APageHeader, AButton, ATag, ATooltip }

function makeLogsData() {
  return {
    records: [
      {
        id: 1, username: 'admin', action: 'LOGIN', targetType: 'SESSION', targetId: 100,
        detail: '登录成功', ipAddress: '127.0.0.1', createdAt: '2026-03-05T06:07:08'
      },
      {
        id: 2, username: 'dev', action: 'TRIGGER_SCAN', targetType: null, targetId: 200,
        detail: '触发扫描', ipAddress: '10.1.2.3', createdAt: null
      },
      {
        id: 3, username: 'ghost', action: 'UNKNOWN_ACTION', targetType: 'PROJECT', targetId: 300,
        detail: '未知操作', ipAddress: null, createdAt: '2026-01-02T12:30:45'
      }
    ],
    total: 3
  }
}

function mountView() {
  return mount(AdminAuditLog, { global: { components: globalComponents } })
}

describe('AdminAuditLog', () => {
  beforeEach(() => {
    // ant-design-vue 的 Table 通过 useBreakpoint 订阅响应式断点，jsdom 无 matchMedia
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
    apiMock.get.mockResolvedValue({ data: { data: makeLogsData() } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('AdminAuditLog_whenMounted_shouldLoadFirstPageWithDefaultPaging', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledWith('/admin/audit-log', {
      params: { page: 1, size: 20 }
    })
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(3)
    expect(wrapper.vm.pagination.total).toBe(3)
    expect(wrapper.vm.pagination.current).toBe(1)
    expect(wrapper.vm.pagination.pageSize).toBe(20)
    expect(wrapper.vm.pagination.showSizeChanger).toBe(true)
    expect(wrapper.vm.loading).toBe(false)
  })

  it('AdminAuditLog_actionColor_shouldMapKnownActionsAndFallbackToDefault', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.actionColor('CREATE_PROJECT')).toBe('green')
    expect(wrapper.vm.actionColor('DELETE_PROJECT')).toBe('red')
    expect(wrapper.vm.actionColor('UPDATE_PROJECT')).toBe('blue')
    expect(wrapper.vm.actionColor('TRIGGER_SCAN')).toBe('purple')
    expect(wrapper.vm.actionColor('LOGIN')).toBe('cyan')
    expect(wrapper.vm.actionColor('REGISTER')).toBe('geekblue')
    expect(wrapper.vm.actionColor('WHATEVER')).toBe('default')

    // 表格中的操作标签渲染颜色（UNKNOWN_ACTION 走 default 分支）
    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].find('.ant-tag').text()).toBe('LOGIN')
    expect(rows[2].find('.ant-tag').text()).toBe('UNKNOWN_ACTION')
  })

  it('AdminAuditLog_ipHelpers_shouldDetectLocalAndFormatDisplay', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.isLocalIp(null)).toBe(true)
    expect(wrapper.vm.isLocalIp('127.0.0.1')).toBe(true)
    expect(wrapper.vm.isLocalIp('0:0:0:0:0:0:0:1')).toBe(true)
    expect(wrapper.vm.isLocalIp('::1')).toBe(true)
    expect(wrapper.vm.isLocalIp('10.1.2.3')).toBe(false)

    expect(wrapper.vm.formatIp(null)).toBe('-')
    expect(wrapper.vm.formatIp('127.0.0.1')).toBe('本地访问')
    expect(wrapper.vm.formatIp('10.1.2.3')).toBe('10.1.2.3')

    // 表格渲染：本地访问灰色样式、缺失 IP 显示 '-'
    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].text()).toContain('本地访问')
    expect(rows[1].text()).toContain('10.1.2.3')
    expect(rows[2].text()).toContain('-')
    const localIpCell = rows[0].findAll('.ant-table-cell').find((c) => c.text() === '本地访问')
    expect(localIpCell.find('span').attributes('style')).toContain('color: rgb(153, 153, 153)')
    const remoteIpCell = rows[1].findAll('.ant-table-cell').find((c) => c.text() === '10.1.2.3')
    expect(remoteIpCell.find('span').attributes('style')).toContain('color: rgb(51, 51, 51)')
  })

  it('AdminAuditLog_formatDate_shouldFormatAndFallbackToDash', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.formatDate(null)).toBe('-')
    expect(wrapper.vm.formatDate('2026-03-05T06:07:08')).toBe('2026-03-05 06:07:08')

    // 表格渲染：null 时间显示 '-'
    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].text()).toContain('2026-03-05 06:07:08')
    expect(rows[1].text()).toContain('-')
  })

  it('AdminAuditLog_targetTypeCell_shouldRenderTagOrDash', async () => {
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].text()).toContain('SESSION')
    // targetType 为空渲染 '-'
    const cells = rows[1].findAll('.ant-table-cell')
    expect(cells.some((c) => c.text() === '-')).toBe(true)
  })

  it('AdminAuditLog_paginationShowTotal_shouldRenderChineseSummary', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.pagination.showTotal(42)).toBe('共 42 条')
  })

  it('AdminAuditLog_whenTableChanged_shouldUpdatePagingAndReload', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findComponent(ATable).vm.$emit('change', { current: 2, pageSize: 50 })
    await flushPromises()

    expect(wrapper.vm.pagination.current).toBe(2)
    expect(wrapper.vm.pagination.pageSize).toBe(50)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/audit-log', {
      params: { page: 2, size: 50 }
    })
    expect(apiMock.get).toHaveBeenCalledTimes(2)
  })

  it('AdminAuditLog_whenRefreshClicked_shouldReloadCurrentPage', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.ant-page-header button').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/audit-log', {
      params: { page: 1, size: 20 }
    })
  })

  it('AdminAuditLog_whenLoadFails_shouldSwallowErrorAndStopLoading', async () => {
    apiMock.get.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.logs).toEqual([])
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(0)
  })

  it('AdminAuditLog_whenResponseLacksRecords_shouldFallbackToEmptyListAndZeroTotal', async () => {
    apiMock.get.mockResolvedValue({ data: { data: {} } })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.logs).toEqual([])
    expect(wrapper.vm.pagination.total).toBe(0)
  })
})
