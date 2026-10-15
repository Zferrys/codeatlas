import { describe, it, expect, vi, beforeEach, afterEach, beforeAll } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

// ant-design-vue 采用部分 mock：组件渲染走真实实现，仅替换 message 单例便于断言，
// 否则真实 message 会把提示挂到 document.body，测试无法精确验证提示文案
vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
  }
})

vi.mock('../api', () => ({
  default: { get: vi.fn(), put: vi.fn(), post: vi.fn(), delete: vi.fn() },
  handleUnauthorized: vi.fn()
}))

import Antd, { message } from 'ant-design-vue'
import api from '../api'
import ProjectRules from './ProjectRules.vue'

// 多代理并行 + 真实 antd 首次挂载开销大，放宽用例超时避免环境抖动误报
vi.setConfig({ testTimeout: 30000 })

async function mountRules({ flush = true } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/project/:id/rules', component: ProjectRules },
      { path: '/project/:id/map', component: { template: '<div />' } }
    ]
  })
  router.push('/project/p1/rules')
  await router.isReady()
  const wrapper = mount(ProjectRules, {
    global: { plugins: [Antd, router] },
    attachTo: document.body
  })
  if (flush) await flushPromises()
  return { wrapper, router }
}

// 每次返回全新对象：组件会原地修改 record.isEnabled，共享引用会串扰用例
function buildRules() {
  return [
    {
      id: 1, name: 'No cyclic dependency', description: '禁止循环依赖',
      category: 'DEPENDENCY', severity: 'BLOCKER', isEnabled: true
    },
    {
      id: 2, name: 'Naming convention', description: '命名规范',
      category: 'NAMING', severity: 'WARN', isEnabled: false
    }
  ]
}

