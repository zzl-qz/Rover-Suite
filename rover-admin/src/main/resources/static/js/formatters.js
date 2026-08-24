/** 纯格式化函数（页面方法里也有同名拷贝，保证 this.num 等可用）。 */
function num(v) {
    if (v === null || v === undefined) return '0';
    if (typeof v === 'number') {
        return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2);
    }
    return String(v);
}
function fmtTime(millis) {
    if (!millis) return '-';
    const d = new Date(millis);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
function fmtSecond(epochSecond) {
    const d = new Date(epochSecond * 1000);
    const pad = n => String(n).padStart(2, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
function fmtFullTime(millis) { return fmtTime(millis); }
function fmtUptime(seconds) {
    if (seconds === null || seconds === undefined) return '-';
    const s = Number(seconds);
    if (s < 60) return Math.floor(s) + 's';
    if (s < 3600) return Math.floor(s / 60) + 'm ' + Math.floor(s % 60) + 's';
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return (h >= 24 ? Math.floor(h / 24) + 'd ' : '') + (h % 24) + 'h ' + m + 'm';
}
function fmtHeap(jvm) {
    if (!jvm) return '-';
    return (jvm.heapUsedPercent || 0).toFixed(1) + '%';
}
function fmtCpu(jvm) {
    if (!jvm || jvm.processCpuPercent === undefined) return '-';
    return Number(jvm.processCpuPercent).toFixed(1) + '%';
}
function idleText(millis) {
    if (!millis) return '-';
    const sec = Math.max(0, Math.floor((Date.now() - millis) / 1000));
    if (sec < 5) return '刚刚';
    if (sec < 60) return sec + 's';
    return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
}
/** 纵轴好看的上限：峰值 * 1.15 再取 1/2/5×10^n。 */
function niceCeil(value) {
    if (!value || value <= 0) return 1;
    const target = value * 1.15;
    const exp = Math.floor(Math.log10(target));
    const f = Math.pow(10, exp);
    const n = target / f;
    let nice = 10;
    if (n <= 1) nice = 1;
    else if (n <= 2) nice = 2;
    else if (n <= 5) nice = 5;
    return Math.ceil(nice * f);
}

window.RoverAdminFormatters = {
    num, fmtTime, fmtSecond, fmtFullTime, fmtUptime, fmtHeap, fmtCpu, idleText, niceCeil,
};
