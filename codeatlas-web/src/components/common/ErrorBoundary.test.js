import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import ErrorBoundary from './ErrorBoundary.vue'

const AButtonStub = {
  name: 'AButton',
  emits: ['click'],
  template: '<button class="a-button-stub" @click="$emit(\'click\')"><slot /></button>'
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dashboard', name: 'Dashboard', component: { render: () => null } }
    ]
  })
}

/** 首次 setup 抛错的子组件：模拟渲染异常，恢复后（重建实例）能正常渲染 */
function createFaultyChild(message) {
  let shouldThrow = true
  return {
    name: 'FaultyChild',
    setup() {
      if (shouldThrow) {
        shouldThrow = false
        throw new Error(message)
      }
      return () => h('div', { class: 'child-ok' }, '已恢复')
    }
  }
}

async function mountBoundary(FaultyChild) {
  const router = createTestRouter()
  await router.push('/dashboard')
  const wrapper = mount(ErrorBoundary, {
    global: {
      plugins: [router],
      stubs: { 'a-button': AButtonStub }
    },
    slots: { default: FaultyChild }
  })
  return { wrapper, router }
}

describe('ErrorBoundary.vue', () => {
  let consoleErrorSpy

  beforeEach(() => {
    consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    consoleErrorSpy.mockRestore()
  })

  it('ErrorBoundary_whenChildThrows_shouldRenderFallbackAndLogCapturedError', async () => {
    const { wrapper } = await mountBoundary(createFaultyChild('渲染炸了'))

    expect(wrapper.find('.error-boundary').exists()).toBe(true)
    expect(wrapper.find('.error-title').text()).toBe('页面出现了异常')
    expect(wrapper.find('.error-desc').text()).toContain('很抱歉')
    expect(wrapper.find('.child-ok').exists()).toBe(false)

    expect(consoleErrorSpy).toHaveBeenCalled()
    const [tag, error, info] = consoleErrorSpy.mock.calls[0]
    expect(tag).toBe('[ErrorBoundary]')
    expect(error.message).toBe('渲染炸了')
    expect(typeof info).toBe('string')

    const details = wrapper.find('details.error-details')
    expect(details.exists()).toBe(true)
    expect(details.find('pre').text()).toBe('渲染炸了')
    expect(details.find('pre.error-stack').text()).toBe(error.stack)
  })

  it('ErrorBoundary_whenRefreshClicked_shouldClearErrorAndRenderSlotAgain', async () => {
    const faulty = createFaultyChild('第一次渲染失败')
    const { wrapper } = await mountBoundary(faulty)
    expect(wrapper.find('.error-boundary').exists()).toBe(true)

    await wrapper.findAll('.a-button-stub')[0].trigger('click')

    expect(wrapper.find('.error-boundary').exists()).toBe(false)
    expect(wrapper.find('.child-ok').text()).toBe('已恢复')
    // console.error 只记录第一次的异常
    expect(consoleErrorSpy).toHaveBeenCalledTimes(1)
  })

  it('ErrorBoundary_whenGoHomeClicked_shouldClearErrorAndNavigateToDashboard', async () => {
    const faulty = createFaultyChild('第二次渲染失败')
    const { wrapper, router } = await mountBoundary(faulty)
    expect(wrapper.find('.error-boundary').exists()).toBe(true)

    const homeButton = wrapper.findAll('.a-button-stub')[1]
    expect(homeButton.text()).toBe('返回首页')
    await homeButton.trigger('click')

    expect(wrapper.find('.error-boundary').exists()).toBe(false)
    expect(wrapper.find('.child-ok').exists()).toBe(true)
    expect(router.currentRoute.value.name).toBe('Dashboard')
  })

  it('ErrorBoundary_whenChildHealthy_shouldRenderSlotOnly', async () => {
    const healthyChild = { name: 'HealthyChild', render: () => h('div', { class: 'child-ok' }, '一切正常') }

    const { wrapper } = await mountBoundary(healthyChild)

    expect(wrapper.find('.error-boundary').exists()).toBe(false)
    expect(wrapper.find('.child-ok').text()).toBe('一切正常')
    expect(consoleErrorSpy).not.toHaveBeenCalled()
  })
})
