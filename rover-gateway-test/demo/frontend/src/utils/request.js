import axios from 'axios'

/**
 * 备用 Axios 实例（页面主流程在 App.vue 里自建实例）。
 * 默认对齐仓库 Gateway port: 80。
 */
const GATEWAY_BASE_URL = 'http://localhost'

const request = axios.create({
  baseURL: GATEWAY_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.request.use(
  config => {
    console.log(`[Request] ${config.method.toUpperCase()} ${config.url}`)
    return config
  },
  error => {
    console.error('[Request Error]', error)
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  response => {
    console.log(`[Response] ${response.status}`, response.data)
    return response.data
  },
  error => {
    console.error('[Response Error]', error.message)
    return Promise.reject(error)
  }
)

export default request
