import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent } from 'vue'
import Antd, { message, Modal, Popconfirm, Tooltip } from 'ant-design-vue'

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn()
}))

vi.mock('@/api', () => ({
  default: { get, post, delete: del },
  handleUnauthorized: vi.fn()
}))

vi.mock('@/composables/useAiTask', async () => {
  const { ref } = await import('vue')
  return {
    AI_TASK_CANCELLED: Symbol('AI_TASK_CANCELLED'),
    runAiTask: vi.fn(),
    cancelActiveAiTask: vi.fn(),
    activeTask: ref(null)
  }
})

import ProjectInsights from './ProjectInsights.vue'
import { runAiTask, cancelActiveAiTask, activeTask, AI_TASK_CANCELLED } from '@/composables/useAiTask'

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

const historyItem = (overrides = {}) => ({
  id: 1,
  scope: 'GLOBAL',
  question: 'Q1',
  answer: 'A1',
  createdAt: '2026-01-01T10:00:00',
  contextFqn: null,
  verification: null,
  ...overrides
})

// 生产环境 antd Empty 的 description prop 优先于 #description slot（slot 永不渲染，见报告），
// 该替代 stub 强制渲染 slot 内容，用于验证 slot 模板自身正确并保持对文案回归的敏感度
const SlotRenderingEmptyStub = {
  name: 'AEmpty',
  template: '<div class="empty-slot-stub"><slot name="description" /></div>'
}

/** 按 scope 返回不同历史数据；传 null 表示该 scope 接口 data 为 null（覆盖 `|| []` 回退） */
function mockHistory({ global = [], class: classItems = [], impact = [] } = {}) {
  get.mockImplementation((url, config) => {
    if (String(url).endsWith('/chat-history')) {
      const scope = config?.params?.scope
      const map = { GLOBAL: global, CLASS: classItems, IMPACT: impact }
      return Promise.resolve({ data: { data: map[scope] ?? null } })
    }
    return Promise.reject(new Error('unexpected url ' + url))
  })
}

async function mountView(historyOptions, path = '/projects/1') {
  mockHistory(historyOptions)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:id', component: { template: '<div/>' } },
      { path: '/outside', component: { template: '<div/>' } }
    ]
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(ProjectInsights, {
    global: { plugins: [router, Antd] }
  })
  await flushPromises()
  return { wrapper, router }
}

// antd Button 会给双汉字标签插入空格（如「提 问」），比较前统一去空白
const findButtonByText = (scope, text) => {
  const target = text.replace(/\s+/g, '')
  return scope.findAll('button').find(b => b.text().replace(/\s+/g, '').includes(target))
}

