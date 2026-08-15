import axios from 'axios'

/**
 * Axios 实例配置
 * 注意：这里配置的是网关地址，前端请求会先到网关，再由网关代理到后端服务
 */

// 网关地址（根据实际部署情况修改）
const GATEWAY_BASE_URL = 'http://localhost:9999'

const request = axios.create({
  baseURL: GATEWAY_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器
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

// 响应拦截器
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