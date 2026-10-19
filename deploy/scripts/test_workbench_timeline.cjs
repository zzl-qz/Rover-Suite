/* Run from the repository root: node deploy/scripts/test_workbench_timeline.cjs */
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const sandbox = { window: {}, console };
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('rover-admin/src/main/resources/static/vendor/vue.global.prod.js', 'utf8'), sandbox);
vm.runInContext(fs.readFileSync('rover-admin/src/main/resources/static/js/pages/workbench.js', 'utf8'), sandbox);
const methods = sandbox.window.RoverAdminPages.workbench.methods;
const change = {
    eventId: 'event-1', sourceId: 'release:fixture-ci', kind: 'RELEASE_SUCCEEDED',
    occurredAtMillis: 1792400000000, observedAtMillis: 1792400001000,
    subject: { services: ['orders'], group: 'v2' },
    facts: { releaseId: 'deployment-1', artifact: 'registry/orders@sha256:fixture', status: 'SUCCEEDED' },
    limitations: ['源时钟未校准；不能单独确认根因'],
};
const item = { envelope: { sourceId: 'rover.change-timeline', facts: { events: [change] } } };
assert.equal(methods.wbTimelineEvents(item).length, 1);
assert.equal(methods.wbTimelineEvents(null).length, 0);
assert.match(methods.wbTimelineTime(change), /Z.*源时钟未校准/);
assert.match(methods.wbTimelineDescription(change), /deployment-1.*registry\/orders@sha256:fixture/);
const polled = { ...change, occurredAtMillis: null, occurrenceRange: { fromMillis: 1792400000000, toMillis: 1792400001000 } };
assert.match(methods.wbTimelineTime(polled), /观测区间.*实际变更时刻未知/);
const html = fs.readFileSync('rover-admin/src/main/resources/static/index.html', 'utf8');
const templates = [...html.matchAll(/<details v-if="wbTimelineEvents\(item\)\.length"[\s\S]*?<\/details>/g)];
assert.equal(templates.length, 2, 'Live and historical evidence must both show the timeline');
for (const [template] of templates) {
    assert(!template.includes('v-html'), 'External event text must use escaped interpolation');
    sandbox.template = template;
    sandbox.fixture = { ...methods, item };
    const vnode = vm.runInContext('Vue.compile(template)(fixture, [])', sandbox);
    assert.equal(vnode.type, 'details', 'The real bundled Vue compiler must render the timeline');
}
console.log('Workbench timeline helpers and both Vue templates passed');