describe('ProjectInsights', () => {
  let warningSpy
  let successSpy

  beforeEach(() => {
    localStorage.clear()
    runAiTask.mockReset()
    cancelActiveAiTask.mockReset()
    activeTask.value = null
    warningSpy = vi.spyOn(message, 'warning').mockImplementation(() => {})
    successSpy = vi.spyOn(message, 'success').mockImplementation(() => {})
    vi.spyOn(message, 'error').mockImplementation(() => {})
    vi.spyOn(message, 'info').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('onActivated_whenKeptAlive_shouldSkipFirstActivationThenReloadOnReturn', async () => {
    const Host = defineComponent({
      components: { ProjectInsights },
      template: '<KeepAlive><ProjectInsights v-if="alive" /></KeepAlive>',
      data: () => ({ alive: true })
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    mockHistory({ global: [historyItem()] })

    const wrapper = mount(Host, { global: { plugins: [router, Antd] } })
    await flushPromises()
    const initialCalls = get.mock.calls.length // onMounted 加载一次

    wrapper.vm.alive = false
    await flushPromises()
    wrapper.vm.alive = true // keep-alive 切回，activated 触发刷新
    await flushPromises()

    expect(get.mock.calls.length).toBeGreaterThan(initialCalls)
  })

  it('loadHistory_whenSuccess_shouldMergeSortAndRenderAllItems', async () => {
    const { wrapper } = await mountView({
      global: [
        historyItem({ id: 1, question: '全局旧问', createdAt: '2026-01-01T10:00:00' }),
        historyItem({ id: 2, question: '<b>全局</b> 新问 #标题', answer: 'x'.repeat(200), createdAt: '2026-03-01T10:00:00', contextFqn: 'com.demo.PayService' })
      ],
      class: null, // 覆盖 `classRes.data.data || []`
      impact: [historyItem({ id: 3, question: '影响模拟问', scope: 'IMPACT', createdAt: '2026-02-01T10:00:00' })]
    })

    expect(get).toHaveBeenCalledWith('/projects/1/chat-history', { params: { scope: 'GLOBAL' } })
    expect(get).toHaveBeenCalledWith('/projects/1/chat-history', { params: { scope: 'CLASS' } })
    expect(get).toHaveBeenCalledWith('/projects/1/chat-history', { params: { scope: 'IMPACT' } })

    const items = wrapper.findAll('.history-item')
    expect(items).toHaveLength(3)
    // 按 createdAt 倒序：全局新问 → 影响模拟问 → 全局旧问
    expect(items[0].text()).toContain('全局')
    expect(items[1].text()).toContain('影响模拟问')
    expect(items[2].text()).toContain('全局旧问')
    expect(wrapper.find('.history-count').text()).toBe('3 / 3 条')
    // contextFqn 标签渲染短名
    expect(items[0].text()).toContain('PayService')
    // 长回答预览截断
    expect(items[0].find('.history-preview').text().endsWith('...')).toBe(true)
  })

  it('loadHistory_whenSomeItemsLackCreatedAt_shouldSortWithZeroFallback', async () => {
    const { wrapper } = await mountView({
      global: [
        historyItem({ id: 71, question: '无时间A', createdAt: null }),
        historyItem({ id: 72, question: '有时间', createdAt: '2026-05-01T10:00:00' }),
        historyItem({ id: 73, question: '无时间B', createdAt: undefined })
      ]
    })

    // 排序比较器对缺失 createdAt 的项回退 0，不抛异常且全部渲染
    expect(wrapper.findAll('.history-item')).toHaveLength(3)
    expect(wrapper.find('.history-count').text()).toBe('3 / 3 条')
  })

  it('loadHistory_whenRequestFails_shouldRenderGlobalEmptyState', async () => {
    get.mockRejectedValue(new Error('boom'))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    const wrapper = mount(ProjectInsights, { global: { plugins: [router, Antd] } })
    await flushPromises()

    expect(wrapper.findAll('.history-item')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无对话历史')
  })

  it('loadHistory_whenLoading_shouldShowHistorySkeleton', async () => {
    const resolvers = []
    get.mockImplementation(() => new Promise(resolve => { resolvers.push(resolve) }))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    const wrapper = mount(ProjectInsights, { global: { plugins: [router, Antd] } })
    await flushPromises()

    expect(wrapper.find('.qa-history-card .ant-skeleton').exists()).toBe(true)

    // 三个 scope 的 data 均为 null：覆盖 `xxxRes.data.data || []` 回退
    resolvers.forEach(resolve => resolve({ data: { data: null } }))
    await flushPromises()
    expect(wrapper.find('.qa-history-card .ant-skeleton').exists()).toBe(false)
    expect(wrapper.findAll('.history-item')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无对话历史')
  })

  it('historyFilter_whenSwitched_shouldFilterByScopeAndShowCount', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 1, question: '全局问' })],
      runAiTask: undefined,
      impact: [historyItem({ id: 3, question: '影响问', scope: 'IMPACT' })]
    })

    expect(wrapper.findAll('.history-item')).toHaveLength(2)

    const radios = wrapper.findAll('.ant-radio-button-wrapper input')
    await radios[1].setValue() // GLOBAL
    await flushPromises()
    expect(wrapper.findAll('.history-item')).toHaveLength(1)
    expect(wrapper.find('.history-count').text()).toBe('1 / 2 条')

    await radios[3].setValue() // IMPACT
    await flushPromises()
    expect(wrapper.find('.history-item').text()).toContain('影响问')

    await radios[0].setValue() // 全部
    await flushPromises()
    expect(wrapper.findAll('.history-item')).toHaveLength(2)
  })

  it('historyFilter_whenNoMatch_shouldRenderEmptyHistoryAndKeepTotalCount', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 1 })]
    })

    const radios = wrapper.findAll('.ant-radio-button-wrapper input')
    await radios[2].setValue() // CLASS：无该类记录
    await flushPromises()

    expect(wrapper.findAll('.history-item')).toHaveLength(0)
    // 注意：a-empty 同时给了 description prop 和 #description slot，
    // antd v4 中 prop 优先于 slot，slot 内的「当前筛选下没有记录」永远不渲染（见测试报告）
    expect(wrapper.find('.qa-history-card .ant-empty-description').text()).toBe('暂无对话历史')
    expect(wrapper.find('.history-count').text()).toBe('0 / 1 条')
  })

  it('emptyDescriptionSlot_whenNoHistoryAtAll_shouldRenderGuidanceWhenSlotIsInvoked', async () => {
    mockHistory({})
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    const wrapper = mount(ProjectInsights, {
      global: { plugins: [router, Antd], stubs: { AEmpty: SlotRenderingEmptyStub } }
    })
    await flushPromises()

    // 借由渲染 slot 的 stub 执行「无任何历史」分支的模板内容
    expect(wrapper.find('.empty-slot-stub').text())
      .toContain('在智能问答提问、或在类关系图谱中做影响模拟后，记录会自动保存在这里统一管理')
  })

  it('emptyDescriptionSlot_whenOnlyFilteredOut_shouldRenderFilterHintWhenSlotIsInvoked', async () => {
    mockHistory({ global: [historyItem({ id: 1 })] })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
    })
    router.push('/projects/1')
    await router.isReady()
    const wrapper = mount(ProjectInsights, {
      global: { plugins: [router, Antd], stubs: { AEmpty: SlotRenderingEmptyStub } }
    })
    await flushPromises()

    const radios = wrapper.findAll('.ant-radio-button-wrapper input')
    await radios[2].setValue() // CLASS：有历史但被筛选排除
    await flushPromises()

    expect(wrapper.find('.empty-slot-stub').text()).toContain('当前筛选下没有记录')
    expect(wrapper.find('.empty-slot-stub').text()).not.toContain('在智能问答提问')
  })

  it('ask_whenQuestionEmpty_shouldWarnWithoutRequest', async () => {
    const { wrapper } = await mountView({})

    // 空问题下按钮被 disabled 拦截，改用回车路径触发 ask 的守卫分支
    const preventDefault = vi.fn()
    wrapper.vm.onPressEnter({ shiftKey: false, preventDefault })
    await flushPromises()

    expect(warningSpy).toHaveBeenCalledWith('请输入问题')
    expect(runAiTask).not.toHaveBeenCalled()
  })

  it('ask_whenButtonDisabled_shouldNotFireRequest', async () => {
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('   ')
    const askBtn = findButtonByText(wrapper, '提问')
    expect(askBtn.attributes('disabled')).toBeDefined()

    await askBtn.trigger('click')
    await flushPromises()

    expect(runAiTask).not.toHaveBeenCalled()
    expect(wrapper.find('.answer-loading').exists()).toBe(false)
  })

  it('ask_whenSuccess_shouldPostWithTaskHeaderAndRenderVerifiedAnswer', async () => {
    runAiTask.mockImplementation(async ({ execute }) => execute({ taskId: 'task-1', signal: 'sig-1' }))
    post.mockResolvedValue({
      data: {
        data: {
          answer: '## 结论\n支付相关类有 3 个',
          verification: { unsourced: [], relationMismatches: [], classRefsChecked: 3, relationClaimsChecked: 1 }
        }
      }
    })
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('支付相关代码？')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    expect(post).toHaveBeenCalledWith(
      '/projects/1/ai/context-qa',
      { question: '支付相关代码？' },
      { headers: { 'X-Ai-Task-Id': 'task-1' }, signal: 'sig-1' }
    )
    expect(runAiTask).toHaveBeenCalledWith(expect.objectContaining({ projectId: '1', label: '全局问答' }))
    const answerBox = wrapper.find('.answer-box')
    expect(answerBox.exists()).toBe(true)
    expect(answerBox.find('.markdown-body').html()).toContain('<h2')
    expect(answerBox.text()).toContain('支付相关类有 3 个')
    expect(answerBox.text()).toContain('回答已保存到下方对话历史')
    const verifyTag = answerBox.find('.verify-line .ant-tag')
    expect(verifyTag.text()).toBe('证据校验通过')
    expect(verifyTag.classes()).toContain('ant-tag-green')
    expect(answerBox.findComponent(Tooltip).props('title')).toBe('已校验 3 个类引用、1 条关系声明，均通过')
    // 成功后刷新历史
    expect(get).toHaveBeenCalledWith('/projects/1/chat-history', { params: { scope: 'GLOBAL' } })
  })

  it('ask_whenVerificationCorrected_shouldRenderBlueCorrectedTag', async () => {
    runAiTask.mockImplementation(async ({ execute }) => execute({ taskId: 't', signal: 's' }))
    post.mockResolvedValue({
      data: {
        data: {
          answer: '答案',
          verification: { corrected: true, unsourced: ['A.java#L1'], classRefsChecked: 1 }
        }
      }
    })
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    const verifyTag = wrapper.find('.answer-box .verify-line .ant-tag')
    expect(verifyTag.text()).toBe('证据校验：已自动修正')
    expect(verifyTag.classes()).toContain('ant-tag-blue')
    expect(wrapper.findComponent(Tooltip).props('title')).toBe('未溯源引用: A.java#L1')
  })

  it('ask_whenVerificationHasIssues_shouldRenderOrangeTagWithCount', async () => {
    runAiTask.mockImplementation(async ({ execute }) => execute({ taskId: 't', signal: 's' }))
    post.mockResolvedValue({
      data: {
        data: {
          answer: '答案',
          verification: { unsourced: ['X.java'], relationMismatches: ['Y 调用 Z'], classRefsChecked: 2 }
        }
      }
    })
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    const verifyTag = wrapper.find('.answer-box .verify-line .ant-tag')
    expect(verifyTag.text()).toBe('证据校验：2 项异常')
    expect(verifyTag.classes()).toContain('ant-tag-orange')
    expect(wrapper.findComponent(Tooltip).props('title')).toBe('未溯源引用: X.java；关系声明存疑: Y 调用 Z')
  })

  it('ask_whenResponseHasNoAnswer_shouldRenderFallbackText', async () => {
    runAiTask.mockImplementation(async ({ execute }) => execute({ taskId: 't', signal: 's' }))
    post.mockResolvedValue({ data: { data: {} } })
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    expect(wrapper.find('.answer-box').text()).toContain('未获取到回答')
  })

  it('ask_whenVerificationMissing_shouldHideVerifyLine', async () => {
    runAiTask.mockImplementation(async ({ execute }) => execute({ taskId: 't', signal: 's' }))
    post.mockResolvedValue({ data: { data: { answer: '无校验答案' } } })
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    expect(wrapper.find('.answer-box').exists()).toBe(true)
    expect(wrapper.find('.answer-box .verify-line').exists()).toBe(false)
  })

  it('ask_whenTaskCancelled_shouldSkipAnswerRendering', async () => {
    runAiTask.mockResolvedValue(AI_TASK_CANCELLED)
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    expect(post).not.toHaveBeenCalled()
    expect(wrapper.find('.answer-box').exists()).toBe(false)
    expect(wrapper.find('.answer-loading').exists()).toBe(false)
  })

  it('ask_whenRequestFails_shouldSilentlyResetAsking', async () => {
    runAiTask.mockRejectedValue(new Error('server down'))
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    expect(wrapper.find('.answer-loading').exists()).toBe(false)
    expect(wrapper.find('.answer-box').exists()).toBe(false)
  })

  it('ask_whenInFlight_shouldShowActiveTaskProgressAndCancelButton', async () => {
    let resolveTask
    runAiTask.mockImplementation(() => new Promise(resolve => { resolveTask = resolve }))
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    // 模拟 useAiTask 内部上报了活跃任务
    activeTask.value = { stage: '正在语义检索', label: '影响模拟', elapsedSec: 12 }
    await flushPromises()

    const loadingBox = wrapper.find('.answer-loading')
    expect(loadingBox.exists()).toBe(true)
    expect(loadingBox.text()).toContain('正在语义检索')
    expect(loadingBox.text()).toContain('影响模拟')
    expect(loadingBox.text()).toContain('已等待 12 秒')

    await findButtonByText(loadingBox, '终止').trigger('click')
    expect(cancelActiveAiTask).toHaveBeenCalledTimes(1)

    resolveTask(AI_TASK_CANCELLED)
    await flushPromises()
    expect(wrapper.find('.answer-loading').exists()).toBe(false)
  })

  it('ask_whenNoActiveTaskInfo_shouldShowDefaultProgressText', async () => {
    let resolveTask
    runAiTask.mockImplementation(() => new Promise(resolve => { resolveTask = resolve }))
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()

    const loadingBox = wrapper.find('.answer-loading')
    expect(loadingBox.text()).toContain('AI 正在搜索代码库并组织回答…')
    expect(loadingBox.text()).toContain('全局问答')
    expect(loadingBox.text()).toContain('已等待 0 秒')

    resolveTask(AI_TASK_CANCELLED)
    await flushPromises()
  })

  it('onPressEnter_whenPlainEnter_shouldPreventDefaultAndAsk', async () => {
    runAiTask.mockResolvedValue(AI_TASK_CANCELLED)
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('回车问题')
    const preventDefault = vi.fn()
    wrapper.vm.onPressEnter({ shiftKey: false, preventDefault })
    await flushPromises()

    expect(preventDefault).toHaveBeenCalled()
    expect(runAiTask).toHaveBeenCalledWith(expect.objectContaining({ label: '全局问答' }))
  })

  it('onPressEnter_whenShiftEnter_shouldNotAsk', async () => {
    const { wrapper } = await mountView({})

    const preventDefault = vi.fn()
    wrapper.vm.onPressEnter({ shiftKey: true, preventDefault })
    await flushPromises()

    expect(preventDefault).not.toHaveBeenCalled()
    expect(runAiTask).not.toHaveBeenCalled()
  })

  it('onPressEnter_whenAsking_shouldNotTriggerSecondAsk', async () => {
    runAiTask.mockImplementation(() => new Promise(() => {}))
    const { wrapper } = await mountView({})

    await wrapper.find('textarea').setValue('第一次')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()
    expect(runAiTask).toHaveBeenCalledTimes(1)

    const preventDefault = vi.fn()
    wrapper.vm.onPressEnter({ shiftKey: false, preventDefault })
    await flushPromises()

    // preventDefault 无条件执行（阻止换行）；仅 ask 由 asking 守卫拦截
    expect(preventDefault).toHaveBeenCalled()
    expect(runAiTask).toHaveBeenCalledTimes(1)
  })

  it('openHistory_whenItemMainClicked_shouldOpenDetailModalWithMarkdown', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({
        id: 5,
        question: '详情问题',
        answer: '# 大标题\n内容',
        contextFqn: 'com.demo.OrderService',
        createdAt: '2026-04-05T08:30:00'
      })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    const modal = wrapper.findComponent(Modal)
    expect(modal.props('open')).toBe(true)
    expect(modal.props('title')).toBe('详情问题')
    const detail = document.querySelector('.history-detail')
    expect(detail).not.toBeNull()
    expect(detail.querySelector('.markdown-body').innerHTML).toContain('<h1')
    expect(detail.textContent).toContain('内容')
    expect(detail.textContent).toContain('com.demo.OrderService')
  })

  it('openHistory_whenViewButtonClicked_shouldOpenDetailModal', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 6, question: '按钮查看' })]
    })

    await findButtonByText(wrapper, '查看').trigger('click')
    await flushPromises()

    expect(wrapper.findComponent(Modal).props('open')).toBe(true)
    expect(wrapper.findComponent(Modal).props('title')).toBe('按钮查看')
  })

  it('detailModal_whenClosedViaUpdateOpen_shouldCloseDetail', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 61, question: '关闭弹窗' })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()
    const modal = wrapper.findComponent(Modal)
    expect(modal.props('open')).toBe(true)

    // 模拟 antd 遮罩/ESC 关闭：触发 v-model:open 回写
    modal.vm.$emit('update:open', false)
    await flushPromises()

    expect(wrapper.findComponent(Modal).props('open')).toBe(false)
  })

  it('detailModal_whenImpactItem_shouldRenderSnapshotFields', async () => {
    const { wrapper } = await mountView({
      impact: [historyItem({
        id: 7,
        scope: 'IMPACT',
        question: '改方法影响什么',
        verification: JSON.stringify({ changeType: 'METHOD', targetMethod: 'pay()', totalImpacted: 4 })
      })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    const snapshot = document.querySelector('.impact-snapshot')
    expect(snapshot).not.toBeNull()
    expect(snapshot.textContent).toContain('METHOD')
    expect(snapshot.textContent).toContain('pay()')
    expect(snapshot.textContent).toContain('4')
    // IMPACT 的 verification 是结果快照，不应渲染问答校验标签
    expect(document.querySelector('.history-detail .verify-line')).toBeNull()
  })

  it('detailModal_whenImpactSnapshotInvalid_shouldHideSnapshot', async () => {
    const { wrapper } = await mountView({
      impact: [historyItem({ id: 8, scope: 'IMPACT', verification: 'not-json' })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    expect(document.querySelector('.history-detail')).not.toBeNull()
    expect(document.querySelector('.impact-snapshot')).toBeNull()
  })

  it('detailModal_whenImpactSnapshotFieldsMissing_shouldRenderPlaceholders', async () => {
    const { wrapper } = await mountView({
      impact: [historyItem({ id: 9, scope: 'IMPACT', verification: '{}' })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    const snapshot = document.querySelector('.impact-snapshot')
    expect(snapshot).not.toBeNull()
    expect(snapshot.textContent).toContain('—')
  })

  it('detailModal_whenVerificationIsJsonString_shouldRenderVerifyTag', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({
        id: 10,
        verification: JSON.stringify({ relationMismatches: ['存疑声明'], classRefsChecked: 2 })
      })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    expect(document.querySelector('.history-detail .verify-line .ant-tag').textContent)
      .toBe('证据校验：1 项异常')
  })

  it('detailModal_whenVerificationIsNotQaShape_shouldHideVerifyLine', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 11, verification: JSON.stringify({ foo: 1 }) })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    expect(document.querySelector('.history-detail .verify-line')).toBeNull()
  })

  it('detailModal_whenVerificationCountsMissing_shouldRenderFallbackCounts', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({
        id: 13,
        verification: JSON.stringify({ unsourced: [], relationMismatches: [] })
      })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    expect(document.querySelector('.history-detail .verify-line .ant-tag').textContent)
      .toBe('证据校验通过')
    // 计数缺失时回退 0（页面中 popconfirm 内嵌 Tooltip，改用 computed 断言 detail 文案）
    expect(wrapper.vm.detailVerify).toEqual({
      text: '证据校验通过',
      color: 'green',
      detail: '已校验 0 个类引用、0 条关系声明，均通过'
    })
  })

  it('detailModal_whenImpactVerificationNull_shouldHideSnapshot', async () => {
    const { wrapper } = await mountView({
      impact: [historyItem({ id: 14, scope: 'IMPACT', verification: null })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    expect(document.querySelector('.history-detail')).not.toBeNull()
    expect(document.querySelector('.impact-snapshot')).toBeNull()
  })

  it('detailModal_whenAnswerMissing_shouldRenderEmptyMarkdownAndNullDetailVerify', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 15, answer: null })]
    })

    // 未打开详情时 detailVerify 走 null 分支
    expect(wrapper.vm.detailVerify).toBeNull()

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()

    const markdown = document.querySelector('.history-detail .markdown-body')
    expect(markdown).not.toBeNull()
    expect(markdown.innerHTML).toBe('')
  })

  it('renderItem_whenOptionalFieldsMissing_shouldRenderGracefulDefaults', async () => {
    const { wrapper } = await mountView({
      global: [historyItem({ id: 12, scope: 'UNKNOWN', answer: null, createdAt: null, contextFqn: null })]
    })

    const item = wrapper.find('.history-item')
    expect(item.find('.history-preview').text()).toBe('')
    expect(item.find('.history-time').text()).toBe('')
    expect(item.text()).toContain('UNKNOWN') // scopeLabel 未知值原样返回
    expect(item.find('.ant-tag').classes()).toContain('ant-tag-default') // scopeColor 未知值
    // 无 contextFqn 时短名标签不渲染
    expect(wrapper.findAll('.history-item .ant-tag')).toHaveLength(1)
  })

  it('shortName_whenFqnFalsy_shouldReturnEmptyString', async () => {
    const { wrapper } = await mountView({})

    expect(wrapper.vm.shortName(null)).toBe('')
    expect(wrapper.vm.shortName('')).toBe('')
    expect(wrapper.vm.shortName('com.a.B')).toBe('B')
  })

  it('removeHistory_whenConfirmedOnOpenDetail_shouldDeleteCloseDetailAndReload', async () => {
    del.mockResolvedValue({ data: { data: {} } })
    const { wrapper } = await mountView({
      global: [historyItem({ id: 21, question: '将被删除' })]
    })

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(Modal).props('open')).toBe(true)

    // 删除按钮自身 @click.stop：点击不应冒泡打开详情
    const dangerBtn = wrapper.find('.history-item-side button.ant-btn-dangerous')
    await dangerBtn.trigger('click')
    await flushPromises()

    await wrapper.findComponent(Popconfirm).vm.$emit('confirm')
    await flushPromises()

    expect(del).toHaveBeenCalledWith('/projects/1/chat-history/21')
    expect(successSpy).toHaveBeenCalledWith('已删除')
    expect(wrapper.findComponent(Modal).props('open')).toBe(false)
    expect(document.querySelector('.history-detail')).toBeNull()
  })

  it('removeHistory_whenConfirmedOnOtherItem_shouldKeepDetailOpen', async () => {
    del.mockResolvedValue({ data: { data: {} } })
    const { wrapper } = await mountView({
      global: [
        historyItem({ id: 31, question: '保留的详情', createdAt: '2026-01-02T10:00:00' }),
        historyItem({ id: 32, question: '删除的目标', createdAt: '2026-01-01T10:00:00' })
      ]
    })

    await wrapper.findAll('.history-item-main')[0].trigger('click') // 打开 id=31 详情
    await flushPromises()

    await wrapper.findAllComponents(Popconfirm)[1].vm.$emit('confirm') // 删除 id=32
    await flushPromises()

    expect(del).toHaveBeenCalledWith('/projects/1/chat-history/32')
    expect(wrapper.findComponent(Modal).props('open')).toBe(true)
    expect(wrapper.findComponent(Modal).props('title')).toBe('保留的详情')
  })

  it('removeHistory_whenDeleteFails_shouldSwallowError', async () => {
    del.mockRejectedValue(new Error('500'))
    const { wrapper } = await mountView({
      global: [historyItem({ id: 41 })]
    })

    await wrapper.findAllComponents(Popconfirm)[0].vm.$emit('confirm')
    await flushPromises()

    expect(successSpy).not.toHaveBeenCalled()
    expect(wrapper.findAll('.history-item')).toHaveLength(1) // 列表未变（loadHistory 未成功刷新前）
  })

  it('watchProjectId_whenRouteChanges_shouldResetAnswerDetailAndReload', async () => {
    runAiTask.mockImplementation(async ({ execute }) => execute({ taskId: 't', signal: 's' }))
    post.mockResolvedValue({ data: { data: { answer: '旧项目答案' } } })
    const { wrapper, router } = await mountView({ global: [historyItem({ id: 51, question: '旧项目历史' })] })

    await wrapper.find('textarea').setValue('问题')
    await findButtonByText(wrapper, '提问').trigger('click')
    await flushPromises()
    expect(wrapper.find('.answer-box').exists()).toBe(true)

    await wrapper.find('.history-item-main').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(Modal).props('open')).toBe(true)

    mockHistory({ global: [historyItem({ id: 52, question: '新项目历史' })] })
    await router.push('/projects/2')
    await flushPromises()

    expect(wrapper.find('.answer-box').exists()).toBe(false)
    expect(wrapper.findComponent(Modal).props('open')).toBe(false)
    expect(get).toHaveBeenCalledWith('/projects/2/chat-history', { params: { scope: 'GLOBAL' } })
    expect(wrapper.find('.history-item').text()).toContain('新项目历史')
  })

  it('watchProjectId_whenLeavingProjectRoute_shouldNotFetchWithUndefinedId', async () => {
    const { router } = await mountView({ global: [historyItem({ id: 61 })] })
    get.mockClear()

    await router.push('/outside')
    await flushPromises()

    expect(get).not.toHaveBeenCalled()
  })
})
