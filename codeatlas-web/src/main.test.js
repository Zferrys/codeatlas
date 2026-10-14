import { describe, it, expect, vi, beforeEach } from 'vitest'

const { appStub, createAppMock } = vi.hoisted(() => {
  const appStub = { use: vi.fn(), mount: vi.fn() }
  appStub.use.mockReturnValue(appStub)
  return { appStub, createAppMock: vi.fn(() => appStub) }
})

vi.mock('vue', async importOriginal => {
  const actual = await importOriginal()
  return { ...actual, createApp: createAppMock }
})

describe('main.js 应用启动', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    localStorage.clear()
  })

  // 首个用例承担 App.vue / router / ant-design-vue 全量模块图的首次转换耗时，单独放宽超时
  it('main_whenBootstrapped_shouldInstallPiniaRouterAntdAndI18nThenMount', { timeout: 30000 }, async () => {
    const { default: AppComponent } = await import('./App.vue')

    await import('./main.js')

    const { default: router } = await import('./router')
    const { default: Antd } = await import('ant-design-vue')

    expect(createAppMock).toHaveBeenCalledTimes(1)
    expect(createAppMock.mock.calls[0][0]).toBe(AppComponent)

    expect(appStub.use).toHaveBeenCalledTimes(4)
    const [piniaInstance, routerArg, antdArg, i18nInstance] = appStub.use.mock.calls.map(([plugin]) => plugin)
    expect(typeof piniaInstance.install).toBe('function')
    expect(routerArg).toBe(router)
    expect(antdArg).toBe(Antd)
    expect(typeof i18nInstance.install).toBe('function')

    expect(appStub.mount).toHaveBeenCalledTimes(1)
    expect(appStub.mount).toHaveBeenCalledWith('#app')
  })

  it('main_whenNoSavedLocale_shouldDefaultToChinese', async () => {
    await import('./main.js')

    const i18nInstance = appStub.use.mock.calls[3][0]
    expect(i18nInstance.global.locale.value).toBe('zh-CN')
    expect(i18nInstance.global.fallbackLocale.value).toBe('zh-CN')
    expect(i18nInstance.global.t('nav.workspace')).toBe('工作台')
    expect(i18nInstance.global.t('nav.workspace', {}, { locale: 'en-US' })).toBe('Workspace')
  })

  it('main_whenLocaleSavedInStorage_shouldBootWithSavedLocale', async () => {
    localStorage.setItem('codeatlas_locale', 'en-US')

    await import('./main.js')

    const i18nInstance = appStub.use.mock.calls[3][0]
    expect(i18nInstance.global.locale.value).toBe('en-US')
    expect(i18nInstance.global.t('nav.workspace')).toBe('Workspace')
  })
})
