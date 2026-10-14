import { describe, it, expect, vi, beforeEach } from 'vitest'

const { post } = vi.hoisted(() => ({
  post: vi.fn(() => Promise.resolve({}))
}))

vi.mock('axios', () => ({ default: { post } }))

import { report } from './errorReporter'

describe('errorReporter', () => {
  beforeEach(() => {
    post.mockClear()
    post.mockImplementation(() => Promise.resolve({}))
  })

  it('report_whenCalled_shouldPostPayloadToTelemetryEndpoint', () => {
    report({ type: 'api_error', status: 500 })

    expect(post).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledWith('/api/v1/telemetry/frontend-error', {
      type: 'api_error',
      status: 500
    })
  })

  it('report_whenPostFails_shouldSwallowErrorWithoutThrowing', async () => {
    post.mockImplementationOnce(() => Promise.reject(new Error('network down')))

    expect(() => report({ type: 'x' })).not.toThrow()
    await Promise.resolve()
  })

  it('js_runtime_error_shouldBeReportedWithMessageStackAndUrl', () => {
    const error = new Error('boom')
    window.dispatchEvent(new ErrorEvent('error', { message: 'boom', error }))

    expect(post).toHaveBeenCalledTimes(1)
    const payload = post.mock.calls[0][1]
    expect(payload.type).toBe('js_error')
    expect(payload.message).toBe('boom')
    expect(payload.stack).toContain('boom')
    expect(payload.url).toBe(window.location.href)
    expect(typeof payload.timestamp).toBe('number')
  })

  it('js_runtime_error_whenStackTooLong_shouldTruncateTo1000Chars', () => {
    const error = new Error('long')
    error.stack = 'x'.repeat(1500)
    window.dispatchEvent(new ErrorEvent('error', { message: 'long', error }))

    const payload = post.mock.calls[0][1]
    expect(payload.stack).toHaveLength(1000)
  })

  it('unhandledrejection_whenReasonIsError_shouldReportMessageAndStack', () => {
    const evt = new Event('unhandledrejection')
    evt.reason = new Error('promise boom')
    window.dispatchEvent(evt)

    expect(post).toHaveBeenCalledTimes(1)
    const payload = post.mock.calls[0][1]
    expect(payload.type).toBe('promise_rejection')
    expect(payload.message).toBe('promise boom')
    expect(payload.stack).toContain('promise boom')
  })

  it('unhandledrejection_whenReasonIsNotError_shouldStringifyReason', () => {
    const evt = new Event('unhandledrejection')
    evt.reason = 'just a string'
    window.dispatchEvent(evt)

    const payload = post.mock.calls[0][1]
    expect(payload.type).toBe('promise_rejection')
    expect(payload.message).toBe('just a string')
    expect(payload.stack).toBeUndefined()
  })
})
