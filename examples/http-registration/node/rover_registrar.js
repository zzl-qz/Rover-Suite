'use strict';

const http = require('node:http');
const https = require('node:https');
const { randomUUID } = require('node:crypto');

const PATHS = Object.freeze({
  register: '/v1/client/instances/register',
  heartbeat: '/v1/client/instances/heartbeat',
  unregister: '/v1/client/instances/unregister',
});

const PERMANENT_HTTP_STATUSES = new Set([400, 401, 403, 409, 413]);

/**
 * Rover HTTP Registration API 的零依赖参考实现。
 *
 * 这不是完整服务发现 SDK：只负责注册、心跳和尽力注销。请在业务端口真正
 * listen/ready 后调用 start()，并在关闭业务监听端口前 await close()。
 */
class RoverRegistrar {
  constructor(config) {
    this.config = validateConfig(config);
    this.sessionId = randomUUID();
    this.state = 'IDLE';
    this.lastError = null;

    this._timer = null;
    this._inFlight = null;
    this._closing = false;
    this._closePromise = null;
  }

  /** 非阻塞启动；第一次完整注册会立即发出。 */
  start() {
    if (this.state !== 'IDLE') {
      return this;
    }
    this.state = 'REGISTERING';
    this._startCycle('register');
    return this;
  }

  /**
   * 停止调度，等待当前请求结束，然后最多发送一次注销。注销失败不阻止关闭。
   */
  close() {
    if (this._closePromise) {
      return this._closePromise;
    }
    const started = this.state !== 'IDLE';
    this._closing = true;
    this.state = 'STOPPING';
    this._clearTimer();
    this._closePromise = (async () => {
      const active = this._inFlight;
      if (active) {
        try {
          await active;
        } catch (_) {
          // 周期方法会自行记录错误；关闭路径继续执行尽力注销。
        }
      }
      try {
        if (started) {
          await this._request('unregister');
        }
      } catch (error) {
        this._log('warn', `Rover unregister failed during close: ${error.message}`);
      } finally {
        this.state = 'CLOSED';
      }
    })();
    return this._closePromise;
  }

  _startCycle(operation) {
    if (this._closing || this.state === 'FAILED' || this._inFlight) {
      return;
    }
    const cycle = this._runCycle(operation);
    this._inFlight = cycle;
    const clear = () => {
      if (this._inFlight === cycle) {
        this._inFlight = null;
      }
    };
    cycle.then(clear, clear);
  }

  async _runCycle(operation) {
    let outcome;
    try {
      outcome = await this._request(operation);
    } catch (error) {
      outcome = { action: 'RETRY', error };
    }

    if (this._closing) {
      return;
    }

    if (outcome.action === 'PERMANENT_FAILURE') {
      this.lastError = outcome.error;
      this.state = 'FAILED';
      this._log('error', `Rover registrar stopped: ${outcome.error.message}`);
      return;
    }

    if (operation === 'register') {
      if (outcome.action === 'SUCCESS') {
        this.lastError = null;
        this.state = 'REGISTERED';
        this._schedule('heartbeat', this.config.heartbeatIntervalMs);
      } else {
        this.lastError = outcome.error;
        this.state = 'REGISTERING';
        this._log('warn', `Rover register failed; retrying in ${this.config.retryIntervalMs}ms: ${outcome.error.message}`);
        this._schedule('register', this.config.retryIntervalMs);
      }
      return;
    }

    if (outcome.action === 'SUCCESS') {
      this.lastError = null;
      this.state = 'REGISTERED';
      this._schedule('heartbeat', this.config.heartbeatIntervalMs);
    } else if (outcome.action === 'REGISTER_NOW') {
      this.state = 'REGISTERING';
      // 0ms timer 在当前 cycle 清理 in-flight 标记后执行，不额外等待 5 秒。
      this._schedule('register', 0);
    } else {
      this.lastError = outcome.error;
      this.state = 'REGISTERED';
      this._log('warn', `Rover heartbeat failed; retrying in ${this.config.retryIntervalMs}ms: ${outcome.error.message}`);
      this._schedule('heartbeat', this.config.retryIntervalMs);
    }
  }

  _schedule(operation, delayMs) {
    this._clearTimer();
    if (this._closing || this.state === 'FAILED') {
      return;
    }
    // fixed-delay：只有上一请求完成后，才从这里开始计算下一次等待。
    this._timer = setTimeout(() => {
      this._timer = null;
      this._startCycle(operation);
    }, delayMs);
  }

  _clearTimer() {
    if (this._timer) {
      clearTimeout(this._timer);
      this._timer = null;
    }
  }

