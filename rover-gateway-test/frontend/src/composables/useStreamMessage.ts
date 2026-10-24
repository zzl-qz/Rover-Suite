import { onBeforeUnmount, ref } from 'vue'

type StreamEvent = { event: string; data: string; id?: string }
type StreamOptions = {
  sessionId: string
  message: string
  onTask?: (taskId: string, resumed?: boolean) => void | Promise<void>
  onDelta?: (text: string) => void
  onSnapshot?: (text: string) => void
  onFinish?: (status: string, taskId: string) => void
  onError?: (message: string, taskId?: string) => void
  onStop?: (taskId?: string) => void
}

const API = '/api/agent'
const TERMINAL_EVENTS = new Set(['TASK_COMPLETED', 'TASK_FAILED', 'TASK_CANCELLED', 'CLARIFICATION_REQUIRED'])

function cookieCsrfHeaders(): Record<string, string> {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  const token = match ? decodeURIComponent(match[1]) : ''
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

async function ensureCsrfHeaders() {
  const headers = cookieCsrfHeaders()
  if (headers['X-XSRF-TOKEN']) return headers
  try {
    const response = await fetch('/api/auth/status', { headers: { Accept: 'application/json' } })
    if (!response.ok) return headers
    const status = await response.json()
    return status.csrfToken ? { [status.csrfHeaderName || 'X-XSRF-TOKEN']: status.csrfToken } : headers
  } catch { return headers }
}

async function readSse(response: Response, signal: AbortSignal, onEvent: (event: StreamEvent) => boolean | void) {
  if (!response.body) throw new Error('浏览器未提供 SSE 响应流')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let lineBuffer = ''
  let eventName = 'message'
  let eventId: string | undefined
  let dataLines: string[] = []
  let shouldStop = false

  const dispatch = () => {
    if (dataLines.length && onEvent({ event: eventName, data: dataLines.join('\n'), id: eventId })) shouldStop = true
    eventName = 'message'
    dataLines = []
  }
  const consumeLine = (line: string) => {
    if (!line) return dispatch()
    if (line.startsWith(':')) return
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') eventName = value
    else if (field === 'id' && !value.includes('\0')) eventId = value
    else if (field === 'data') dataLines.push(value)
  }

  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (done) break
      lineBuffer += decoder.decode(value, { stream: true })
      let newline = lineBuffer.indexOf('\n')
      while (newline >= 0) {
        const line = lineBuffer.slice(0, newline).replace(/\r$/, '')
        lineBuffer = lineBuffer.slice(newline + 1)
        consumeLine(line)
        if (shouldStop) break
        newline = lineBuffer.indexOf('\n')
      }
      if (shouldStop) { await reader.cancel().catch(() => undefined); break }
    }
    lineBuffer += decoder.decode()
    if (lineBuffer) consumeLine(lineBuffer.replace(/\r$/, ''))
    dispatch()
  } finally {
    if (signal.aborted) await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}

async function responseError(response: Response) {
  let body: any = null
  try { body = await response.json() } catch { /* 响应不一定是 JSON */ }
  const error = new Error(body?.message || `请求失败（HTTP ${response.status}）`) as Error & { status?: number; body?: any }
  error.status = response.status
  error.body = body
  return error
}

