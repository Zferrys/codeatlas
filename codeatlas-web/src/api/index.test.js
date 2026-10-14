import { describe, it, expect, vi, beforeEach } from 'vitest'

const {
  createMock,
  instance,
  requestUse,
  responseUse,
  messageError,
  reportMock,
  useAuthStoreMock,
  clearAuthMock
} = vi.hoisted(() => {
  const requestUse = vi.fn()
  const responseUse = vi.fn()
  const instance = {
    interceptors: {
      request: { use: requestUse },
      response: { use: responseUse }
    }
  }
  return {
    createMock: vi.fn(() => instance),
    instance,
    requestUse,
    responseUse,
    messageError: vi.fn(),
    reportMock: vi.fn(),
    useAuthStoreMock: vi.fn(),
    clearAuthMock: vi.fn()
  }
})

vi.mock('axios', () => ({ default: { create: createMock } }))
vi.mock('ant-design-vue', () => ({ message: { error: messageError } }))
vi.mock('../utils/errorReporter', () => ({ report: reportMock }))
vi.mock('../stores/auth', () => ({ useAuthStore: useAuthStoreMock }))

import api, { handleUnauthorized } from './index'

// 模块加载时拦截器已注册，捕获回调用于手动调用断言
// vitest 5 默认 clearMocks=true：测试前会清空调用记录，故在模块加载时快照创建参数
const createArgs = createMock.mock.calls[0][0]
const onRequest = requestUse.mock.calls[0][0]
const onRequestError = requestUse.mock.calls[0][1]
const onResponse = responseUse.mock.calls[0][0]
const onResponseError = responseUse.mock.calls[0][1]

// 只清理行为类 mock：createMock/requestUse/responseUse 保留模块导入时的调用记录
function resetBehaviorMocks() {
  messageError.mockClear()
  reportMock.mockClear()
  clearAuthMock.mockClear()
  useAuthStoreMock.mockClear()
  useAuthStoreMock.mockReturnValue({ clearAuth: clearAuthMock })
  localStorage.clear()
  window.history.replaceState(null, '', '/')
}

describe('api instance', () => {
  beforeEach(resetBehaviorMocks)

  it('api_whenCreated_shouldUseV1BaseURLAndTwoMinuteTimeout', () => {
    expect(createArgs).toEqual({ baseURL: '/api/v1', timeout: 120000 })
    expect(api).toBe(instance)
  })
})

describe('request interceptor', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('request_whenTokenInStorage_shouldAttachBearerAuthorization', () => {
    localStorage.setItem('codeatlas_token', 'tok-abc')

    const config = onRequest({ headers: {} })

    expect(config.headers.Authorization).toBe('Bearer tok-abc')
  })

  it('request_whenNoToken_shouldNotAttachAuthorization', () => {
    const config = onRequest({ headers: {} })

    expect(config.headers.Authorization).toBeUndefined()
    expect(config.headers['Content-Type']).toBeUndefined()
  })

  it('request_whenPlainData_shouldForceJsonContentType', () => {
    const config = onRequest({ headers: {}, data: { username: 'a' } })

    expect(config.headers['Content-Type']).toBe('application/json')
  })

  it('request_whenFormData_shouldNotOverrideContentType', () => {
    const config = onRequest({ headers: {}, data: new FormData() })

    expect(config.headers['Content-Type']).toBeUndefined()
  })

  it('request_whenNoData_shouldNotSetContentType', () => {
    const config = onRequest({ headers: {}, data: undefined })

    expect(config.headers['Content-Type']).toBeUndefined()
  })

  it('request_whenRejected_shouldPassThroughTheError', async () => {
    const error = new Error('interceptor down')

    await expect(onRequestError(error)).rejects.toBe(error)
  })
})

