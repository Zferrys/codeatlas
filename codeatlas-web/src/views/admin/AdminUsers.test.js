import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import {
  Table as ATable, Card as ACard, PageHeader as APageHeader, Button as AButton,
  Select as ASelect, Switch as ASwitch, message
} from 'ant-design-vue'

const { apiMock } = vi.hoisted(() => ({
  apiMock: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}))

vi.mock('../../api', () => ({ default: apiMock }))

import AdminUsers from './AdminUsers.vue'

enableAutoUnmount(afterEach)

const globalComponents = {
  ATable, ACard, APageHeader, AButton, ASelect, ASelectOption: ASelect.Option, ASwitch
}

function makeUsersData() {
  return {
    records: [
      {
        id: 1, username: 'admin', email: 'admin@example.com', role: 'ADMIN', status: 1,
        createdAt: '2026-03-05T06:07:08', lastLoginAt: '2026-03-06T08:09:10'
      },
      {
        id: 2, username: 'dev', email: 'dev@example.com', role: 'ARCHITECT', status: 0,
        createdAt: null, lastLoginAt: null
      },
      {
        id: 3, username: 'coder', email: 'coder@example.com', role: 'DEVELOPER', status: 1,
        createdAt: '2026-02-01T00:00:00', lastLoginAt: '2026-02-02T00:00:00'
      },
      {
        id: 4, username: 'viewer', email: 'viewer@example.com', role: 'VIEWER', status: 0,
        createdAt: '2026-01-01T00:00:00', lastLoginAt: '2026-01-02T00:00:00'
      }
    ],
    total: 4
  }
}

function mountView() {
  return mount(AdminUsers, { global: { components: globalComponents } })
}

describe('AdminUsers', () => {
  beforeEach(() => {
    // ant-design-vue 的 Table/Select 依赖 matchMedia 与 ResizeObserver，jsdom 缺失
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
    apiMock.put.mockReset()
    apiMock.get.mockResolvedValue({ data: { data: makeUsersData() } })
    apiMock.put.mockResolvedValue({ data: { code: 200 } })
    vi.spyOn(message, 'success').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('AdminUsers_whenMounted_shouldLoadFirstPageWithDefaultPaging', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledWith('/admin/users', {
      params: { page: 1, size: 20 }
    })
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(4)
    expect(wrapper.vm.pagination.total).toBe(4)
    expect(wrapper.vm.pagination.current).toBe(1)
    expect(wrapper.vm.pagination.pageSize).toBe(20)
    expect(wrapper.vm.pagination.showSizeChanger).toBe(true)
    expect(wrapper.vm.loading).toBe(false)
  })

  it('AdminUsers_formatDate_shouldFormatAndRenderFallbacks', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.formatDate(null)).toBe('-')
    expect(wrapper.vm.formatDate('2026-03-05T06:07:08')).toBe('2026-03-05 06:07')

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].text()).toContain('2026-03-05 06:07')
    expect(rows[0].text()).toContain('2026-03-06 08:09')
    // createdAt 为空渲染 '-'，lastLoginAt 为空渲染 '从未登录'
    expect(rows[1].text()).toContain('-')
    expect(rows[1].text()).toContain('从未登录')
  })

  it('AdminUsers_whenRoleSelectChanged_shouldPutRoleAndNotify', async () => {
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    await rows[0].findComponent(ASelect).vm.$emit('change', 'VIEWER')
    await flushPromises()

    expect(apiMock.put).toHaveBeenCalledWith('/admin/users/1/role', null, {
      params: { role: 'VIEWER' }
    })
    expect(wrapper.vm.users[0].role).toBe('VIEWER')
    expect(message.success).toHaveBeenCalledWith('用户 admin 角色已更新为 VIEWER')
  })

  it('AdminUsers_whenRoleChangeFails_shouldSwallowErrorAndKeepOldRole', async () => {
    apiMock.put.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    await rows[0].findComponent(ASelect).vm.$emit('change', 'ARCHITECT')
    await flushPromises()

    expect(wrapper.vm.users[0].role).toBe('ADMIN')
    expect(message.success).not.toHaveBeenCalled()
  })

  it('AdminUsers_whenStatusSwitchedOn_shouldPutStatus1AndNotify', async () => {
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    expect(rows[0].findComponent(ASwitch).props('checked')).toBe(true)
    expect(rows[1].findComponent(ASwitch).props('checked')).toBe(false)

    await rows[1].findComponent(ASwitch).vm.$emit('change', true)
    await flushPromises()

    expect(apiMock.put).toHaveBeenCalledWith('/admin/users/2/status', null, {
      params: { status: 1 }
    })
    expect(wrapper.vm.users[1].status).toBe(1)
    expect(message.success).toHaveBeenCalledWith('用户 dev 已启用')
  })

  it('AdminUsers_whenStatusSwitchedOff_shouldPutStatus0AndNotify', async () => {
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    await rows[0].findComponent(ASwitch).vm.$emit('change', false)
    await flushPromises()

    expect(apiMock.put).toHaveBeenCalledWith('/admin/users/1/status', null, {
      params: { status: 0 }
    })
    expect(wrapper.vm.users[0].status).toBe(0)
    expect(message.success).toHaveBeenCalledWith('用户 admin 已禁用')
  })

  it('AdminUsers_whenStatusChangeFails_shouldSwallowErrorAndKeepOldStatus', async () => {
    apiMock.put.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ant-table-tbody tr.ant-table-row')
    await rows[1].findComponent(ASwitch).vm.$emit('change', true)
    await flushPromises()

    expect(wrapper.vm.users[1].status).toBe(0)
    expect(message.success).not.toHaveBeenCalled()
  })

  it('AdminUsers_paginationShowTotal_shouldRenderChineseSummary', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.pagination.showTotal(42)).toBe('共 42 条')
  })

  it('AdminUsers_whenTableChanged_shouldUpdatePagingAndReload', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findComponent(ATable).vm.$emit('change', { current: 2, pageSize: 50 })
    await flushPromises()

    expect(wrapper.vm.pagination.current).toBe(2)
    expect(wrapper.vm.pagination.pageSize).toBe(50)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/users', {
      params: { page: 2, size: 50 }
    })
    expect(apiMock.get).toHaveBeenCalledTimes(2)
  })

  it('AdminUsers_whenRefreshClicked_shouldReloadCurrentPage', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.ant-page-header button').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(apiMock.get).toHaveBeenLastCalledWith('/admin/users', {
      params: { page: 1, size: 20 }
    })
  })

  it('AdminUsers_whenLoadFails_shouldSwallowErrorAndStopLoading', async () => {
    apiMock.get.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.users).toEqual([])
    expect(wrapper.findAll('.ant-table-tbody tr.ant-table-row')).toHaveLength(0)
  })

  it('AdminUsers_whenResponseLacksRecords_shouldFallbackToEmptyListAndZeroTotal', async () => {
    apiMock.get.mockResolvedValue({ data: { data: {} } })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.vm.users).toEqual([])
    expect(wrapper.vm.pagination.total).toBe(0)
  })
})