export function useStreamMessage() {
  const isStreaming = ref(false)
  const isSubmitting = ref(false)
  let controller: AbortController | null = null
  let activeTaskId = ''
  let generation = 0
  let intentionalStop = false
  let onIntentionalStop: (() => void) | undefined
  let intentionalStopNotified = false
  let stopInFlight = false

  const abort = () => {
    generation += 1
    controller?.abort()
    controller = null
    activeTaskId = ''
    isStreaming.value = false
    isSubmitting.value = false
  }

  const stop = async () => {
    if (stopInFlight) return
    stopInFlight = true
    const taskId = activeTaskId
    intentionalStop = true
    intentionalStopNotified = true
    const notifyStopped = onIntentionalStop
    onIntentionalStop = undefined
    abort()
    notifyStopped?.(taskId || undefined)
    // 先停本地讀取，再通知後端協作式取消正在執行的任務。
    if (taskId) {
      try {
        const response = await fetch(`${API}/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST', headers: await ensureCsrfHeaders() })
        if (!response.ok && response.status !== 409) throw await responseError(response)
      } catch { /* 本地流已中止；取消失敗由任務狀態輪詢對齊 */ }
    }
    stopInFlight = false
  }

  const send = async (options: StreamOptions) => {
    // Ignore duplicate submits while either request or event stream is active.
    if (controller) return
    const requestGeneration = ++generation
    const requestController = new AbortController()
    controller = requestController
    let requestTaskId = ''
    intentionalStop = false
    onIntentionalStop = options.onStop
    intentionalStopNotified = false
    isSubmitting.value = true

    const isCurrent = () => generation === requestGeneration && !requestController.signal.aborted
    try {
      const csrf = await ensureCsrfHeaders()
      if (!isCurrent()) return
      const response = await fetch(`${API}/sessions/${encodeURIComponent(options.sessionId)}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...csrf },
        body: JSON.stringify({ message: options.message }),
        signal: requestController.signal,
      })
      let task: { taskId: string }
      if (response.status === 409) {
        const conflict = await responseError(response)
        if (!conflict.body?.taskId) throw conflict
        task = { taskId: conflict.body.taskId }
      } else {
        if (!response.ok) throw await responseError(response)
        task = await response.json()
      }
      requestTaskId = task.taskId
      if (!isCurrent()) {
        void fetch(`${API}/tasks/${encodeURIComponent(task.taskId)}/cancel`, { method: 'POST', headers: await ensureCsrfHeaders() }).catch(() => undefined)
        return
      }
      activeTaskId = task.taskId
      await options.onTask?.(task.taskId, response.status === 409)
      if (!isCurrent()) return
      isSubmitting.value = false
      isStreaming.value = true

      const eventsUrl = `${API}/tasks/${encodeURIComponent(task.taskId)}/events`
      let terminalReceived = false
      const stream = await fetch(eventsUrl, { headers: { Accept: 'text/event-stream' }, signal: requestController.signal })
      if (!stream.ok) throw await responseError(stream)
      await readSse(stream, requestController.signal, ({ event, data, id }) => {
        if (!isCurrent() || terminalReceived) return
        let envelope: any
        try { envelope = JSON.parse(data) } catch { return }
        const payload = envelope?.payload
        if (!payload) return
        if (event === 'SNAPSHOT' && typeof payload.analysis === 'string' && payload.analysis) {
          options.onSnapshot?.(payload.analysis)
        } else if (event === 'ANALYSIS_DELTA' && typeof payload.text === 'string' && payload.text) {
          options.onDelta?.(payload.text)
        }
        if (TERMINAL_EVENTS.has(event)) {
          terminalReceived = true
          if (event === 'TASK_FAILED') options.onError?.(payload.task?.error || payload.error || '任务执行失败', task.taskId)
          else if (event === 'TASK_CANCELLED' && !intentionalStopNotified) options.onStop?.(task.taskId)
          options.onFinish?.(event, task.taskId)
          return true
        }
        return false
      })
      if (terminalReceived) isStreaming.value = false
      if (isCurrent() && isStreaming.value) throw new Error('事件流提前结束')
    } catch (error) {
      if (requestGeneration !== generation) return
      if (requestController.signal.aborted) {
        if (intentionalStop && !intentionalStopNotified) options.onStop?.(requestTaskId || undefined)
      } else {
        options.onError?.((error as Error).message || '网络连接失败', requestTaskId || undefined)
      }
    } finally {
      if (requestGeneration === generation) {
        controller = null
        activeTaskId = ''
        onIntentionalStop = undefined
        isSubmitting.value = false
        isStreaming.value = false
      }
    }
  }

  onBeforeUnmount(abort)
  return { isStreaming, isSubmitting, send, stop, abort }
}
