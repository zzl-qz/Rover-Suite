'use strict';

const assert = require('node:assert/strict');
const http = require('node:http');
const test = require('node:test');
const { RoverRegistrar } = require('./rover_registrar');

const QUIET_LOGGER = Object.freeze({ warn() {}, error() {} });

test('retry and heartbeat intervals cannot exceed eight seconds', () => {
  const common = {
    nameserverUrl: 'http://127.0.0.1:8889',
    serviceName: 'order-service',
    instanceId: 'pod-one',
    host: '127.0.0.1',
    port: 8080,
  };
  assert.throws(
    () => new RoverRegistrar({ ...common, retryIntervalMs: 8001 }),
    /retryIntervalMs must be <= 8000ms/,
  );
  assert.throws(
    () => new RoverRegistrar({ ...common, heartbeatIntervalMs: 8001 }),
    /heartbeatIntervalMs must be <= 8000ms/,
  );
});

test('close before start does not issue an unregister request', async () => {
  const registrar = new RoverRegistrar({
    nameserverUrl: 'http://127.0.0.1:1',
    serviceName: 'order-service',
    instanceId: 'pod-one',
    host: '127.0.0.1',
    port: 8080,
    requestTimeoutMs: 20,
    logger: QUIET_LOGGER,
  });

  await registrar.close();
  assert.equal(registrar.state, 'CLOSED');
});

test('a 2xx response without code=OK is retried instead of accepted', async (t) => {
  let registerCount = 0;
  const harness = await startServer(({ operation }) => {
    if (operation === 'register') {
      registerCount += 1;
      return registerCount === 1 ? reply(200, 'BROKEN_SUCCESS') : reply(200, 'OK');
    }
    return reply(200, 'OK');
  });
  t.after(() => harness.close());

  const registrar = new RoverRegistrar({
    nameserverUrl: harness.url,
    serviceName: 'order-service',
    instanceId: 'pod-one',
    host: '127.0.0.1',
    port: 8080,
    retryIntervalMs: 20,
    heartbeatIntervalMs: 20,
    requestTimeoutMs: 200,
    logger: QUIET_LOGGER,
  });

  registrar.start();
  await waitFor(() => registerCount >= 2 && registrar.state === 'REGISTERED', 500);
  await registrar.close();
});

test('fixed retry, immediate re-register, single in-flight request and close unregister', async (t) => {
  const counts = { register: 0, heartbeat: 0, unregister: 0 };
  const harness = await startServer(({ operation }) => {
    counts[operation] += 1;
    if (operation === 'register' && counts.register === 1) {
      return reply(408, 'REQUEST_TIMEOUT');
    }
    if (operation === 'heartbeat' && counts.heartbeat === 1) {
      return reply(404, 'INSTANCE_NOT_FOUND');
    }
    return reply(200, 'OK');
  });
  t.after(() => harness.close());

  const registrar = new RoverRegistrar({
    nameserverUrl: harness.url,
    token: 'test-token',
    serviceName: 'order-service',
    instanceId: 'pod-one',
    host: '127.0.0.1',
    port: 8080,
    retryIntervalMs: 30,
    heartbeatIntervalMs: 30,
    requestTimeoutMs: 200,
    logger: QUIET_LOGGER,
  });

  registrar.start();
  await waitFor(() => counts.register >= 3 && counts.heartbeat >= 2, 1500);

  const firstRegister = harness.requests.find((item) => item.operation === 'register');
  const secondRegister = harness.requests.filter((item) => item.operation === 'register')[1];
  assert.ok(secondRegister.at - firstRegister.at >= 20, 'failed register should use fixed-delay retry');
  assert.equal(harness.maxActive, 1);
  assert.ok(harness.requests.every((item) => item.authorization === 'Bearer test-token'));
  assert.equal(new Set(harness.requests.map((item) => item.body.sessionId)).size, 1);
  assert.equal(registrar.state, 'REGISTERED');

  await registrar.close();
  assert.equal(counts.unregister, 1);
  assert.equal(registrar.state, 'CLOSED');
});

test('generic heartbeat 404 is permanent and does not trigger re-register', async (t) => {
  let registerCount = 0;
  let heartbeatCount = 0;
  let unregisterCount = 0;
  const harness = await startServer(({ operation }) => {
    if (operation === 'register') {
      registerCount += 1;
      return reply(200, 'OK');
    }
    if (operation === 'heartbeat') {
      heartbeatCount += 1;
      return reply(404, 'NOT_FOUND');
    }
    if (operation === 'unregister') {
      unregisterCount += 1;
    }
    return reply(200, 'OK');
  });
  t.after(() => harness.close());

  const registrar = new RoverRegistrar({
    nameserverUrl: harness.url,
    serviceName: 'order-service',
    instanceId: 'pod-one',
    host: '127.0.0.1',
    port: 8080,
    retryIntervalMs: 20,
    heartbeatIntervalMs: 20,
    requestTimeoutMs: 200,
    logger: QUIET_LOGGER,
  });

  registrar.start();
  await waitFor(() => registrar.state === 'FAILED', 500);
  await delay(80);
  assert.equal(registerCount, 1);
  assert.equal(heartbeatCount, 1);

  await registrar.close();
  assert.equal(unregisterCount, 1);
  assert.equal(registrar.state, 'CLOSED');
});

test('register 404 is permanent even when its code is INSTANCE_NOT_FOUND', async (t) => {
  let registerCount = 0;
  const harness = await startServer(({ operation }) => {
    if (operation === 'register') {
      registerCount += 1;
      return reply(404, 'INSTANCE_NOT_FOUND');
    }
    return reply(200, 'OK');
  });
  t.after(() => harness.close());

  const registrar = new RoverRegistrar({
    nameserverUrl: harness.url,
    serviceName: 'order-service',
    instanceId: 'pod-one',
    host: '127.0.0.1',
    port: 8080,
    retryIntervalMs: 20,
    heartbeatIntervalMs: 20,
    requestTimeoutMs: 200,
    logger: QUIET_LOGGER,
  });

  registrar.start();
  await waitFor(() => registrar.state === 'FAILED', 500);
  await delay(60);
  assert.equal(registerCount, 1);
  await registrar.close();
});

async function startServer(responder) {
  const requests = [];
  let active = 0;
  let maxActive = 0;
  const server = http.createServer(async (request, response) => {
    active += 1;
    maxActive = Math.max(maxActive, active);
    try {
      const body = await readJson(request);
      const operation = request.url.split('/').pop();
      const entry = {
        operation,
        body,
        authorization: request.headers.authorization || '',
        at: Date.now(),
      };
      requests.push(entry);
      // 让并发错误更容易在 maxActive 中暴露。
      await delay(5);
      const result = responder(entry);
      response.writeHead(result.status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ code: result.code, message: result.code, epoch: 'test-epoch' }));
    } finally {
      active -= 1;
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  return {
    url: `http://127.0.0.1:${address.port}`,
    requests,
    get maxActive() { return maxActive; },
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (error) {
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

function reply(status, code) {
  return { status, code };
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error('condition was not met before timeout');
    }
    await delay(5);
  }
}
