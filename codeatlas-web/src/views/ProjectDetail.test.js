import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, inject } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  },
  handleUnauthorized: vi.fn()
}))

import api from '../api'
import ProjectDetail from './ProjectDetail.vue'

const ChildProbe = defineComponent({
  setup() {
    const projectId = inject('projectId')
    const projectName = inject('projectName')
    return { projectId, projectName }
  },
  template: '<div class="child-probe">{{ projectId }}|{{ projectName }}</div>'
})

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/project/:id',
        component: ProjectDetail,
        children: [
          { path: 'overview', component: ChildProbe }
        ]
      }
    ]
  })
}

let wrapper = null

async function mountProjectDetail(path = '/project/42/overview') {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  wrapper = mount(ProjectDetail, {
    global: { plugins: [router] }
  })
  return { wrapper, router }
}

describe('ProjectDetail.vue', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset()
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
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.restoreAllMocks()
  })

  it('onMounted_whenProjectLoaded_shouldProvideProjectIdAndNameToChildRoute', async () => {
    api.get.mockResolvedValue({ data: { data: { name: 'Order Service' } } })
    const { wrapper } = await mountProjectDetail()
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/projects/42')
    expect(wrapper.vm.projectId).toBe('42')
    expect(wrapper.vm.projectName).toBe('Order Service')
    expect(wrapper.find('.child-probe').text()).toBe('42|Order Service')
  })

  it('onMounted_whenResponseHasNoProjectData_shouldProvideEmptyProjectName', async () => {
    api.get.mockResolvedValue({ data: { data: null } })
    const { wrapper } = await mountProjectDetail('/project/7/overview')
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/projects/7')
    expect(wrapper.vm.projectName).toBe('')
    expect(wrapper.find('.child-probe').text()).toBe('7|')
  })

  it('onMounted_whenRequestFails_shouldRenderWithoutName', async () => {
    api.get.mockRejectedValue(new Error('not found'))
    const { wrapper } = await mountProjectDetail('/project/99/overview')
    await flushPromises()

    expect(wrapper.vm.projectName).toBe('')
    expect(wrapper.find('.child-probe').text()).toBe('99|')
  })
})
