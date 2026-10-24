<template>
  <main class="workspace">
    <aside class="sessions">
      <div class="sessions-head"><div><small>ROVER / OPERATIONS</small><h1>Agent 工作台</h1></div><button type="button" @click="newSession">＋</button></div>
      <p v-if="loadError" class="error-line">{{ loadError }}</p>
      <p v-if="authError" class="auth-line">{{ authError }} · <a href="/login.html?redirect=%2F">前往登录</a></p>
      <button v-for="session in sessions" :key="session.sessionId" type="button" class="session-row" :class="{ active: session.sessionId === activeSessionId }" @click="switchSession(session.sessionId)">
        <span class="session-dot"></span><span><b>{{ session.title || '新建调查' }}</b><small>{{ formatTime(session.lastActiveAtMillis || session.createdAtMillis) }}</small></span>
      </button>
      <p v-if="!sessions.length" class="empty-note">暂无会话</p>
    </aside>

    <section class="chat-panel">
      <header class="chat-head"><div><span class="eyebrow">LIVE INVESTIGATION</span><h2>{{ activeSession?.title || '运维调查' }}</h2></div><span class="connection"><i></i>{{ isStreaming ? '正在生成' : isSubmitting ? '正在提交' : '就绪' }}</span><a class="exit-link" href="/">返回网关测试</a></header>
      <div ref="scrollArea" class="message-list" @scroll="onScroll">
        <div v-if="!items.length" class="welcome"><span>R</span><h3>从一个运维问题开始</h3><p>描述异常现象、路由或服务，Agent 会整理调查线索。</p></div>
        <template v-for="item in items" :key="item.id">
          <MessageItem v-if="item.kind === 'message'" :role="item.role" :snapshot="item.content" />
          <MessageItem v-else :key="item.id" :ref="(el) => setStreamRef(item.id, el)" role="assistant" :streaming="item.streaming" :hint="item.hint" @content="scrollToBottom(false)" />
        </template>
      </div>
      <div class="composer-wrap">
        <p class="composer-help">Enter 发送 · Shift+Enter 换行</p>
        <InputBox ref="inputBox" v-model="draft" :sending="isStreaming || isSubmitting" :disabled="!apiReady" @send="sendMessage" @stop="stopMessage" />
        <p class="footer-note">调查会读取当前账号有权限访问的 Rover 运行数据</p>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import InputBox from '../components/InputBox.vue'
import MessageItem from '../components/MessageItem.vue'
import { useStreamMessage } from '../composables/useStreamMessage'

type Session = { sessionId: string; title?: string; createdAtMillis?: number; lastActiveAtMillis?: number }
type TimelineItem = { id: string; kind: 'message'; role: 'user' | 'assistant'; content: string } | { id: string; kind: 'stream'; role: 'assistant'; streaming: boolean; hint: string }
type MessageRenderer = { appendDelta(text: string): void; appendSnapshot(text: string): void; finish(): void; reset(): void }
type ApiError = Error & { status?: number; body?: any }

const sessions = ref<Session[]>([])
const activeSessionId = ref('')
const workspace = ref<any>(null)
const draft = ref('')
const items = ref<TimelineItem[]>([])
const loadError = ref('')
const authError = ref('')
const apiReady = ref(true)
const scrollArea = ref<HTMLElement | null>(null)
const inputBox = ref<InstanceType<typeof InputBox> | null>(null)
const streamRenderers = new Map<string, MessageRenderer>()
const streamItems = new Map<string, Extract<TimelineItem, { kind: 'stream' }>>()
const activeTaskId = ref('')
const isUserScrolling = ref(false)
const { isStreaming, isSubmitting, send, stop, abort } = useStreamMessage()
let scrollResetTimer: ReturnType<typeof setTimeout> | undefined
let scrollFrame = 0
let destroyed = false

const activeSession = computed(() => sessions.value.find(session => session.sessionId === activeSessionId.value))

function cookieCsrfHeaders() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  const token = match ? decodeURIComponent(match[1]) : ''
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

