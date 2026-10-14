import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const { infoMock, warnMock, handleUnauthorizedMock } = vi.hoisted(() => ({
  infoMock: vi.fn(),
  warnMock: vi.fn(),
  handleUnauthorizedMock: vi.fn()
}))

vi.mock('ant-design-vue', () => ({ message: { info: infoMock, warning: warnMock } }))
vi.mock('../api/index', () => ({ handleUnauthorized: handleUnauthorizedMock }))

import {
  runAiTask,
  cancelActiveAiTask,
  hasActiveAiTask,
  activeTask,
  AI_TASK_CANCELLED
} from './useAiTask'

const flush = () => new Promise(resolve => setTimeout(resolve, 0))

/** 一次性灌入全部分段的 SSE 响应 */
function sseResponse(chunks, overrides = {}) {
  return {
    status: 200,
    ok: true,
    body: new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder()
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
        controller.close()
      }
    }),
    ...overrides
  }
}

/** 可控分段推送的 SSE 流：模拟真实网络下 data 跨 chunk 到达 */
function controlledSseStream() {
  const encoder = new TextEncoder()
  let controller
  return {
    stream: new ReadableStream({ start(c) { controller = c } }),
    push(chunk) { controller.enqueue(encoder.encode(chunk)) },
    close() { controller.close() }
  }
}

