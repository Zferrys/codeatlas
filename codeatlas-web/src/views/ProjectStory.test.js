import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import Antd from 'ant-design-vue'

vi.mock('@/api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  handleUnauthorized: vi.fn()
}))

import ProjectStory from './ProjectStory.vue'

const ReportPanelStub = {
  name: 'ReportPanel',
  props: ['projectId', 'type', 'kind', 'title', 'description', 'autoPoll'],
  template: '<div class="report-panel-stub" />'
}

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

async function mountView(path = '/projects/9') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/projects/:id', component: { template: '<div/>' } }]
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(ProjectStory, {
    global: {
      plugins: [router, Antd],
      stubs: { ReportPanel: ReportPanelStub }
    }
  })
  await flushPromises()
  return wrapper
}

async function clickTab(wrapper, index) {
  const tabs = wrapper.findAll('.ant-tabs-tab')
  expect(tabs.length).toBe(4)
  await tabs[index].trigger('click')
  await flushPromises()
}

describe('ProjectStory', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('mount_whenDefault_shouldRenderArchStoryPanelWithAutoPoll', async () => {
    const wrapper = await mountView()
    const panel = wrapper.findComponent(ReportPanelStub)

    expect(panel.exists()).toBe(true)
    expect(panel.props()).toMatchObject({
      projectId: '9',
      type: 'ARCH_STORY',
      kind: 'story',
      title: '架构解读',
      autoPoll: true
    })
    expect(panel.props().description).toContain('架构故事')
    expect(wrapper.findAll('.ant-tabs-tab').map(t => t.text())).toEqual([
      '架构解读', '设计模式', '反模式', '规约检查'
    ])
  })

  it('switchTab_whenPatternClicked_shouldPassPatternMetaAndDisablePoll', async () => {
    const wrapper = await mountView()

    await clickTab(wrapper, 1)

    const panel = wrapper.findComponent(ReportPanelStub)
    expect(panel.props()).toMatchObject({
      type: 'PATTERN',
      kind: 'pattern',
      title: '设计模式',
      autoPoll: false
    })
    expect(panel.props().description).toContain('识别项目采用的架构与设计模式')
  })

  it('switchTab_whenAntiPatternClicked_shouldPassAntiPatternMeta', async () => {
    const wrapper = await mountView()

    await clickTab(wrapper, 2)

    expect(wrapper.findComponent(ReportPanelStub).props()).toMatchObject({
      type: 'ANTI_PATTERN',
      kind: 'anti-pattern',
      title: '反模式',
      autoPoll: false
    })
  })

  it('switchTab_whenConstitutionCheckClicked_shouldPassConstitutionMeta', async () => {
    const wrapper = await mountView()

    await clickTab(wrapper, 3)

    const panel = wrapper.findComponent(ReportPanelStub)
    expect(panel.props()).toMatchObject({
      type: 'CONSTITUTION_CHECK',
      kind: 'constitution-check',
      title: '规约检查',
      autoPoll: false
    })
    expect(panel.props().description).toContain('宪法规则')
  })

  it('switchTab_whenBackToArchStory_shouldReEnableAutoPoll', async () => {
    const wrapper = await mountView()

    await clickTab(wrapper, 1)
    expect(wrapper.findComponent(ReportPanelStub).props('autoPoll')).toBe(false)

    await clickTab(wrapper, 0)
    expect(wrapper.findComponent(ReportPanelStub).props()).toMatchObject({
      type: 'ARCH_STORY',
      autoPoll: true
    })
  })
})