describe('ProjectRules.vue', () => {
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
    // antd 会以伪元素参数调用 getComputedStyle，jsdom 会打印 not-implemented 噪音
    const nativeGetComputedStyle = window.getComputedStyle.bind(window)
    vi.stubGlobal('getComputedStyle', element => nativeGetComputedStyle(element))
  })

  beforeEach(() => {
    api.get.mockReset()
    api.put.mockReset()
    message.success.mockClear()
    message.error.mockClear()
    message.warning.mockClear()
    message.info.mockClear()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  describe('fetchRules 加载', () => {
    it('fetchRules_whenLoading_shouldShowSkeletonUntilResolved', async () => {
      let resolveGet
      api.get.mockReturnValueOnce(new Promise(resolve => { resolveGet = resolve }))

      const { wrapper } = await mountRules({ flush: false })

      expect(wrapper.find('.ant-skeleton').exists()).toBe(true)

      resolveGet({ data: { data: { records: buildRules() } } })
      await flushPromises()

      expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
      expect(wrapper.findAll('.ant-table-row')).toHaveLength(2)
    })

    it('fetchRules_whenSuccess_shouldRequestWithEnabledOnlyFalseAndRenderRows', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })

      const { wrapper } = await mountRules()

      expect(api.get).toHaveBeenCalledWith('/projects/p1/rules', { params: { enabledOnly: false } })
      expect(wrapper.find('.card-title').text()).toBe('架构宪法规则')
      expect(wrapper.find('.card-count').text()).toBe('2 条规则')

      const rows = wrapper.findAll('.ant-table-row')
      expect(rows).toHaveLength(2)
      // 第一条启用：无 disabled 样式
      expect(rows[0].find('.rule-name').classes()).not.toContain('disabled')
      expect(rows[0].find('.rule-name').text()).toBe('No cyclic dependency')
      // 第二条禁用：追加 disabled 类（删除线）
      expect(rows[1].find('.rule-name').classes()).toContain('disabled')
      // 分类标签与严重度徽标
      expect(rows[0].find('.ant-tag').text()).toBe('依赖')
      expect(rows[1].find('.ant-tag').text()).toBe('命名')
      expect(rows[0].text()).toContain('BLOCKER')
      expect(rows[1].text()).toContain('WARN')
      // 两个开关的勾选状态与 isEnabled 一致
      const switches = wrapper.findAllComponents({ name: 'ASwitch' })
      expect(switches).toHaveLength(2)
      expect(switches[0].props('checked')).toBe(true)
      expect(switches[1].props('checked')).toBe(false)
    })

    it('fetchRules_whenRecordsEmpty_shouldRenderEmptyState', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: [] } } })

      const { wrapper } = await mountRules()

      expect(wrapper.find('.ant-empty').exists()).toBe(true)
      expect(wrapper.text()).toContain('暂无宪法规则')
      expect(wrapper.find('.ant-table').exists()).toBe(false)
    })

    it('fetchRules_whenDataMissing_shouldFallbackToEmptyArray', async () => {
      api.get.mockResolvedValueOnce({ data: { data: null } })

      const { wrapper } = await mountRules()

      expect(wrapper.vm.rules).toEqual([])
      expect(wrapper.find('.ant-empty').exists()).toBe(true)
    })

    it('fetchRules_whenApiErrorWithMessage_shouldRenderErrorStateWithServerMessage', async () => {
      api.get.mockRejectedValueOnce({ response: { data: { message: '服务不可用' } } })

      const { wrapper } = await mountRules()

      expect(wrapper.find('.ant-result').exists()).toBe(true)
      expect(wrapper.text()).toContain('加载失败')
      expect(wrapper.text()).toContain('服务不可用')
    })

    it('fetchRules_whenApiErrorWithoutResponse_shouldRenderDefaultMessage', async () => {
      api.get.mockRejectedValueOnce(new Error('network down'))

      const { wrapper } = await mountRules()

      expect(wrapper.text()).toContain('加载宪法规则失败')
    })

    it('fetchRules_whenRetryClicked_shouldFetchAgainAndRecover', async () => {
      api.get.mockRejectedValueOnce(new Error('boom'))
      const { wrapper } = await mountRules()
      expect(wrapper.find('.ant-result').exists()).toBe(true)

      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })
      await wrapper.find('.ant-result button').trigger('click')
      await flushPromises()

      expect(api.get).toHaveBeenCalledTimes(2)
      expect(wrapper.find('.ant-result').exists()).toBe(false)
      expect(wrapper.findAll('.ant-table-row')).toHaveLength(2)
    })
  })

  describe('toggleRule 启停', () => {
    it('toggleRule_whenEnabled_shouldPutWithEnabledTrueAndMarkRuleEnabled', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })
      api.put.mockResolvedValueOnce({ data: { data: {} } })

      const { wrapper } = await mountRules()
      const switches = wrapper.findAllComponents({ name: 'ASwitch' })

      switches[1].vm.$emit('change', true)
      await flushPromises()

      expect(api.put).toHaveBeenCalledWith('/projects/p1/rules/2/toggle', null, { params: { enabled: true } })
      expect(wrapper.vm.rules.find(r => r.id === 2).isEnabled).toBe(true)
      expect(message.success).toHaveBeenCalledWith('规则已启用')
    })

    it('toggleRule_whenDisabled_shouldPutWithEnabledFalseAndShowDisabledMessage', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })
      api.put.mockResolvedValueOnce({ data: { data: {} } })

      const { wrapper } = await mountRules()

      wrapper.findAllComponents({ name: 'ASwitch' })[0].vm.$emit('change', false)
      await flushPromises()

      expect(api.put).toHaveBeenCalledWith('/projects/p1/rules/1/toggle', null, { params: { enabled: false } })
      expect(wrapper.vm.rules.find(r => r.id === 1).isEnabled).toBe(false)
      expect(message.success).toHaveBeenCalledWith('规则已禁用')
    })

    it('toggleRule_whenRuleMissingLocally_shouldNotThrowAndStillSucceed', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })
      api.put.mockResolvedValueOnce({ data: { data: {} } })

      const { wrapper } = await mountRules()

      await wrapper.vm.toggleRule(999, true)

      expect(api.put).toHaveBeenCalledWith('/projects/p1/rules/999/toggle', null, { params: { enabled: true } })
      expect(message.success).toHaveBeenCalledWith('规则已启用')
    })

    it('toggleRule_whenApiFails_shouldShowOperationFailed', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })
      api.put.mockRejectedValueOnce(new Error('500'))

      const { wrapper } = await mountRules()

      await wrapper.vm.toggleRule(1, false)

      expect(message.error).toHaveBeenCalledWith('操作失败')
      expect(wrapper.vm.rules.find(r => r.id === 1).isEnabled).toBe(true)
    })

    it('toggleRule_whenApiFailsWithServerMessage_shouldShowServerMessage', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: buildRules() } } })
      api.put.mockRejectedValueOnce({ response: { data: { message: '无权限操作' } } })

      const { wrapper } = await mountRules()

      await wrapper.vm.toggleRule(1, false)

      expect(message.error).toHaveBeenCalledWith('无权限操作')
      expect(wrapper.vm.rules.find(r => r.id === 1).isEnabled).toBe(true)
    })
  })

  describe('映射函数', () => {
    it('categoryColor_whenKnownOrUnknownCategory_shouldMapOrFallbackDefault', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: [] } } })
      const { wrapper } = await mountRules()
      const vm = wrapper.vm

      expect(vm.categoryColor('DEPENDENCY')).toBe('red')
      expect(vm.categoryColor('STRUCTURE')).toBe('blue')
      expect(vm.categoryColor('NAMING')).toBe('green')
      expect(vm.categoryColor('OTHER')).toBe('default')
      expect(vm.categoryColor(undefined)).toBe('default')
    })

    it('categoryLabel_whenKnownOrUnknownCategory_shouldMapOrReturnOriginal', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: [] } } })
      const { wrapper } = await mountRules()
      const vm = wrapper.vm

      expect(vm.categoryLabel('DEPENDENCY')).toBe('依赖')
      expect(vm.categoryLabel('STRUCTURE')).toBe('结构')
      expect(vm.categoryLabel('NAMING')).toBe('命名')
      expect(vm.categoryLabel('OTHER')).toBe('OTHER')
    })

    it('severityBadge_whenKnownOrUnknownSeverity_shouldMapOrFallbackDefault', async () => {
      api.get.mockResolvedValueOnce({ data: { data: { records: [] } } })
      const { wrapper } = await mountRules()
      const vm = wrapper.vm

      expect(vm.severityBadge('BLOCKER')).toBe('error')
      expect(vm.severityBadge('ERROR')).toBe('processing')
      expect(vm.severityBadge('WARN')).toBe('warning')
      expect(vm.severityBadge('INFO')).toBe('default')
      expect(vm.severityBadge('X')).toBe('default')
    })

    it('template_whenMixedCategoryAndSeverity_shouldRenderTagsAndBadges', async () => {
      api.get.mockResolvedValueOnce({
        data: {
          data: {
            records: [
              { id: 1, name: 'a', description: 'd1', category: 'STRUCTURE', severity: 'ERROR', isEnabled: true },
              { id: 2, name: 'b', description: 'd2', category: 'OTHER', severity: 'INFO', isEnabled: true },
              { id: 3, name: 'c', description: 'd3', category: 'NAMING', severity: 'X', isEnabled: false }
            ]
          }
        }
      })

      const { wrapper } = await mountRules()

      const rows = wrapper.findAll('.ant-table-row')
      expect(rows[0].find('.ant-tag').text()).toBe('结构')
      expect(rows[0].text()).toContain('ERROR')
      // 未知分类回退到原始值展示
      expect(rows[1].find('.ant-tag').text()).toBe('OTHER')
      expect(rows[1].text()).toContain('INFO')
      expect(rows[2].find('.ant-tag').text()).toBe('命名')
      expect(rows[2].text()).toContain('X')
    })
  })
})
