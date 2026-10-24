// Run: node --test rover-admin/src/test/js/route-isolation.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

function page(api, globals = {}) {
    const context = { window: {}, URLSearchParams, ...globals, RoverAdminApi: { api } };
    vm.runInNewContext(readFileSync(join(__dirname, '../../main/resources/static/js/pages/routes.js'), 'utf8'), context);
    const definition = context.window.RoverAdminPages.routes;
    const state = { ...definition.data(), auth: { authenticated: true, permissions: ['MANAGE_SYSTEM'], writesStopped: false },
        discoveryType: 'NAMESERVER', routes: [] };
    for (const [key, method] of Object.entries(definition.methods)) state[key] = method.bind(state);
    for (const [key, get] of Object.entries(definition.computed || {})) {
        Object.defineProperty(state, key, { get: get.bind(state) });
    }
    state.toast = (type, message) => (state.lastToast = { type, message });
    return state;
}

test('route isolation control requires current system-management permission and an enabled write path', () => {
    const state = page(async () => ({}));
    assert.equal(state.canManageInstanceIsolation, true);
    state.auth.permissions = ['APPROVE'];
    assert.equal(state.canManageInstanceIsolation, false);
    state.auth.permissions = ['MANAGE_SYSTEM'];
    state.auth.writesStopped = true;
    assert.equal(state.canManageInstanceIsolation, false);
    assert.equal(state.routeSupportsInstanceIsolation({ targets: [{ serviceName: 'orders' }] }), true);
    state.discoveryType = 'STATIC';
    assert.equal(state.routeSupportsInstanceIsolation({ targets: [{ serviceName: 'orders' }] }), false);
});

test('isolation request uses fresh discovery and isolation snapshots and refuses a breached capacity floor', async () => {
    const calls = [];
    const state = page(async (url, options) => {
        calls.push({ url, options });
        if (url === '/api/instances') return [
            { serviceName: 'orders', group: 'blue', instanceId: 'orders-1', host: '10.0.0.1', port: 8080, healthy: true },
            { serviceName: 'orders', group: 'green', instanceId: 'orders-2', host: '10.0.0.2', port: 8080, healthy: true },
        ];
        if (url === '/api/routes/instances/isolations') return { isolations: [{
            routeUid: 'uid', generation: 2, routeId: 'orders', serviceName: 'orders', group: 'green',
            instanceId: 'orders-2', host: '10.0.0.2', port: 8080, state: 'ISOLATED', lifecycle: 'ISOLATED', version: 1,
        }] };
        throw new Error('unexpected request ' + url);
    }, { prompt: () => '1' });
    state.routes = [{ id: 'orders', targets: [{ serviceName: 'orders', group: '' }] }];
    state.instanceIsolations = [{ routeUid: 'uid', generation: 2, routeId: 'orders', serviceName: 'orders', group: 'green',
        instanceId: 'orders-2', host: '10.0.0.2', port: 8080, state: 'ISOLATED', lifecycle: 'ISOLATED' }];
    await state.changeInstanceIsolation({ routeUid: 'uid', generation: 2, routeId: 'orders', serviceName: 'orders',
        group: 'blue', instanceId: 'orders-1', host: '10.0.0.1', port: 8080, version: 0 }, 'ISOLATE');
    assert.equal(calls.some(call => call.options && call.options.method === 'POST'), false);
    assert.equal(state.lastToast.type, 'error');
    assert.match(state.lastToast.message, /剩余健康实例 0/);
});

test('restore requires an explicit operator check and submits the matching isolation version', async () => {
    const calls = [];
    const prompts = ['1'];
    const state = page(async (url, options) => {
        calls.push({ url, options });
        if (url === '/api/instances') return [
            { serviceName: 'orders', group: 'blue', instanceId: 'orders-1', host: '10.0.0.1', port: 8080, healthy: false },
            { serviceName: 'orders', group: 'blue', instanceId: 'orders-2', host: '10.0.0.2', port: 8080, healthy: true },
        ];
        if (url === '/api/routes/instances/isolations') return { isolations: [{ ...row, state: 'ISOLATED', lifecycle: 'EXPIRED_PENDING_REVIEW' }] };
        return { status: 'RESTORED' };
    }, { prompt: () => prompts.shift(), confirm: () => true });
    const row = { routeUid: 'uid', generation: 2, routeId: 'orders', serviceName: 'orders', group: 'blue',
        instanceId: 'orders-1', host: '10.0.0.1', port: 8080, source: 'MANUAL', version: 4 };
    state.instanceIsolations = [{ ...row, state: 'ISOLATED', lifecycle: 'EXPIRED_PENDING_REVIEW' }];
    state.routes = [{ id: 'orders', targets: [{ serviceName: 'orders', group: 'blue' }] }];
    await state.restoreInstanceIsolation(state.routes[0], row);
    const post = calls.find(call => call.options && call.options.method === 'POST');
    assert.ok(post);
    const body = JSON.parse(post.options.body);
    assert.equal(body.operation, 'RESTORE');
    assert.equal(body.expectedVersion, 4);
    assert.equal(body.minimumHealthyAfter, 1);
    assert.match(body.capacityEvidence, /健康实例数=1/);
    assert.equal(body.requestedBy, undefined);
});

test('admin write stop blocks isolation before discovery or mutation requests', async () => {
    const calls = [];
    const state = page(async (url, options) => { calls.push({ url, options }); return {}; });
    state.auth.writesStopped = true;
    await state.openInstanceIsolation({ id: 'orders', resourceUid: 'uid', generation: 2, targets: [{ serviceName: 'orders' }] });
    assert.deepEqual(calls, []);
    assert.match(state.lastToast.message, /沒有實例隔離管理權限|没有实例隔离管理权限/);
});
