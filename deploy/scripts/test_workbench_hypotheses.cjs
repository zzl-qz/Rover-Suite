/* Run from the repository root: node deploy/scripts/test_workbench_hypotheses.cjs */
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const sandbox = { window: {}, console };
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('rover-admin/src/main/resources/static/vendor/vue.global.prod.js', 'utf8'), sandbox);
vm.runInContext(fs.readFileSync('rover-admin/src/main/resources/static/js/pages/workbench.js', 'utf8'), sandbox);
const methods = sandbox.window.RoverAdminPages.workbench.methods;
const candidate = { id: 'H7', statement: '<script>untrusted</script>', status: 'UNKNOWN', detail: '仅为关联',
    sources: ['/api/agent/timeline'], supportingEvidenceIds: ['e1'], counterEvidenceIds: ['e2'],
    missingEvidence: ['应用日志'], nextCapabilities: ['LOG_QUERY'] };
const task = { plan: { assessments: [candidate], progress: { stopReason: '无法确认' } } };
assert.equal(methods.wbHypotheses(task)[0], candidate);
assert.equal(methods.wbHypotheses({ result: { hypotheses: [] }, ...task }).length, 0);
assert.equal(methods.wbHypotheses(null).length, 0);
const html = fs.readFileSync('rover-admin/src/main/resources/static/index.html', 'utf8');
const match = html.match(/<h4>调查过程<\/h4>([\s\S]*?)<h4>证据<\/h4>/);
assert(match);
assert(!match[1].includes('v-html'));
sandbox.template = '<div>' + match[1] + '</div>';
sandbox.fixture = { ...methods, turn: { task } };
// The extracted template contains raw JS && but no HTML entities; avoid the browser DOM entity decoder in Node.
assert(!/&(?:#\d+|#x[0-9a-f]+|[a-z]+);/i.test(sandbox.template));
const vnode = vm.runInContext('Vue.compile(template, { decodeEntities: value => value })(fixture, [])', sandbox);
const values = [];
function visit(node) {
    if (typeof node === 'string') values.push(node);
    else if (Array.isArray(node)) node.forEach(visit);
    else if (node && node.children) visit(node.children);
}
visit(vnode);
const text = values.join(' ');
for (const value of ['支持证据', '反证', '缺失证据', '下一步检查', '调查停止原因', 'e1', 'e2', '应用日志', 'LOG_QUERY']) assert(text.includes(value), value);
assert(text.includes(candidate.statement), 'Untrusted statement remains text, never compiled as HTML');
console.log('Workbench structured hypotheses and stop reason compiled and rendered with bundled Vue');