  async _request(operation) {
    const payload = operation === 'register'
      ? {
          serviceName: this.config.serviceName,
          instanceId: this.config.instanceId,
          sessionId: this.sessionId,
          host: this.config.host,
          port: this.config.port,
          weight: this.config.weight,
          group: this.config.group,
          zone: this.config.zone,
          metadata: this.config.metadata,
        }
      : {
          serviceName: this.config.serviceName,
          instanceId: this.config.instanceId,
          sessionId: this.sessionId,
        };

    const response = await postJson(
      new URL(PATHS[operation], this.config.nameserverUrl),
      payload,
      this.config.token,
      this.config.requestTimeoutMs,
    );

    if (response.parseError && response.statusCode >= 200 && response.statusCode < 300) {
      throw response.parseError;
    }
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (!response.body || response.body.code !== 'OK') {
        throw new Error('Nameserver success response must contain code=OK');
      }
      return { action: 'SUCCESS', body: response.body };
    }
    const code = response.body && response.body.code
      ? String(response.body.code)
      : `HTTP_${response.statusCode}`;
    const message = response.body && response.body.message ? response.body.message : code;
    const error = new Error(`${code}: ${message}`);
    error.statusCode = response.statusCode;
    error.code = code;

    if (operation === 'heartbeat'
        && response.statusCode === 404
        && code === 'INSTANCE_NOT_FOUND') {
      return { action: 'REGISTER_NOW', error };
    }
    if (response.statusCode === 408 || response.statusCode === 429
        || response.statusCode >= 500) {
      return { action: 'RETRY', error };
    }
    if (PERMANENT_HTTP_STATUSES.has(response.statusCode)
        || (response.statusCode >= 400 && response.statusCode < 500)) {
      return { action: 'PERMANENT_FAILURE', error };
    }
    return { action: 'RETRY', error };
  }

  _log(level, message) {
    const logger = this.config.logger;
    if (logger && typeof logger[level] === 'function') {
      logger[level](message);
    }
  }
}

function postJson(url, payload, token, timeoutMs) {
  const content = Buffer.from(JSON.stringify(payload), 'utf8');
  const transport = url.protocol === 'https:' ? https : http;
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return Promise.reject(new Error(`Unsupported Nameserver protocol: ${url.protocol}`));
  }

  return new Promise((resolve, reject) => {
    let settled = false;
    let request;
    let timer = null;
    const succeed = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    };
    const fail = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    };
    const headers = {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Content-Length': content.length,
    };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    try {
      request = transport.request(url, { method: 'POST', headers }, (response) => {
        const chunks = [];
        let received = 0;
        response.on('data', (chunk) => {
          received += chunk.length;
          if (received > 64 * 1024) {
            response.destroy(new Error('Nameserver response exceeds 65536 bytes'));
            return;
          }
          chunks.push(chunk);
        });
        response.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let body = null;
          let parseError = null;
          if (text) {
            try {
              body = JSON.parse(text);
            } catch (error) {
              parseError = new Error(`Nameserver returned invalid JSON: ${error.message}`);
            }
          }
          succeed({ statusCode: response.statusCode || 0, body, parseError });
        });
        response.on('error', fail);
      });
    } catch (error) {
      fail(error);
      return;
    }
    request.on('error', fail);
    timer = setTimeout(() => {
      const error = new Error(`Nameserver request timed out after ${timeoutMs}ms`);
      error.code = 'ETIMEDOUT';
      request.destroy(error);
    }, timeoutMs);
    try {
      request.end(content);
    } catch (error) {
      fail(error);
    }
  });
}

function validateConfig(source) {
  if (!source || typeof source !== 'object') {
    throw new TypeError('Registrar config is required');
  }
  const config = {
    nameserverUrl: requiredString(source.nameserverUrl, 'nameserverUrl'),
    token: source.token == null ? '' : String(source.token),
    serviceName: requiredString(source.serviceName, 'serviceName'),
    instanceId: requiredString(source.instanceId, 'instanceId'),
    host: requiredString(source.host, 'host'),
    port: positiveInteger(source.port, 'port'),
    weight: source.weight == null ? 100 : positiveInteger(source.weight, 'weight'),
    group: source.group == null ? null : String(source.group),
    zone: source.zone == null ? null : String(source.zone),
    metadata: source.metadata == null ? {} : { ...source.metadata },
    retryIntervalMs: source.retryIntervalMs == null ? 5000 : boundedInterval(source.retryIntervalMs, 'retryIntervalMs'),
    heartbeatIntervalMs: source.heartbeatIntervalMs == null ? 5000 : boundedInterval(source.heartbeatIntervalMs, 'heartbeatIntervalMs'),
    requestTimeoutMs: source.requestTimeoutMs == null ? 3000 : positiveInteger(source.requestTimeoutMs, 'requestTimeoutMs'),
    logger: source.logger === undefined ? console : source.logger,
  };
  // 构造时解析一次，避免启动后台任务后才暴露 URL 配置错误。
  const parsed = new URL(config.nameserverUrl);
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new TypeError('nameserverUrl must use http or https');
  }
  if (config.port > 65535) {
    throw new RangeError('port must be <= 65535');
  }
  return Object.freeze(config);
}

function requiredString(value, name) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new TypeError(`${name} must be a non-empty string`);
  }
  return value.trim();
}

function positiveInteger(value, name) {
  if (!Number.isInteger(value) || value <= 0) {
    throw new TypeError(`${name} must be a positive integer`);
  }
  return value;
}

function boundedInterval(value, name) {
  const interval = positiveInteger(value, name);
  if (interval > 8000) {
    throw new RangeError(`${name} must be <= 8000ms`);
  }
  return interval;
}

module.exports = { RoverRegistrar };