describe('response interceptor', () => {
  beforeEach(resetBehaviorMocks)

  it('response_whenSuccess_shouldPassThroughUntouched', () => {
    const response = { data: { ok: true }, status: 200 }

    expect(onResponse(response)).toBe(response)
    expect(messageError).not.toHaveBeenCalled()
  })

  it('response_whenRequestCancelledByCode_shouldRejectSilently', async () => {
    const error = { code: 'ERR_CANCELED', message: 'canceled' }

    await expect(onResponseError(error)).rejects.toBe(error)
    expect(messageError).not.toHaveBeenCalled()
    expect(reportMock).not.toHaveBeenCalled()
  })

  it('response_whenRequestCancelledByName_shouldRejectSilently', async () => {
    const error = { name: 'CanceledError', message: 'canceled' }

    await expect(onResponseError(error)).rejects.toBe(error)
    expect(messageError).not.toHaveBeenCalled()
    expect(reportMock).not.toHaveBeenCalled()
  })

  it('response_when401_shouldClearAuthRedirectToLoginAndReport', async () => {
    const error = {
      config: { url: '/projects' },
      response: { status: 401, data: {}, headers: { 'x-trace-id': 'trace-401' } }
    }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(clearAuthMock).toHaveBeenCalledTimes(1)
    expect(window.location.hash).toBe('#/login')
    expect(messageError).toHaveBeenCalledTimes(1)
    expect(messageError).toHaveBeenCalledWith('登录已过期，请重新登录')
    expect(reportMock).toHaveBeenCalledTimes(1)
    expect(reportMock).toHaveBeenCalledWith({
      type: 'api_error',
      url: '/projects',
      status: 401,
      traceId: 'trace-401',
      timestamp: expect.any(Number)
    })
  })

  it('response_when403_shouldToastNoPermission', async () => {
    const error = { config: { url: '/admin/users' }, response: { status: 403, data: {}, headers: {} } }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('无权限访问')
    expect(reportMock).toHaveBeenCalledTimes(1)
  })

  it('response_when404_shouldToastResourceMissing', async () => {
    const error = { config: { url: '/projects/999' }, response: { status: 404, data: {}, headers: {} } }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('资源不存在')
  })

  it('response_when500WithBackendMessage_shouldPreferBackendMessage', async () => {
    const error = {
      config: { url: '/ai/analyze' },
      response: { status: 500, data: { message: '模型服务不可用' }, headers: {} }
    }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('模型服务不可用')
  })

  it('response_when500WithoutBackendMessage_shouldFallbackToGenericText', async () => {
    const error = { config: { url: '/ai/analyze' }, response: { status: 500, data: {}, headers: {} } }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('服务器内部错误')
  })

  it('response_whenOtherStatusWithBackendMessage_shouldPreferBackendMessage', async () => {
    const error = {
      config: { url: '/teapot' },
      response: { status: 418, data: { message: '我是茶壶' }, headers: {} }
    }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('我是茶壶')
  })

  it('response_whenOtherStatusWithoutBackendMessage_shouldIncludeStatusInText', async () => {
    const error = { config: { url: '/teapot' }, response: { status: 418, data: {}, headers: {} } }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('请求失败 (418)')
  })

  it('response_whenNetworkErrorWithoutResponse_shouldToastAndSkipReport', async () => {
    const error = { config: { url: '/projects' }, message: 'Network Error' }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(messageError).toHaveBeenCalledWith('网络异常，请检查连接')
    expect(reportMock).not.toHaveBeenCalled()
    expect(clearAuthMock).not.toHaveBeenCalled()
  })

  it('response_whenErrorHasNoConfig_shouldStillReportWithUndefinedUrl', async () => {
    const error = { response: { status: 502, data: {}, headers: {} } }

    await expect(onResponseError(error)).rejects.toBe(error)

    expect(reportMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'api_error', url: undefined, status: 502 })
    )
  })
})

describe('handleUnauthorized', () => {
  beforeEach(resetBehaviorMocks)

  it('handleUnauthorized_whenStoreAvailable_shouldClearViaStoreAndRedirect', () => {
    handleUnauthorized()

    expect(clearAuthMock).toHaveBeenCalledTimes(1)
    expect(window.location.hash).toBe('#/login')
    expect(messageError).toHaveBeenCalledWith('登录已过期，请重新登录')
  })

  it('handleUnauthorized_whenStoreUnavailable_shouldFallbackToLocalStorageCleanup', () => {
    useAuthStoreMock.mockImplementationOnce(() => {
      throw new Error('pinia not installed')
    })
    localStorage.setItem('codeatlas_token', 'stale-token')
    localStorage.setItem('codeatlas_user', '{"id":1}')

    handleUnauthorized()

    expect(clearAuthMock).not.toHaveBeenCalled()
    expect(localStorage.getItem('codeatlas_token')).toBeNull()
    expect(localStorage.getItem('codeatlas_user')).toBeNull()
    expect(window.location.hash).toBe('#/login')
    expect(messageError).toHaveBeenCalledWith('登录已过期，请重新登录')
  })
})
