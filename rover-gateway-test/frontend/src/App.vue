<template>
  <div class="container">
    <!-- 全局 toast 提示：请求结果即时反馈，无需打开控制台 -->
    <div class="toast-container">
      <div v-for="t in toasts" :key="t.id" :class="['toast', t.type]">
        {{ t.message }}
      </div>
    </div>

    <div class="header">
      <h1>🚀 Rover Gateway Test</h1>
      <p class="subtitle">完整测试网关代理、负载均衡、请求头传递、超时处理等</p>
    </div>

    <!-- 网关配置 -->
    <div class="config-panel">
      <label>网关地址：</label>
      <input v-model="gatewayUrl" placeholder="http://localhost" />
      <button @click="updateGateway">更新</button>
      <button @click="resetStats" class="btn-reset">重置统计</button>
      <p class="config-hint">默认对齐仓库 Gateway 配置端口 80；若你改过网关端口，这里一并改。</p>
    </div>

    <!-- 基础接口测试 -->
    <div class="test-section">
      <h3>📦 基础接口测试</h3>
      <div class="button-group">
        <button @click="testHello" :disabled="loading" class="btn btn-primary">
          GET /api/hello
        </button>
        <button @click="testEcho" :disabled="loading" class="btn btn-secondary">
          GET /api/echo
        </button>
        <button @click="testHealth" :disabled="loading" class="btn btn-success">
          GET /api/health
        </button>
        <button @click="testInfo" :disabled="loading" class="btn btn-info">
          GET /api/info
        </button>
      </div>
    </div>

    <!-- 请求头测试 -->
    <div class="test-section">
      <h3>🔐 请求头测试</h3>
      <div class="header-config">
        <div class="header-row">
          <label>Authorization:</label>
          <input v-model="customHeaders.Authorization" placeholder="Bearer token123" />
        </div>
        <div class="header-row">
          <label>X-Request-ID:</label>
          <input v-model="customHeaders['X-Request-ID']" placeholder="req-12345" />
        </div>
        <div class="header-row">
          <label>X-Custom-Header:</label>
          <input v-model="customHeaders['X-Custom-Header']" placeholder="custom-value" />
        </div>
      </div>
      <div class="button-group">
        <button @click="testWithHeaders" :disabled="loading" class="btn btn-warning">
          GET /api/headers（携带自定义头）
        </button>
      </div>
    </div>

    <!-- POST 数据测试 -->
    <div class="test-section">
      <h3>📤 POST 数据测试</h3>
      <div class="button-group">
        <button @click="testData" :disabled="loading" class="btn btn-secondary">
          POST /api/data（简单 JSON）
        </button>
        <button @click="testBatch" :disabled="loading" class="btn btn-secondary">
          POST /api/batch（大数据量）
        </button>
      </div>
    </div>

    <!-- 超时与延迟测试 -->
    <div class="test-section">
      <h3>⏱️ 超时与延迟测试</h3>
      <div class="delay-config">
        <label>延迟时间 (ms)：</label>
        <input v-model.number="delayMs" type="number" min="0" max="10000" step="100" />
      </div>
      <div class="button-group">
        <button @click="testDelay" :disabled="loading" class="btn btn-warning">
          GET /api/delay（模拟慢响应）
        </button>
        <button @click="testTimeout1s" :disabled="loading" class="btn btn-danger">
          超时测试（1s 超时）
        </button>
      </div>
    </div>

    <!-- 并发测试 -->
    <div class="test-section">
      <h3>🔀 并发测试（负载均衡验证）</h3>
      <div class="concurrent-config">
        <label>并发数：</label>
        <input v-model.number="concurrentCount" type="number" min="1" max="50" />
      </div>
      <div class="button-group">
        <button @click="testConcurrent" :disabled="loading" class="btn btn-primary">
          并发请求（观察端口分布）
        </button>
      </div>
    </div>

    <!-- 错误场景测试 -->
    <div class="test-section">
      <h3>❌ 错误场景测试</h3>
      <div class="button-group">
        <button @click="test404" :disabled="loading" class="btn btn-danger">
          GET /api/not-exist（404）
        </button>
        <button @click="testError" :disabled="loading" class="btn btn-danger">
          GET /api/error（模拟错误）
        </button>
      </div>
    </div>

    <!-- 统计面板 -->
    <div class="stats-panel">
      <h3>📊 统计信息</h3>
      <div class="stats-grid">
        <div class="stat-item">
          <div class="stat-label">总请求数</div>
          <div class="stat-value">{{ stats.total }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">成功</div>
          <div class="stat-value success">{{ stats.success }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">失败</div>
          <div class="stat-value error">{{ stats.error }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">平均延迟</div>
          <div class="stat-value">{{ stats.avgLatency }}ms</div>
        </div>
      </div>

      <h4 style="margin-top: 20px;">端口分布（负载均衡验证）</h4>
      <div class="port-distribution">
        <div v-for="(count, port) in stats.portDistribution" :key="port" class="port-item">
          <span class="port-label">端口 {{ port }}:</span>
          <div class="port-bar-container">
            <div class="port-bar" :style="{ width: getPortBarWidth(count) }"></div>
          </div>
          <span class="port-count">{{ count }} 次</span>
        </div>
      </div>
    </div>

    <!-- 响应面板 -->
    <div class="response-panel">
      <h3>📝 最新响应</h3>
      <div v-if="loading" class="loading">
        <div class="spinner"></div>
        <span>请求中...</span>
      </div>
      <pre v-else-if="lastResponse">{{ formatJSON(lastResponse) }}</pre>
      <div v-else class="placeholder">点击按钮开始测试</div>
    </div>

    <!-- 历史记录 -->
    <div class="history-panel">
      <h3>📜 历史记录（最近 30 条）</h3>
      <div v-if="history.length === 0" class="placeholder">暂无记录</div>
      <div v-else class="history-list">
        <div v-for="(item, index) in history" :key="index" class="history-item">
          <span class="history-time">{{ item.time }}</span>
          <span class="history-method" :class="item.method.toLowerCase()">{{ item.method }}</span>
          <span class="history-endpoint">{{ item.endpoint }}</span>
          <span :class="['history-status', item.success ? 'success' : 'error']">
            {{ item.success ? '✓' : '✗' }}
          </span>
          <span v-if="item.latency" class="history-latency">{{ item.latency }}ms</span>
          <span v-if="item.port" class="history-port">端口: {{ item.port }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import axios from 'axios'

// 网关地址配置
// 与 rover-gateway.yml 默认 port: 80 对齐；改过端口就在页面上改
const gatewayUrl = ref('http://localhost')

// 创建可配置的 axios 实例
let request = axios.create({
  baseURL: gatewayUrl.value,
  timeout: 10000
})

const updateGateway = () => {
  request = axios.create({
    baseURL: gatewayUrl.value,
    timeout: 10000
  })
  showToast('success', `网关地址已切换为 ${gatewayUrl.value}，结果会显示在下方「最新响应」`)
}

// 自定义请求头
const customHeaders = reactive({
  'Authorization': '',
  'X-Request-ID': '',
  'X-Custom-Header': ''
})

// 配置参数
const delayMs = ref(2000)
const concurrentCount = ref(10)

// 状态管理
const loading = ref(false)
const lastResponse = ref(null)
const history = ref([])

// ===== toast 提示 =====
const toasts = ref([])
let toastSeq = 0

const showToast = (type, message) => {
  const id = ++toastSeq
  toasts.value.push({ id, type, message })
  // 最多同时显示 5 条
  if (toasts.value.length > 5) {
    toasts.value.shift()
  }
  setTimeout(() => {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }, 3000)
}

const stats = reactive({
  total: 0,
  success: 0,
  error: 0,
  avgLatency: 0,
  totalLatency: 0,
  portDistribution: {}
})

// 重置统计
const resetStats = () => {
  stats.total = 0
  stats.success = 0
  stats.error = 0
  stats.avgLatency = 0
  stats.totalLatency = 0
  stats.portDistribution = {}
  history.value = []
  lastResponse.value = null
}

// 更新统计
const updateStats = (success, port, latency) => {
  stats.total++
  if (success) {
    stats.success++
    if (port) {
      stats.portDistribution[port] = (stats.portDistribution[port] || 0) + 1
    }
  } else {
    stats.error++
  }
  if (latency) {
    stats.totalLatency += latency
    stats.avgLatency = Math.round(stats.totalLatency / stats.total)
  }
}

// 添加历史记录
const addHistory = (method, endpoint, success, port, latency) => {
  history.value.unshift({
    time: new Date().toLocaleTimeString(),
    method,
    endpoint,
    success,
    port,
    latency
  })
  // 只保留最近 30 条
  if (history.value.length > 30) {
    history.value.pop()
  }
}

// 获取端口分布条宽度
const getPortBarWidth = (count) => {
  if (stats.total === 0) return '0%'
  const percentage = (count / stats.total) * 100
  return `${Math.min(percentage, 100)}%`
}

// 通用请求方法（带耗时统计）
const makeRequest = async (method, endpoint, config = {}) => {
  loading.value = true
  lastResponse.value = null
  const startTime = Date.now()

  try {
    const response = await request({ method, url: endpoint, ...config })
    const latency = Date.now() - startTime
    lastResponse.value = response.data
    updateStats(true, response.data.port, latency)
    addHistory(method.toUpperCase(), endpoint, true, response.data.port, latency)
    showToast('success', `${method.toUpperCase()} ${endpoint} → ${response.status} 成功（${latency}ms）`)
  } catch (error) {
    const latency = Date.now() - startTime
    lastResponse.value = {
      error: error.message,
      status: error.response?.status,
      data: error.response?.data
    }
    updateStats(false, null, latency)
    addHistory(method.toUpperCase(), endpoint, false, null, latency)
    const status = error.response?.status
    showToast('error', `${method.toUpperCase()} ${endpoint} → ${status ? `HTTP ${status}` : error.message}（${latency}ms）`)
  } finally {
    loading.value = false
  }
}

// 基础接口测试
const testHello = () => makeRequest('get', '/api/hello')
const testEcho = () => makeRequest('get', '/api/echo?msg=test-message')
const testHealth = () => makeRequest('get', '/api/health')
const testInfo = () => makeRequest('get', '/api/info')

// 请求头测试
const testWithHeaders = async () => {
  const headers = {}
  if (customHeaders['Authorization']) {
    headers['Authorization'] = customHeaders['Authorization']
  }
  if (customHeaders['X-Request-ID']) {
    headers['X-Request-ID'] = customHeaders['X-Request-ID']
  }
  if (customHeaders['X-Custom-Header']) {
    headers['X-Custom-Header'] = customHeaders['X-Custom-Header']
  }

  await makeRequest('get', '/api/headers', { headers })
}

// POST 数据测试
const testData = async () => {
  await makeRequest('post', '/api/data', {
    data: {
      test: 'data',
      timestamp: Date.now(),
      random: Math.random()
    }
  })
}

const testBatch = async () => {
  // 生成 100 个元素的数组
  const items = Array.from({ length: 100 }, (_, i) => ({
    id: i + 1,
    name: `Item ${i + 1}`,
    value: Math.random()
  }))

  await makeRequest('post', '/api/batch', { data: items })
}

// 延迟测试
const testDelay = async () => {
  await makeRequest('get', `/api/delay?ms=${delayMs.value}`)
}

const testTimeout1s = async () => {
  // 请求需要 2s 响应，但设置 1s 超时
  const timeoutRequest = axios.create({
    baseURL: gatewayUrl.value,
    timeout: 1000
  })

  loading.value = true
  lastResponse.value = null
  const startTime = Date.now()

  try {
    const response = await timeoutRequest.get('/api/delay?ms=2000')
    const latency = Date.now() - startTime
    lastResponse.value = response.data
    updateStats(true, response.data.port, latency)
    addHistory('GET', '/api/delay?ms=2000 (timeout:1s)', true, response.data.port, latency)
    showToast('success', `GET /api/delay?ms=2000 → 200 成功（${latency}ms，未触发超时）`)
  } catch (error) {
    const latency = Date.now() - startTime
    lastResponse.value = {
      error: error.message,
      expected: 'Timeout after 1s',
      backendDelay: '2s'
    }
    updateStats(false, null, latency)
    addHistory('GET', '/api/delay?ms=2000 (timeout:1s)', false, null, latency)
    showToast('error', `GET /api/delay?ms=2000 → 超时失败（${latency}ms，符合预期）`)
  } finally {
    loading.value = false
  }
}

// 并发测试
const testConcurrent = async () => {
  loading.value = true
  lastResponse.value = {
    message: `正在发送 ${concurrentCount.value} 个并发请求...`,
    timestamp: new Date().toLocaleTimeString()
  }

  const promises = []
  const startTime = Date.now()
  let okCount = 0
  let failCount = 0

  for (let i = 0; i < concurrentCount.value; i++) {
    promises.push(
      request.get('/api/hello')
        .then(response => {
          okCount++
          updateStats(true, response.data.port, 0)
          addHistory('GET', `/api/hello (并发 ${i + 1})`, true, response.data.port, 0)
        })
        .catch(error => {
          failCount++
          updateStats(false, null, 0)
          addHistory('GET', `/api/hello (并发 ${i + 1})`, false, null, 0)
        })
    )
  }

  await Promise.all(promises)
  const totalTime = Date.now() - startTime

  lastResponse.value = {
    message: `并发测试完成`,
    concurrentCount: concurrentCount.value,
    totalTime: `${totalTime}ms`,
    timestamp: new Date().toLocaleTimeString()
  }
  if (failCount === 0) {
    showToast('success', `并发测试完成：${concurrentCount.value} 个请求全部成功，总耗时 ${totalTime}ms`)
  } else {
    showToast('error', `并发测试完成：成功 ${okCount} / 失败 ${failCount}，总耗时 ${totalTime}ms`)
  }

  loading.value = false
}

// 错误场景测试
const test404 = () => makeRequest('get', '/api/not-exist')
const testError = () => makeRequest('get', '/api/error?code=500')

// JSON 格式化
const formatJSON = (obj) => JSON.stringify(obj, null, 2)
</script>

<style scoped>
/* ===== toast 提示 ===== */
.toast-container {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 9999;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}

.toast {
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: bold;
  color: white;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  animation: toast-in 0.25s ease-out;
  max-width: 80vw;
  word-break: break-all;
}

.toast.success {
  background: #38a169;
}

.toast.error {
  background: #e53e3e;
}

@keyframes toast-in {
  from {
    opacity: 0;
    transform: translateY(-12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.container {
  background: white;
  border-radius: 16px;
  padding: 30px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  max-width: 1200px;
  margin: 0 auto;
}

.header {
  text-align: center;
  margin-bottom: 30px;
}

.header h1 {
  color: #333;
  font-size: 2.5em;
  margin-bottom: 10px;
}

.subtitle {
  color: #666;
  font-size: 1.1em;
}

.config-panel {
  background: #f8f9fa;
  padding: 15px;
  border-radius: 8px;
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.config-hint {
  flex-basis: 100%;
  margin: 4px 0 0;
  font-size: 12px;
  color: #666;
  line-height: 1.5;
}

.config-panel label {
  font-weight: bold;
  color: #333;
}

.config-panel input {
  flex: 1;
  min-width: 200px;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}

.config-panel button {
  padding: 8px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-weight: bold;
}

.config-panel button:hover {
  background: #5a67d8;
}

.btn-reset {
  background: #718096 !important;
}

.btn-reset:hover {
  background: #4a5568 !important;
}

.test-section {
  background: #f8f9fa;
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.test-section h3 {
  color: #333;
  margin-bottom: 15px;
  font-size: 1.1em;
}

.header-config,
.delay-config,
.concurrent-config {
  background: white;
  padding: 15px;
  border-radius: 8px;
  margin-bottom: 15px;
}

.header-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.header-row:last-child {
  margin-bottom: 0;
}

.header-row label {
  min-width: 150px;
  font-weight: bold;
  color: #555;
}

.header-row input,
.delay-config input,
.concurrent-config input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
}

.delay-config,
.concurrent-config {
  display: flex;
  align-items: center;
  gap: 10px;
}

.button-group {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: bold;
  transition: all 0.3s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background: #667eea;
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: #5a67d8;
}

.btn-secondary {
  background: #48bb78;
  color: white;
}

.btn-secondary:hover:not(:disabled) {
  background: #38a169;
}

.btn-success {
  background: #38b2ac;
  color: white;
}

.btn-success:hover:not(:disabled) {
  background: #319795;
}

.btn-info {
  background: #4299e1;
  color: white;
}

.btn-info:hover:not(:disabled) {
  background: #3182ce;
}

.btn-warning {
  background: #ed8936;
  color: white;
}

.btn-warning:hover:not(:disabled) {
  background: #dd6b20;
}

.btn-danger {
  background: #e53e3e;
  color: white;
}

.btn-danger:hover:not(:disabled) {
  background: #c53030;
}

.stats-panel {
  background: #f8f9fa;
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.stats-panel h3,
.stats-panel h4 {
  color: #333;
  margin-bottom: 15px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 15px;
  margin-bottom: 20px;
}

.stat-item {
  background: white;
  padding: 15px;
  border-radius: 8px;
  text-align: center;
}

.stat-label {
  font-size: 12px;
  color: #666;
  margin-bottom: 5px;
}

.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #333;
}

.stat-value.success {
  color: #38a169;
}

.stat-value.error {
  color: #e53e3e;
}

.port-distribution {
  background: white;
  padding: 15px;
  border-radius: 8px;
}

.port-item {
  display: flex;
  align-items: center;
  gap: 15px;
  padding: 10px 0;
  border-bottom: 1px solid #eee;
}

.port-item:last-child {
  border-bottom: none;
}

.port-label {
  min-width: 80px;
  font-weight: bold;
  color: #333;
}

.port-bar-container {
  flex: 1;
  height: 20px;
  background: #e2e8f0;
  border-radius: 10px;
  overflow: hidden;
}

.port-bar {
  height: 100%;
  background: linear-gradient(90deg, #667eea, #764ba2);
  transition: width 0.3s;
}

.port-count {
  min-width: 60px;
  text-align: right;
  color: #667eea;
  font-weight: bold;
}

.response-panel {
  background: #2d3748;
  color: #e2e8f0;
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 20px;
  min-height: 150px;
}

.response-panel h3 {
  color: #e2e8f0;
  margin-bottom: 15px;
}

.response-panel pre {
  margin: 0;
  white-space: pre-wrap;
  word-wrap: break-word;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.loading {
  display: flex;
  align-items: center;
  gap: 15px;
}

.spinner {
  width: 20px;
  height: 20px;
  border: 3px solid #4299e1;
  border-top: 3px solid transparent;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.placeholder {
  color: #a0aec0;
  font-style: italic;
}

.history-panel {
  background: #f8f9fa;
  padding: 20px;
  border-radius: 8px;
}

.history-panel h3 {
  color: #333;
  margin-bottom: 15px;
}

.history-list {
  max-height: 400px;
  overflow-y: auto;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: white;
  margin-bottom: 6px;
  border-radius: 4px;
  font-size: 13px;
}

.history-time {
  color: #718096;
  font-size: 11px;
  min-width: 80px;
}

.history-method {
  font-weight: bold;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  min-width: 40px;
  text-align: center;
}

.history-method.get {
  background: #bee3f8;
  color: #2b6cb0;
}

.history-method.post {
  background: #c6f6d5;
  color: #276749;
}

.history-endpoint {
  flex: 1;
  color: #2d3748;
  font-family: 'Courier New', monospace;
}

.history-status {
  font-weight: bold;
  font-size: 16px;
}

.history-status.success {
  color: #38a169;
}

.history-status.error {
  color: #e53e3e;
}

.history-latency {
  color: #718096;
  font-size: 12px;
  min-width: 50px;
  text-align: right;
}

.history-port {
  color: #667eea;
  font-weight: bold;
  min-width: 80px;
  text-align: right;
}
</style>