async function csrfHeaders() {
  const headers = cookieCsrfHeaders()
  if (headers['X-XSRF-TOKEN']) return headers
  try {
    const response = await fetch('/api/auth/status', { headers: { Accept: 'application/json' } })
    if (!response.ok) return headers
    const status = await response.json()
    return status.csrfToken ? { [status.csrfHeaderName || 'X-XSRF-TOKEN']: status.csrfToken } : headers
  } catch { return headers }
}

async function request(path: string, init?: RequestInit) {
  const response = await fetch(`/api/agent${path}`, init)
  if (!response.ok) {
    let body: any
    try { body = await response.json() } catch { /* 空响应 */ }
    const error = new Error(body?.message || `HTTP ${response.status}`) as ApiError
    error.status = response.status
    error.body = body
    throw error
  }
  return response.status === 204 ? null : response.json()
}

async function cancelTask(taskId: string) {
  try { await fetch(`/api/agent/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST', headers: await csrfHeaders() }) }
  catch { /* 本地读取已停止 */ }
}

async function loadSessions() {
  try { sessions.value = await request('/sessions') || []; apiReady.value = true; loadError.value = '' }
  catch (error) {
    const apiError = error as ApiError
    apiReady.value = false
    if (apiError.status === 401 || apiError.status === 403) {
      authError.value = '需要先登录 Rover Admin'
      try { await fetch('/api/auth/status') } catch { /* 登录提示仍可用 */ }
    } else loadError.value = `无法读取会话：${apiError.message}`
  }
}

function sortedMessages(messages: any[], tasks: any[]) {
  const taskById = new Map((tasks || []).map((task: any) => [task.taskId, task]))
  const lastAgentByTask = new Map<string, any>()
  for (const message of messages || []) {
    if (message.role !== 'AGENT' || !message.relatedTaskId || !taskById.has(message.relatedTaskId)) continue
    const previous = lastAgentByTask.get(message.relatedTaskId)
    if (!previous || message.createdAtMillis >= previous.createdAtMillis) lastAgentByTask.set(message.relatedTaskId, message)
  }
  const emittedAnswers = new Set<string>()
  return (messages || []).flatMap((message: any) => {
    const isUser = message.role === 'USER' || message.role === 'user'
    const task: any = message.relatedTaskId && taskById.get(message.relatedTaskId)
    if (!isUser && task && lastAgentByTask.get(task.taskId) !== message) return []
    const timeline: TimelineItem[] = [{ id: `message-${message.messageId || message.id}`, kind: 'message', role: isUser ? 'user' : 'assistant', content: message.content || '' }]
    const answer = task?.result?.aiAnalysis
    if (!isUser && answer && !emittedAnswers.has(task.taskId) && answer !== message.content) {
      emittedAnswers.add(task.taskId)
      timeline.push({ id: `task-${task.taskId}`, kind: 'message', role: 'assistant', content: answer })
    }
    return timeline
  })
}

async function loadSession(sessionId: string, initial = false) {
  if (!sessionId) { workspace.value = null; items.value = []; return }
  const currentItems = items.value
  try {
    const result = await request(`/sessions/${encodeURIComponent(sessionId)}/workspace?limit=50`)
    if (activeSessionId.value !== sessionId || destroyed) return
    workspace.value = result
    const localItems = currentItems.filter((item): item is Extract<TimelineItem, { kind: 'stream' }> => item.kind === 'stream' && item.id.startsWith('stream-'))
    const localTaskItems = localItems
    const timeline = sortedMessages(result.messages, result.tasks)
    for (const taskItem of localTaskItems) {
      const taskId = taskItem.id.slice('stream-'.length)
      const task = result.tasks?.find((entry: any) => entry.taskId === taskId)
      const answer = task?.result?.aiAnalysis
      const answerAlreadyShown = answer && timeline.some(entry => entry.kind === 'message' && entry.role === 'assistant' && entry.content === answer)
      if (task?.status === 'COMPLETED' && answer && !answerAlreadyShown) timeline.push({ id: `task-${taskId}`, kind: 'message', role: 'assistant', content: answer })
      else if (task?.status !== 'COMPLETED' || !answer || !answerAlreadyShown) timeline.push(taskItem)
    }
    items.value = timeline
    if (initial) { isUserScrolling.value = false; scrollToBottom(true) }
    else scrollToBottom(false)
  } catch (error) {
    if (activeSessionId.value === sessionId) loadError.value = `会话加载失败：${(error as Error).message}`
  }
}

async function switchSession(sessionId: string) {
  if (!sessionId || sessionId === activeSessionId.value) return
  const runningTaskId = activeTaskId.value
  const wasBusy = isStreaming.value || isSubmitting.value
  abort()
  if (wasBusy && runningTaskId) {
    void cancelTask(runningTaskId)
    const item = streamItems.get(runningTaskId)
    if (item) { item.streaming = false; item.hint = '切换会话时已停止生成' }
  }
  activeTaskId.value = ''
  streamRenderers.clear()
  streamItems.clear()
  items.value = items.value.filter(item => item.kind !== 'stream')
  activeSessionId.value = sessionId
  isUserScrolling.value = false
  await loadSession(sessionId, true)
}

async function newSession() {
  if (isSubmitting.value || isStreaming.value) {
    const taskId = activeTaskId.value
    await stop()
    if (taskId) void cancelTask(taskId)
    if (taskId) streamItems.get(taskId) && (streamItems.get(taskId)!.streaming = false, streamItems.get(taskId)!.hint = '已停止生成')
  }
  abort()
  try {
    const session = await request('/sessions', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) }, body: '{}' })
    await loadSessions()
    activeSessionId.value = ''
    await switchSession(session.sessionId)
  } catch (error) { loadError.value = `新建会话失败：${(error as Error).message}` }
}

function setStreamRef(id: string, renderer: MessageRenderer | null) {
  if (renderer) streamRenderers.set(id, renderer)
  else streamRenderers.delete(id)
}

async function sendMessage() {
  const message = draft.value
  if (!message.trim() || isSubmitting.value || isStreaming.value || !apiReady.value) return
  draft.value = ''
  let sessionId = activeSessionId.value
  try {
    if (!sessionId) {
      const created = await request('/sessions', { method: 'POST', headers: { 'Content-Type': 'application/json', ...(await csrfHeaders()) }, body: '{}' })
      sessionId = created.sessionId
      activeSessionId.value = sessionId
      await loadSessions()
    }
    const pendingKey = `local-${Date.now()}`
    items.value.push({ id: pendingKey, kind: 'message', role: 'user', content: message })
    scrollToBottom(true)
    await send({
      sessionId,
      message,
      onTask: async (taskId, resumed = false) => {
        if (resumed) {
          items.value = items.value.filter(item => item.id !== pendingKey)
          const existing = streamRenderers.get(`stream-${taskId}`)
          existing?.reset()
          await loadSession(sessionId)
        }
        if (activeSessionId.value !== sessionId || destroyed) return
        activeTaskId.value = taskId
        let streamItem = streamItems.get(taskId)
        if (!streamItem) {
          streamItem = { id: `stream-${taskId}`, kind: 'stream', role: 'assistant', streaming: true, hint: '' }
          streamItems.set(taskId, streamItem)
          items.value.push(streamItem)
        }
        await nextTick()
        scrollToBottom(false)
      },
      onSnapshot: (text) => {
        const item = streamItems.get(activeTaskId.value)
        if (item?.kind === 'stream') {
          const renderer = streamRenderers.get(item.id)
          renderer?.appendSnapshot(text)
          const task = workspace.value?.tasks?.find((entry: any) => entry.taskId === activeTaskId.value)
          if (task && !task.terminal && isUserScrolling.value) scrollToBottom(false)
        }
      },
      onDelta: (text) => {
        const item = streamItems.get(activeTaskId.value)
        if (item?.kind === 'stream') streamRenderers.get(item.id)?.appendDelta(text)
      },
      onFinish: (_status, taskId) => {
        const item = streamItems.get(taskId)
        if (item?.kind === 'stream') { item.streaming = false; streamRenderers.get(item.id)?.finish() }
        if (_status === 'TASK_FAILED' && item?.kind === 'stream' && !item.hint) item.hint = '任务执行失败，请重试或调整问题后再发送。'
        if (_status === 'CLARIFICATION_REQUIRED' && item?.kind === 'stream') item.hint = '需要补充调查条件，请继续描述。'
        if (_status === 'TASK_CANCELLED' && item?.kind === 'stream' && !item.hint) item.hint = '任务已取消'
        if (item?.kind === 'stream') streamRenderers.get(item.id)?.finish()
        streamItems.delete(taskId)
        if (activeTaskId.value === taskId) activeTaskId.value = ''
        void loadSessions()
      },
      onStop: (taskId = activeTaskId.value) => {
        if (!taskId) items.value = items.value.filter(item => item.id !== pendingKey)
        const item = streamItems.get(taskId)
        if (item?.kind === 'stream') { item.streaming = false; item.hint = '已停止生成'; streamRenderers.get(item.id)?.finish() }
        streamItems.delete(taskId)
        if (activeTaskId.value === taskId) activeTaskId.value = ''
      },
      onError: (message, taskId = activeTaskId.value) => {
        const item = streamItems.get(taskId)
        if (item?.kind === 'stream') { item.streaming = false; item.hint = `连接中断：${message}`; streamRenderers.get(item.id)?.finish() }
        else {
          items.value = items.value.filter(candidate => candidate.id !== pendingKey)
          if (message.includes('已有') || message.startsWith('请求失败（HTTP 409）')) void loadSession(sessionId)
          else {
            if (!draft.value.trim()) draft.value = message
            loadError.value = `提问失败：${message}`
          }
        }
        if (taskId) streamItems.delete(taskId)
        if (activeTaskId.value === taskId) activeTaskId.value = ''
      },
    })
  } catch (error) {
    draft.value = message
    const apiError = error as ApiError
    loadError.value = `发送失败：${apiError.message}`
  } finally {
    inputBox.value?.focus()
  }
}

function stopMessage() { void stop() }

function scrollToBottom(force: boolean) {
  if (!force && isUserScrolling.value) return
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  scrollFrame = requestAnimationFrame(() => {
    const area = scrollArea.value
    if (area && (force || !isUserScrolling.value)) area.scrollTop = area.scrollHeight
    scrollFrame = 0
  })
}

function onScroll() {
  const area = scrollArea.value
  if (!area) return
  const atBottom = area.scrollHeight - area.scrollTop - area.clientHeight < 40
  isUserScrolling.value = !atBottom
  // 150ms 后再确认滚动意图，靠近底部时恢复自动跟随。
  clearTimeout(scrollResetTimer)
  scrollResetTimer = setTimeout(() => { if (area.scrollHeight - area.scrollTop - area.clientHeight < 40) isUserScrolling.value = false }, 150)
}

function formatTime(value?: number) { return value ? new Date(value).toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '新会话' }

onMounted(async () => {
  await loadSessions()
  if (sessions.value.length) { activeSessionId.value = sessions.value[0].sessionId; await loadSession(activeSessionId.value, true) }
})
onBeforeUnmount(() => {
  const taskId = activeTaskId.value
  destroyed = true
  abort()
  if (taskId) void cancelTask(taskId)
  clearTimeout(scrollResetTimer)
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  streamRenderers.clear(); streamItems.clear()
})
</script>

<style scoped>
.workspace {
  --color-primary: #647c67;
  --color-text-main: #1f2937;
  --color-text-secondary: #6b7280;
  --color-border: #d1d5db;
  --color-border-focus: #647c67;
  --color-bg-input: #ffffff;
  --color-bg-disabled: #f3f4f6;
  --color-danger-soft: #dc2626;
  --radius: 8px;
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.06);
  --transition-fast: 120ms ease-out;
}
:global(*) { box-sizing: border-box; }
:global(html), :global(body), :global(#app) { width: 100%; min-height: 100%; margin: 0; }
:global(body) { min-height: 100vh; background: #eef0ec; font-family: Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; }
button, textarea { font: inherit; }
.workspace { display: grid; grid-template-columns: 260px minmax(0, 1fr); width: min(1260px, calc(100vw - 48px)); height: min(860px, calc(100vh - 48px)); min-height: 500px; margin: 24px auto; overflow: hidden; border: 1px solid #d7dbd5; background: #fff; color: #1f2937; box-shadow: 0 12px 34px rgba(31, 41, 35, .08); }
.sessions { min-height: 0; overflow-y: auto; border-right: 1px solid #e2e5e0; background: #f5f6f3; padding: 20px 14px; }
.sessions-head { display: flex; align-items: center; justify-content: space-between; margin: 0 2px 23px; }
.sessions-head small, .eyebrow { color: #7a8479; font-size: 10px; letter-spacing: .12em; }
.sessions-head h1 { margin: 5px 0 0; font-size: 16px; font-weight: 650; }
.sessions-head button { width: 32px; height: 32px; border: 1px solid #cdd4cc; border-radius: 6px; background: #fff; color: #647c67; font-size: 19px; cursor: pointer; }
.session-row { display: flex; gap: 10px; width: 100%; padding: 11px 9px; border: 0; border-radius: 4px; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.session-row:hover, .session-row.active { background: #e9ede7; }
.session-dot { width: 7px; height: 7px; flex: 0 0 auto; margin-top: 5px; border-radius: 50%; background: #99a398; }
.session-row.active .session-dot { background: #647c67; }
.session-row span:last-child { min-width: 0; display: grid; gap: 5px; }
.session-row b { overflow: hidden; font-size: 12px; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }
.session-row small, .empty-note { color: #879087; font-size: 11px; }
.chat-panel { display: flex; min-width: 0; min-height: 0; flex-direction: column; background: #fff; }
.chat-head { display: flex; height: 69px; flex: 0 0 auto; align-items: center; justify-content: space-between; padding: 0 24px; border-bottom: 1px solid #e6e9e5; }
.chat-head h2 { margin: 3px 0 0; font-size: 15px; font-weight: 620; }
.exit-link { margin-left: 18px; color: #647c67; font-size: 11px; text-decoration: none; }
.connection { display: flex; align-items: center; gap: 7px; color: #788178; font-size: 11px; }
.connection i { width: 7px; height: 7px; border-radius: 50%; background: #849782; }
.message-list { position: relative; display: flex; flex: 1; min-height: 0; flex-direction: column; gap: 22px; overflow-y: auto; padding: 28px max(28px, calc((100% - 840px) / 2)); scroll-behavior: auto; }
.welcome { margin: auto; padding: 32px 20px; text-align: center; color: #788178; }
.welcome > span { display: grid; width: 38px; height: 38px; margin: 0 auto 14px; place-items: center; background: #647c67; color: white; font-family: Georgia, serif; font-size: 20px; }
.welcome h3 { margin: 0 0 7px; color: #354039; font-size: 15px; }
.welcome p { max-width: 330px; margin: 0; font-size: 12px; line-height: 1.6; }
.composer-wrap { flex: 0 0 auto; padding: 8px 28px 16px; border-top: 1px solid #eff1ee; }
.composer-help, .footer-note { margin: 0 0 7px; color: #8a9289; font-size: 10px; }
.footer-note { margin: 8px 0 0; }
.error-line { color: #b45353; font-size: 12px; line-height: 1.6; }
.auth-line { color: #805b32; font-size: 11px; line-height: 1.6; }
.auth-line a { color: #536b57; }
@media (max-width: 760px) { .workspace { grid-template-columns: 1fr; width: 100%; height: 100dvh; min-height: 0; margin: 0; border: 0; } .sessions { display: flex; align-items: center; gap: 8px; overflow-x: auto; overflow-y: hidden; padding: 8px 12px; border-right: 0; border-bottom: 1px solid #e2e5e0; } .sessions-head { flex: 0 0 auto; gap: 8px; margin: 0 8px 0 0; } .sessions-head small { display: none; } .sessions-head h1 { font-size: 13px; } .sessions-head button { width: 28px; height: 28px; } .session-row { width: 150px; flex: 0 0 auto; padding: 8px; } .session-row small { display: none; } .chat-head { height: 58px; padding: 0 16px; } .message-list { padding: 20px 16px; } .composer-wrap { padding: 8px 12px 12px; } }
</style>