describe('useAiTask', () => {
  let fetchMock
  let resolveExecute
  const pendingExecute = () => vi.fn(() => new Promise(resolve => { resolveExecute = resolve }))

  beforeEach(() => {
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem('codeatlas_token', 'tok-ai')
    activeTask.value = null
  })

  afterEach(() => {
    activeTask.value = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('AI_TASK_CANCELLED_whenInspected_shouldBeASymbolSentinel', () => {
    expect(typeof AI_TASK_CANCELLED).toBe('symbol')
    expect(hasActiveAiTask()).toBe(false)
    expect(activeTask.value).toBeNull()
  })

  it('runAiTask_whenExecuteResolves_shouldReturnResultAndClearState', async () => {
    const execute = pendingExecute()
    fetchMock.mockResolvedValue(sseResponse([]))

    const runPromise = runAiTask({ projectId: 42, label: '智能问答', execute })

    expect(hasActiveAiTask()).toBe(true)
    expect(activeTask.value.label).toBe('智能问答')
    expect(activeTask.value.stage).toBe('正在连接 AI 服务…')
    expect(activeTask.value.elapsedSec).toBe(0)

    const { taskId, signal } = execute.mock.calls[0][0]
    expect(taskId).toMatch(/^aitask-/)
    expect(typeof signal.aborted).toBe('boolean')

    resolveExecute('AI-ANSWER')
    await expect(runPromise).resolves.toBe('AI-ANSWER')

    expect(activeTask.value).toBeNull()
    expect(hasActiveAiTask()).toBe(false)
    expect(warnMock).not.toHaveBeenCalled()
  })

  it('subscribeProgress_whenConnected_shouldSendTokenAndProgressUrl', async () => {
    const execute = pendingExecute()
    fetchMock.mockResolvedValue(sseResponse([]))

    const runPromise = runAiTask({ projectId: 42, label: 'L', execute })
    const { taskId } = execute.mock.calls[0][0]
    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe(`/api/v1/projects/42/ai/ai-progress?taskId=${encodeURIComponent(taskId)}`)
    expect(options.headers.Authorization).toBe('Bearer tok-ai')
    expect(options.signal).toBeTruthy()

    resolveExecute('R')
    await runPromise
  })

  it('subscribeProgress_whenTaskIdEventThenMessageEvent_shouldUpdateStageInOrder', async () => {
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 7, label: 'L', execute })
    await flush()

    sse.push('data: {"taskId":"aitask-remote","progress":10}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('AI 服务已连接，处理中…')

    // 事件既无 message 也无 taskId：进度保持原值，不做任何更新
    sse.push('data: {}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('AI 服务已连接，处理中…')

    sse.push('data: {"stage": "SCANNING", "message": "正在解析依赖"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('正在解析依赖')

    resolveExecute('R')
    await runPromise
  })

  it('subscribeProgress_whenDataSplitAcrossChunks_shouldJoinBeforeParse', async () => {
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push('data: {"taskId":"aitask-x"}\n\ndata: {"mess')
    await flush()
    expect(activeTask.value.stage).toBe('AI 服务已连接，处理中…')

    sse.push('age":"跨包拼接的进度"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('跨包拼接的进度')

    resolveExecute('R')
    await runPromise
  })

  it('subscribeProgress_whenNonDataAndInvalidJsonLines_shouldSkipAndKeepGoing', async () => {
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push(': keep-alive\n\nevent: ping\n\ndata: not-json\n\ndata: {"message":"坏行之后仍生效"}\n\n')
    await flush()

    expect(activeTask.value.stage).toBe('坏行之后仍生效')

    resolveExecute('R')
    await runPromise
  })

  it('subscribeProgress_whenDoneEvent_shouldCancelReaderAndStopUpdating', async () => {
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push('data: {"taskId":"t"}\n\ndata: {"stage":"DONE"}\n\ndata: {"message":"不应生效"}\n\n')
    await flush()

    expect(activeTask.value.stage).toBe('AI 服务已连接，处理中…')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenTaskIdMismatch_shouldIgnoreLateProgressEvents', async () => {
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    const realTask = activeTask.value
    activeTask.value = { ...realTask, id: 'aitask-another-task' }

    sse.push('data: {"message":"别的任务的进度"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('正在连接 AI 服务…')
    activeTask.value = realTask

    resolveExecute('R')
    await runPromise
  })

  it('subscribeProgress_when401_shouldHandleUnauthorizedAndReturnEarly', async () => {
    fetchMock.mockResolvedValue({ status: 401, ok: false, body: null })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    expect(handleUnauthorizedMock).toHaveBeenCalledTimes(1)
    // 订阅提前返回，任务主体不受影响
    expect(activeTask.value.stage).toBe('正在连接 AI 服务…')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenResponseNotOk_shouldReturnSilently', async () => {
    fetchMock.mockResolvedValue(sseResponse([], { status: 503, ok: false }))
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    expect(handleUnauthorizedMock).not.toHaveBeenCalled()

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenBodyMissing_shouldReturnSilently', async () => {
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: null })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    expect(handleUnauthorizedMock).not.toHaveBeenCalled()
    expect(activeTask.value.stage).toBe('正在连接 AI 服务…')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenFetchRejectsWithAbortError_shouldSwallowError', async () => {
    fetchMock.mockRejectedValue(new DOMException('aborted', 'AbortError'))
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('cancelActiveAiTask_whenTaskRunning_shouldAbortBothTransportsAndResolveSentinel', async () => {
    let sseSignal
    fetchMock.mockImplementation((url, options) => {
      sseSignal = options.signal
      return new Promise(() => {})
    })
    let postSignal
    const execute = vi.fn(({ signal }) => {
      postSignal = signal
      return new Promise((resolve, reject) => {
        signal.addEventListener('abort', () => reject(Object.assign(new Error('canceled'), { code: 'ERR_CANCELED' })))
      })
    })

    const runPromise = runAiTask({ projectId: 5, label: 'L', execute })
    await flush()

    cancelActiveAiTask()

    expect(activeTask.value.stage).toBe('正在终止…')
    expect(sseSignal.aborted).toBe(true)
    expect(postSignal.aborted).toBe(true)

    await expect(runPromise).resolves.toBe(AI_TASK_CANCELLED)
    expect(infoMock).toHaveBeenCalledWith('已终止本次任务')
    expect(activeTask.value).toBeNull()
  })

  it('runAiTask_whenCancelledAndExecuteFailsWithPlainError_shouldStillReturnSentinel', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}))
    const execute = vi.fn(({ signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(new Error('连接被中断')))
    }))

    const runPromise = runAiTask({ projectId: 5, label: 'L', execute })
    await flush()

    cancelActiveAiTask()
    await expect(runPromise).resolves.toBe(AI_TASK_CANCELLED)
    expect(infoMock).toHaveBeenCalledWith('已终止本次任务')
  })

  it('runAiTask_whenExecuteRejectsCanceledError_shouldReturnSentinel', async () => {
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = vi.fn(() => Promise.reject(Object.assign(new Error('canceled'), { name: 'CanceledError' })))

    const result = await runAiTask({ projectId: 1, label: 'L', execute })

    expect(result).toBe(AI_TASK_CANCELLED)
    expect(infoMock).toHaveBeenCalledWith('已终止本次任务')
    expect(activeTask.value).toBeNull()
  })

  it('runAiTask_whenExecuteFails_shouldRethrowAndStillCleanup', async () => {
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = vi.fn(() => Promise.reject(new Error('AI 服务 500')))

    await expect(runAiTask({ projectId: 1, label: 'L', execute })).rejects.toThrow('AI 服务 500')

    expect(infoMock).not.toHaveBeenCalled()
    expect(activeTask.value).toBeNull()
    expect(hasActiveAiTask()).toBe(false)
  })

  it('runAiTask_whenTaskAlreadyRunning_shouldWarnAndReturnSentinelWithoutStartingSecond', async () => {
    fetchMock.mockResolvedValue(sseResponse([]))
    const first = runAiTask({ projectId: 1, label: '任务一', execute: pendingExecute().mockName('firstExecute') })
    const firstId = activeTask.value.id

    const secondExecute = vi.fn()
    const second = await runAiTask({ projectId: 2, label: '任务二', execute: secondExecute })

    expect(second).toBe(AI_TASK_CANCELLED)
    expect(warnMock).toHaveBeenCalledWith('已有 AI 任务正在进行中，请等待完成或先终止它')
    expect(secondExecute).not.toHaveBeenCalled()
    expect(activeTask.value.id).toBe(firstId)

    resolveExecute('DONE-1')
    await expect(first).resolves.toBe('DONE-1')
  })

  it('cancelActiveAiTask_whenNoTaskRunning_shouldBeANoOp', () => {
    expect(() => cancelActiveAiTask()).not.toThrow()
    expect(hasActiveAiTask()).toBe(false)
    expect(infoMock).not.toHaveBeenCalled()
  })

  it('runAiTask_whenSecondsPass_shouldTickElapsedSecEachSecond', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await vi.advanceTimersByTimeAsync(0)
    expect(activeTask.value.elapsedSec).toBe(0)

    await vi.advanceTimersByTimeAsync(1000)
    expect(activeTask.value.elapsedSec).toBe(1)

    await vi.advanceTimersByTimeAsync(2500)
    expect(activeTask.value.elapsedSec).toBe(3)

    // 定时回调发现任务 id 不匹配（展示态已被替换）时跳过更新
    const realTask = activeTask.value
    activeTask.value = { id: 'aitask-other', startedAt: Date.now(), elapsedSec: 0 }
    await vi.advanceTimersByTimeAsync(1000)
    expect(activeTask.value.elapsedSec).toBe(0)

    activeTask.value = realTask
    resolveExecute('R')
    await runPromise
  })

  it('runAiTask_whenRunning_shouldBlockBeforeUnloadAndRemoveListenerOnFinish', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })

    const addCall = addSpy.mock.calls.find(([event]) => event === 'beforeunload')
    expect(addCall).toBeTruthy()
    const handler = addCall[1]
    const beforeUnloadEvent = { preventDefault: vi.fn(), returnValue: undefined }
    handler(beforeUnloadEvent)
    expect(beforeUnloadEvent.preventDefault).toHaveBeenCalledTimes(1)
    expect(beforeUnloadEvent.returnValue).toBe('')

    resolveExecute('R')
    await runPromise

    expect(removeSpy.mock.calls.some(([event, listener]) => event === 'beforeunload' && listener === handler)).toBe(true)

    addSpy.mockRestore()
    removeSpy.mockRestore()
  })

  it('runAiTask_whenCryptoRandomUUIDUnavailable_shouldFallbackToTimestampTaskId', async () => {
    vi.stubGlobal('crypto', undefined)
    fetchMock.mockResolvedValue(sseResponse([]))
    let capturedTaskId
    const execute = vi.fn(({ taskId }) => {
      capturedTaskId = taskId
      return 'R'
    })

    await expect(runAiTask({ projectId: 1, label: 'L', execute })).resolves.toBe('R')

    // 格式满足服务端 [A-Za-z0-9_-]{8,64}
    expect(capturedTaskId).toMatch(/^aitask-[a-z0-9]+-[a-z0-9]+$/)
  })

  it('generateTaskId_whenCryptoRandomUUIDAvailable_shouldBuildExactUuidTaskId', async () => {
    const randomUUID = vi.fn(() => '11111111-2222-3333-4444-555555555555')
    vi.stubGlobal('crypto', { randomUUID })
    fetchMock.mockResolvedValue(sseResponse([]))
    let capturedTaskId
    const execute = vi.fn(({ taskId }) => {
      capturedTaskId = taskId
      return 'R'
    })

    await expect(runAiTask({ projectId: 1, label: 'L', execute })).resolves.toBe('R')

    expect(capturedTaskId).toBe('aitask-11111111-2222-3333-4444-555555555555')
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it('generateTaskId_whenCryptoExistsButLacksRandomUUID_shouldFallbackToTimestampTaskId', async () => {
    // crypto 存在但没有 randomUUID 方法：应走降级分支而非调用不存在的方法
    vi.stubGlobal('crypto', {})
    fetchMock.mockResolvedValue(sseResponse([]))
    let capturedTaskId
    const execute = vi.fn(({ taskId }) => {
      capturedTaskId = taskId
      return 'R'
    })

    await expect(runAiTask({ projectId: 1, label: 'L', execute })).resolves.toBe('R')

    expect(capturedTaskId).toMatch(/^aitask-[a-z0-9]+-[a-z0-9]+$/)
  })

  it('cancelActiveAiTask_whenTaskShownButTransportsAbsent_shouldUpdateStageWithoutThrowing', () => {
    // activeTask 有值但控制器为空（未真实启动传输）：守卫应跳过 abort 而非抛错
    activeTask.value = { id: 'aitask-ghost', label: 'L', stage: '处理中', startedAt: 0, elapsedSec: 0 }

    expect(() => cancelActiveAiTask()).not.toThrow()
    expect(activeTask.value.stage).toBe('正在终止…')

    activeTask.value = null
  })

  it('elapsedTick_whenActiveTaskCleared_shouldSkipUpdateWithoutThrowing', async () => {
    // 定时回调在 activeTask 为 null 时须安全跳过，不得读取 null.id
    let tick
    const setIntervalSpy = vi.spyOn(globalThis, 'setInterval').mockImplementation((fn) => {
      tick = fn
      return 12345
    })
    vi.spyOn(globalThis, 'clearInterval').mockImplementation(() => {})
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    expect(typeof tick).toBe('function')

    activeTask.value = null
    expect(() => tick()).not.toThrow()

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')

    setIntervalSpy.mockRestore()
  })

  it('runAiTask_whenExecuteRejectsWithErrCanceledCodeAndNoCancelRequest_shouldReturnSentinel', async () => {
    // 仅凭 code=ERR_CANCELED 判定终止：不经过 cancelActiveAiTask 也应返回哨兵
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = vi.fn(() =>
      Promise.reject(Object.assign(new Error('canceled by code'), { code: 'ERR_CANCELED' }))
    )

    const result = await runAiTask({ projectId: 1, label: 'L', execute })

    expect(result).toBe(AI_TASK_CANCELLED)
    expect(infoMock).toHaveBeenCalledWith('已终止本次任务')
    expect(activeTask.value).toBeNull()
  })

  it('runAiTask_whenExecuteRejectsWithNull_shouldRethrowNullWithoutTouchingFields', async () => {
    // 拒绝值为 null：可选链须短路，原样抛出 null 而非在 error 上取字段抛 TypeError
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = vi.fn(() => Promise.reject(null))

    const caught = await runAiTask({ projectId: 1, label: 'L', execute }).catch((err) => err)

    expect(caught).toBeNull()
    expect(activeTask.value).toBeNull()
  })

  it('cleanupAiTask_whenTaskCompletes_shouldClearElapsedInterval', async () => {
    vi.useFakeTimers()
    fetchMock.mockResolvedValue(sseResponse([]))
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await vi.advanceTimersByTimeAsync(0)
    resolveExecute('R')
    await runPromise

    // 任务收尾后周期计时器必须被清除，不允许遗留
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cleanupAiTask_whenTaskCompletes_shouldAbortSseTransport', async () => {
    let sseSignal
    fetchMock.mockImplementation((url, options) => {
      sseSignal = options.signal
      return Promise.resolve(sseResponse([]))
    })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')

    expect(sseSignal.aborted).toBe(true)
  })

  it('subscribeProgress_whenResponseNotOk_shouldSkipStreamProcessingEvenIfBodyPresent', async () => {
    // ok=false 且 body 存在：直接返回，绝不消费流事件
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 503, ok: false, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push('data: {"message":"不应被处理"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('正在连接 AI 服务…')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenMultibyteCharSplitAcrossChunks_shouldDecodeWithStreaming', async () => {
    // UTF-8 多字节字符跨 chunk 切断：须以 stream 模式解码，避免替换字符污染消息
    let controller
    const stream = new ReadableStream({ start(c) { controller = c } })
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    // 'data: {"message":"' 恰 18 字节，1 个汉字 3 字节；在 20 字节处切在第 1 个汉字中间
    const bytes = new TextEncoder().encode('data: {"message":"中文进度"}\n\n')
    controller.enqueue(bytes.slice(0, 20))
    await flush()
    controller.enqueue(bytes.slice(20))
    await flush()

    expect(activeTask.value.stage).toBe('中文进度')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenNonDataLineHappensToBeValidJson_shouldStillSkipIt', async () => {
    // 无 data: 前缀的行必须跳过。字符串刻意设计为「剥掉前 5 字符后恰是合法 JSON」，
    // 使跳过与否仅取决于前缀判定，而非顺带被 JSON.parse 失败掩盖
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push('00000{"message":"无前缀不应生效"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('正在连接 AI 服务…')

    sse.push('data: {"message":"正常事件"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('正常事件')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenDataLineHasUnicodeWhitespace_shouldTrimBeforeParse', async () => {
    // U+00A0 不属于 JSON 允许的空白：解析前必须 trim 才能成功
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push('data: \u00A0{"message":"nbsp前缀"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('nbsp前缀')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenDoneEvent_shouldCancelReaderStream', async () => {
    // DONE 后必须主动 reader.cancel()，让服务端感知订阅结束
    let controller
    const cancelSpy = vi.fn()
    const stream = new ReadableStream({ start(c) { controller = c }, cancel: cancelSpy })
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    controller.enqueue(new TextEncoder().encode('data: {"stage":"DONE"}\n\n'))
    await flush()
    await flush()
    expect(cancelSpy).toHaveBeenCalledTimes(1)

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenActiveTaskVanishesMidStream_shouldKeepSubscriptionAlive', async () => {
    // activeTask 为 null 的事件应被跳过而非中断订阅：后续事件仍要能生效
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    const realTask = activeTask.value
    activeTask.value = null
    sse.push('data: {"message":"任务不在时的事件"}\n\n')
    await flush()
    activeTask.value = realTask

    sse.push('data: {"message":"恢复后的事件"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('恢复后的事件')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })

  it('subscribeProgress_whenEventHasNeitherMessageNorTaskId_shouldKeepCurrentStage', async () => {
    // 仅有 stage 字段的事件不满足任何展示分支：保持当前阶段不被覆写
    const sse = controlledSseStream()
    fetchMock.mockResolvedValue({ status: 200, ok: true, body: sse.stream })
    const execute = pendingExecute()

    const runPromise = runAiTask({ projectId: 1, label: 'L', execute })
    await flush()

    sse.push('data: {"message":"阶段X"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('阶段X')

    sse.push('data: {"stage":"SCANNING"}\n\n')
    await flush()
    expect(activeTask.value.stage).toBe('阶段X')

    resolveExecute('R')
    await expect(runPromise).resolves.toBe('R')
  })
})
