// Run: node --test rover-admin/src/test/js/admin-list-pages.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

function page(name, api) {
    const file = join(__dirname, '../../main/resources/static/js/pages/' + name + '.js');
    const context = { window: {}, RoverAdminApi: { api }, URLSearchParams };
    vm.runInNewContext(readFileSync(file, 'utf8'), context);
    const definition = context.window.RoverAdminPages[name];
    const state = definition.data();
    for (const [key, method] of Object.entries(definition.methods)) state[key] = method.bind(state);
    for (const [key, get] of Object.entries(definition.computed || {})) {
        Object.defineProperty(state, key, { get: get.bind(state) });
    }
    return state;
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
    return { promise, resolve, reject };
}

test('trace query ignores late results and retains the last successful rows on failure', async () => {
    const old = deferred();
    const current = deferred();
    const calls = [];
    let fail = false;
    const state = page('traces', url => {
        calls.push(url);
        if (fail) return Promise.reject(new Error('offline'));
        return calls.length === 1 ? old.promise : current.promise;
    });
    state.traceFilter.traceId = 'old';
    const oldRequest = state.fetchTraces();
    state.traceFilter.traceId = 'current';
    const currentRequest = state.fetchTraces(true);
    current.resolve({ traces: [{ traceId: 'current' }] });
    await currentRequest;
    old.resolve({ traces: [{ traceId: 'old' }] });
    await oldRequest;
    assert.deepEqual(calls, ['/api/traces?traceId=old', '/api/traces?traceId=current']);
    assert.equal(state.traces[0].traceId, 'current');
    fail = true;
    await state.fetchTraces();
    assert.equal(state.traces[0].traceId, 'current');
    assert.equal(state.tracesError, 'offline');
    assert.equal(state.tracesLoading, false);
});

test('event and instance polls do not overlap and keep their last successful data on failure', async () => {
    for (const [name, initial, response] of [
        ['events', 'events', [{ type: 'REGISTER', serviceName: 'orders' }]],
        ['instances', 'instances', [{ instanceId: 'node-1' }]],
    ]) {
        const pending = deferred();
        let calls = 0;
        let fail = false;
        const state = page(name, () => {
            calls++;
            if (fail) return Promise.reject(new Error('offline'));
            return pending.promise;
        });
        const load = name === 'events' ? 'fetchEvents' : 'fetchInstances';
        const first = state[load]();
        const duplicate = state[load]();
        assert.equal(calls, 1);
        await duplicate;
        pending.resolve(response);
        await first;
        assert.equal(state[initial].length, 1);
        fail = true;
        await state[load]();
        assert.equal(state[initial].length, 1);
        assert.equal(state[name === 'events' ? 'eventsError' : 'instancesError'], 'offline');
    }
});

test('config refresh keeps dirty values and ignores an older concurrent snapshot', async () => {
    const old = deferred();
    const current = deferred();
    let calls = 0;
    const state = page('configs', () => (++calls === 1 ? old.promise : current.promise));
    state.configs = [{ component: 'gateway', key: 'timeout', value: '300', _saved: '300' }];
    state.configs[0].value = '450';

    const oldRequest = state.fetchConfigs();
    const currentRequest = state.fetchConfigs();
    current.resolve([{ component: 'gateway', key: 'timeout', value: '600' }]);
    await currentRequest;
    old.resolve([{ component: 'gateway', key: 'timeout', value: '300' }]);
    await oldRequest;

    assert.equal(state.configs[0].value, '450');
    assert.equal(state.configs[0]._saved, '600');
});

test('model status refresh preserves dirty form values and ignores older responses', async () => {
    const old = deferred();
    const current = deferred();
    let calls = 0;
    const state = page('model', () => (++calls === 1 ? old.promise : current.promise));
    state.modelConfig = {
        enabled: false, vendor: 'custom', timeoutSeconds: 30, baseUrl: 'http://old.test/v1',
        model: 'old-model', fastModel: '', configured: true,
    };
    state.modelVendor = 'custom';
    state.modelForm = {
        enabled: false, apiKey: '', timeoutSeconds: 30, customBaseUrl: 'http://edited.test/v1',
        customModel: 'edited-model', customFastModel: '',
    };

    const oldRequest = state.fetchModelConfig();
    const currentRequest = state.fetchModelConfig();
    current.resolve({ ...state.modelConfig, baseUrl: 'http://server.test/v1', model: 'server-model' });
    await currentRequest;
    old.resolve({ ...state.modelConfig, baseUrl: 'http://stale.test/v1', model: 'stale-model' });
    await oldRequest;

    assert.equal(state.modelForm.customBaseUrl, 'http://edited.test/v1');
    assert.equal(state.modelForm.customModel, 'edited-model');
    assert.equal(state.modelConfig.baseUrl, 'http://server.test/v1');
});
