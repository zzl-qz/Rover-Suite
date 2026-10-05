// Run: node --test rover-admin/src/test/js/metrics.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const source = readFileSync(join(__dirname, '../../main/resources/static/js/pages/metrics.js'), 'utf8');

function page(api) {
    const context = { window: {}, document: { hidden: false }, RoverAdminApi: { api } };
    vm.runInNewContext(source, context);
    const definition = context.window.RoverAdminPages.metrics;
    const state = Object.assign(definition.data(), { page: 'metrics', routes: [], num: String });
    for (const [key, method] of Object.entries(definition.methods)) state[key] = method.bind(state);
    for (const [key, get] of Object.entries(definition.computed)) {
        Object.defineProperty(state, key, { get: get.bind(state) });
    }
    return { state, document: context.document };
}

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
}

test('missing values and empty windows never appear as measured zero or healthy', () => {
    const { state } = page();
    assert.equal(state.metricNum(undefined), '—');
    assert.equal(state.metricNum(-1), '—');
    assert.equal(state.metricNum(0), '0');
    assert.equal(state.metricLatency(0, 0), '—');
    assert.equal(state.metricLatency(0, 1), '0 ms');
    assert.equal(state.metricSampleState(0), '无流量');
    assert.equal(state.metricSampleState(3), '样本不足');
    assert.equal(state.metricSampleState(undefined), '样本未知');
    assert.equal(state.versionTrafficShare({ windowRequests: 0 }), '—');
});

test('unused configured routes remain selectable and version shares use forwarded traffic', () => {
    const { state } = page();
    state.routes = [{ id: 'idle' }, { id: 'orders' }, { businessPrefix: '/no-id' }];
    state.fullMetrics = { routes: [{ routeId: 'orders' }, { routeId: '__unmatched__' }] };
    assert.deepEqual(Array.from(state.diagnosticRouteOptions), ['idle', 'orders', '__unmatched__']);
    state.routeMetrics = {
        targets: [{ weight: 80 }, { weight: 20 }],
        rows: [{ windowRequests: 6 }, { windowRequests: 2 }],
    };
    assert.equal(state.versionConfiguredShare({ declared: true, weight: 20 }), '20.0%');
    assert.equal(state.versionConfiguredShare({ declared: false }), '—');
    assert.equal(state.versionTrafficShare({ windowRequests: 2 }), '25.0%');
});

test('leaving the page, hidden tabs and overlapping polls do not send duplicate requests', async () => {
    const response = deferred();
    const urls = [];
    const { state, document } = page(url => { urls.push(url); return response.promise; });
    state.diagnosticRouteId = 'orders / v2';
    state.page = 'routes';
    await state.refreshMetrics();
    state.page = 'metrics';
    document.hidden = true;
    await state.refreshMetrics();
    assert.equal(urls.length, 0);
    document.hidden = false;
    const pending = state.refreshMetrics();
    await state.refreshMetrics();
    assert.deepEqual(urls, ['/api/metrics', '/api/metrics/routes?routeId=orders%20%2F%20v2&range=60']);
    response.resolve({ enabled: true, rows: [] });
    await pending;
    assert.equal(state.fullMetricsLoading, false);
    assert.equal(state.routeMetricsLoading, false);
});

test('a late response cannot overwrite a newly selected route or window', async () => {
    const old = deferred();
    const current = deferred();
    const urls = [];
    const { state } = page(url => {
        urls.push(url);
        return urls.length === 1 ? old.promise : current.promise;
    });
    state.diagnosticRouteId = 'old';
    const pendingOld = state.fetchRouteMetrics();
    state.diagnosticRouteId = 'new';
    state.diagnosticRange = 300;
    state.resetRouteMetrics();
    const pendingNew = state.fetchRouteMetrics();
    assert.equal(state.routeMetrics, null);
    assert.equal(state.routeMetricsAt, 0);
    current.resolve({ routeId: 'new', windowSeconds: 300, rows: [], observedAtMillis: 123 });
    await pendingNew;
    old.resolve({ routeId: 'old', windowSeconds: 60, rows: [{ windowRequests: 999 }] });
    await pendingOld;
    assert.equal(state.routeMetrics.routeId, 'new');
    assert.equal(state.routeMetrics.windowSeconds, 300);
    assert.equal(state.routeMetricsAt, 123);
    assert.equal(state.routeMetricsLoading, false);
});

test('route entry waits for layout data before focusing the detail and respects later navigation', async () => {
    const { state, document } = page();
    const loaded = deferred();
    let scrolled = 0;
    document.getElementById = () => ({ scrollIntoView: () => { scrolled++; } });
    state.$nextTick = callback => callback();
    state.switchPage = () => loaded.promise;
    const opening = state.openMetrics('orders');
    assert.equal(scrolled, 0);
    loaded.resolve();
    await opening;
    assert.equal(scrolled, 1);

    state.switchPage = async () => { state.page = 'routes'; };
    await state.openMetrics('orders');
    assert.equal(scrolled, 1);
});

test('read failure preserves the timestamp of the last good sample; disabled is separate', async () => {
    let fail = false;
    const { state } = page(async url => {
        if (fail) return { error: '读取失败' };
        return url === '/api/metrics' ? { enabled: false } : { rows: [], observedAtMillis: 123 };
    });
    state.diagnosticRouteId = 'idle';
    await state.refreshMetrics();
    assert.equal(state.fullMetrics.enabled, false);
    assert.equal(state.fullMetricsError, null);
    assert.equal(state.diagnosticInstances.length, 0);
    assert.equal(state.routeMetricsError, null);
    const fullAt = state.fullMetricsAt;
    fail = true;
    await state.refreshMetrics();
    assert.equal(state.fullMetricsAt, fullAt);
    assert.equal(state.routeMetricsAt, 123);
    assert.equal(state.fullMetricsError, '读取失败');
    assert.equal(state.routeMetricsError, '读取失败');
});
