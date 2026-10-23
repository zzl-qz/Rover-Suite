// Run: node --test rover-admin/src/test/js/workbench-operations.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

const source = readFileSync(join(__dirname, '../../main/resources/static/js/pages/workbench.js'), 'utf8');
function workbench(api) {
    const context = { window: {}, document: { hidden: false }, RoverAdminApi: { api } };
    vm.runInNewContext(source, context);
    const page = context.window.RoverAdminPages.workbench;
    const state = page.data();
    for (const [key, method] of Object.entries(page.methods)) state[key] = method.bind(state);
    return state;
}

test('non-manager reads only own permissions and never probes operator endpoints', async () => {
    const urls = [];
    const state = workbench(async url => { urls.push(url); return { principal: 'viewer', permissions: ['READ'] }; });
    await state.wbLoadOperatorState(true);
    assert.deepEqual(urls, ['/api/agent/security/me']);
    assert.equal(state.wbOperationsStatus, null);
    assert.equal(state.wbReconciliationQueue.length, 0);
});

test('manager sees system status and uncertain action reconciliation queue', async () => {
    const urls = [];
    const state = workbench(async url => {
        urls.push(url);
        if (url.endsWith('/me')) return { permissions: ['READ', 'MANAGE_SYSTEM'] };
        if (url === '/api/agent/operations') return { healthy: true, writesStopped: true, uncertainActions: 1 };
        return [{ actionId: 'a1', applyOperationId: 'op-original' }];
    });
    await state.wbLoadOperatorState(true);
    assert.equal(state.wbCanManageSystem(), true);
    assert.equal(state.wbOperationsStatus.writesStopped, true);
    assert.equal(state.wbReconciliationQueue[0].applyOperationId, 'op-original');
    assert.deepEqual(Array.from(urls), ['/api/agent/security/me', '/api/agent/operations', '/api/agent/actions/reconciliation?limit=100']);
});

test('operator reconcile sends no body and refreshes authoritative state', async () => {
    const calls = [];
    const state = workbench(async (url, options = {}) => {
        calls.push({ url, options });
        if (url.endsWith('/me')) return { permissions: ['MANAGE_SYSTEM'] };
        if (url === '/api/agent/operations') return { healthy: true, writesStopped: false };
        if (url.includes('/reconciliation')) return [];
        return {};
    });
    state.wbOperatorPermissions = ['MANAGE_SYSTEM'];
    state.wbActiveSessionId = '';
    await state.wbReconcileAction({ actionId: 'a1', sessionId: 'other-session' });
    const request = calls.find(call => call.url.endsWith('/a1/reconcile'));
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.body, undefined);
    assert.match(state.wbOperationMessage, /没有重发变更请求/);
});

test('emergency stop sends the one-way endpoint request and refreshes state', async () => {
    const calls = [];
    const state = workbench(async (url, options = {}) => {
        calls.push({ url, options });
        if (url.endsWith('/me')) return { permissions: ['MANAGE_SYSTEM'] };
        if (url === '/api/agent/operations') return { healthy: true, writesStopped: true };
        if (url.includes('/reconciliation')) return [];
        return {};
    });
    state.wbOperatorPermissions = ['MANAGE_SYSTEM'];
    await state.wbToggleWriteStop(true);
    const request = calls.find(call => call.url.endsWith('/write-stop'));
    assert.equal(request.options.method, 'POST');
    assert.equal(request.options.body, undefined);
    assert.equal(state.wbOperationsStatus.writesStopped, true);
});

test('resume approval waits for authoritative two-person state', async () => {
    const calls = [];
    let status = { healthy: true, writesStopped: true, canApproveWriteResume: true, writeResume: { stopped: true, approvals: 1, requiredApprovals: 2 } };
    const state = workbench(async (url, options = {}) => {
        calls.push({ url, options });
        if (url.endsWith('/me')) return { permissions: ['MANAGE_SYSTEM'] };
        if (url === '/api/agent/operations') return status;
        if (url.includes('/reconciliation')) return [];
        if (url.endsWith('/resume-approval')) {
            assert.equal(options.method, 'POST');
            status = { ...status, writesStopped: false, writeResume: { stopped: false, approvals: 2, requiredApprovals: 2 } };
        }
        return {};
    });
    state.wbOperatorPermissions = ['MANAGE_SYSTEM'];
    state.wbOperationsStatus = status;
    await state.wbApproveWriteResume();
    assert.equal(calls.filter(call => call.url.endsWith('/resume-approval')).length, 1);
    assert.equal(state.wbOperationsStatus.writesStopped, false);
    assert.match(state.wbOperationMessage, /双人审批已完成/);
});

test('system status failure is visible even while the reconciliation list stays available', async () => {
    const state = workbench(async url => {
        if (url.endsWith('/me')) return { permissions: ['MANAGE_SYSTEM'] };
        if (url === '/api/agent/operations') {
            const error = new Error('storage unavailable');
            error.status = 503;
            error.body = { healthy: false, writesStopped: true };
            throw error;
        }
        return [{ actionId: 'a1' }];
    });
    await state.wbLoadOperatorState(true);
    assert.equal(state.wbOperationsStatus.healthy, false);
    assert.equal(state.wbReconciliationQueue.length, 1);
    assert.match(state.wbOperationMessage, /存储异常/);
});
