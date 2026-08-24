/**
 * Rover Admin：Vue 3 + ECharts。
 * 仪表盘可视时 1 秒拉 /api/live，15 秒拉 overview；非仪表盘/后台标签页不拉 live。
 * 轮询绝不打整页 loading。
 */
const { createApp } = Vue;

const ICONS = {
    dashboard: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>',
    routes: '<svg viewBox="0 0 24 24"><circle cx="6" cy="19" r="2"/><circle cx="18" cy="5" r="2"/><path d="M8 19h6.5a3.5 3.5 0 0 0 3.5-3.5V7"/></svg>',
    instances: '<svg viewBox="0 0 24 24"><rect x="2" y="3" width="20" height="8" rx="2"/><rect x="2" y="13" width="20" height="8" rx="2"/><path d="M6 7h.01M6 17h.01"/></svg>',
    traces: '<svg viewBox="0 0 24 24"><path d="M3 12h4l2-7 4 14 2-7h6"/></svg>',
    events: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>',
    configs: '<svg viewBox="0 0 24 24"><path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/></svg>',
};

async function api(url, options) {
    const res = await fetch(url, options);
    let json = null;
    try { json = await res.json(); } catch (e) { /* 非 JSON */ }
    if (!res.ok) {
        throw new Error((json && json.message) ? json.message : ('HTTP ' + res.status));
    }
    return json;
}

const EMPTY_TRAFFIC = {
    instantQps: 0, currentSecondRequests: 0, qps5s: 0, qps60s: 0, qps300s: 0,
    instantAvgMillis: 0, windowRequests: 0, avgMillis: 0, p95Millis: 0, p99Millis: 0,
    maxMillis: 0, errorRequests: 0, idle: true,
    status: { '2xx': 0, '3xx': 0, '4xx': 0, '5xx': 0 },
};
const EMPTY_RES = { activeConnections: 0, inflightRequests: 0, upstreamInFlight: 0 };
const EMPTY_ERR = { routeUnmatched: 0, proxyTimeout: 0, upstreamConnectFail: 0 };
const EMPTY_INSTANT = { heartbeat: 0, query: 0, push: 0, register: 0 };

createApp({
    data() {
        return {
            page: 'dashboard',
            nav: [
                { id: 'dashboard', label: '仪表盘', icon: ICONS.dashboard },
                { id: 'traces', label: '请求追踪', icon: ICONS.traces },
                { id: 'routes', label: '路由管理', icon: ICONS.routes },
                { id: 'instances', label: '实例管理', icon: ICONS.instances },
                { id: 'events', label: '最近事件', icon: ICONS.events },
                { id: 'configs', label: '配置管理', icon: ICONS.configs },
            ],
            loading: false,
            liveRange: 60,
            live: null,
            liveFailCount: 0,
            liveInFlight: false,
            updatedAt: 0,
            envOpen: true,
            selectedRouteId: null,
            jvmHistory: { gateway: [], nameserver: [] },

            discoveryType: 'UNKNOWN',
            status: { gateway: null, nameserver: null },
            metricsError: null,
            selfcheck: null,
            instances: [],

            routes: [],
            routesError: null,
            savingRoute: false,
            editingPrefix: null,
            routeDrawerOpen: false,
            routeForm: { id: '', businessPrefix: '', serviceName: '', targetUrl: '', targetUrls: '', group: '', stripPrefix: '' },

            instancesError: null,
            traces: [],
            tracesMeta: null,
            tracesError: null,
            traceFilter: { traceId: '', path: '', slow: false },
            selectedTrace: null,

            events: [],
            eventsError: null,
            knownEventKeys: new Set(),
            eventFilter: { type: '', service: '' },
            eventPage: 1,
            eventPageSize: 20,

            configs: [],
            configsError: null,
            configBusyKey: null,

            toasts: [],
            toastSeq: 0,
            charts: { qps: null, status: null },
            liveTimer: null,
            overviewTimer: null,
            pageTimer: null,
        };
    },

    computed: {
        pageTitle() {
            const item = this.nav.find(n => n.id === this.page);
            return item ? item.label : '';
        },
        pageSubtitle() {
            return {
                dashboard: '数字看不懂就悬停；顶栏 1m/5m 决定「近窗」多长',
                traces: '点一行在表内展开阶段耗时',
                routes: '路由规则热更新与落盘',
                instances: 'Nameserver 注册实例',
                events: '可按类型/服务筛选，分页看；最多缓存 200 条',
                configs: '按组件改运行时配置，脏了才保存',
            }[this.page] || '';
        },
        gw() {
            return (this.live && this.live.gateway && !this.live.gateway.error) ? this.live.gateway : null;
        },
        ns() {
            return (this.live && this.live.nameserver && !this.live.nameserver.error) ? this.live.nameserver : null;
        },
        gwTraffic() { return (this.gw && this.gw.traffic) ? this.gw.traffic : EMPTY_TRAFFIC; },
        gwRes() { return (this.gw && this.gw.resources) ? this.gw.resources : EMPTY_RES; },
        gatewayErrors() { return (this.gw && this.gw.gatewayErrors) ? this.gw.gatewayErrors : EMPTY_ERR; },
        windowStatus() { return this.gwTraffic.status || EMPTY_TRAFFIC.status; },
        liveRoutes() { return (this.gw && this.gw.routes) ? this.gw.routes : []; },
        selectedRoute() {
            return this.liveRoutes.find(r => r.routeId === this.selectedRouteId) || null;
        },
        nsInstant() { return (this.ns && this.ns.instant) ? this.ns.instant : EMPTY_INSTANT; },
        nsRes() { return (this.ns && this.ns.resources) ? this.ns.resources : EMPTY_RES; },
        nsReg() { return (this.ns && this.ns.registry) ? this.ns.registry : null; },
        nsHealthy() { return this.nsReg ? this.nsReg.healthyInstances : this.healthyCount; },
        nsInstances() { return this.nsReg ? this.nsReg.instanceCount : this.instances.length; },
        nsUnhealthy() { return this.nsReg ? this.nsReg.unhealthyInstances : this.unhealthyCount; },
        gatewayJvm() { return (this.gw && this.gw.jvm) ? this.gw.jvm : null; },
        nameserverJvm() { return (this.ns && this.ns.jvm) ? this.ns.jvm : null; },
        displayQps() {
            const t = this.gwTraffic;
            if (t.instantQps > 0) return t.instantQps;
            return t.currentSecondRequests || 0;
        },
        qpsHint() {
            const t = this.gwTraffic;
            if (t.idle && !t.currentSecondRequests) return '最近 1s 无请求';
            if (t.instantQps === 0 && t.currentSecondRequests > 0) return '本秒已有 ' + t.currentSecondRequests;
            return '上一秒 · 近 5s ' + this.num(t.qps5s);
        },
        liveState() {
            if (this.liveFailCount >= 3) return 'down';
            if (this.liveFailCount > 0) return 'warn';
            return 'ok';
        },
        liveStateLabel() {
            if (this.liveState === 'down') {
                return this.page === 'dashboard' ? '实时中断' : '组件中断';
            }
            if (this.liveState === 'warn') {
                return this.page === 'dashboard' ? '实时抖动' : '组件抖动';
            }
            return this.page === 'dashboard' ? '实时' : '在线';
        },
        livePillTitle() {
            if (this.page === 'dashboard') {
                return '仪表盘可视时每秒拉 live；切走页面或后台标签会停。绿=正常，黄=偶发失败，红=连续失败';
            }
            return '非仪表盘只靠 overview 探活（约 15s）；不打 live 以免空转观察税';
        },
        updatedAtText() {
            if (!this.updatedAt) return '';
            const d = new Date(this.updatedAt);
            const pad = n => String(n).padStart(2, '0');
            return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
        },
        jvmWarn() {
            const g = this.gatewayJvm;
            const n = this.nameserverJvm;
            return (g && g.heapUsedPercent >= 85) || (n && n.heapUsedPercent >= 85)
                || this.gcWindow('gateway').count >= 8 || this.gcWindow('nameserver').count >= 8;
        },
        gatewayOk() {
            if (this.gw) return true;
            return Boolean(this.status.gateway && this.status.gateway.reachable);
        },
        nameserverOk() {
            if (this.ns) return true;
            return Boolean(this.status.nameserver && this.status.nameserver.reachable);
        },
        gatewayData() { return this.status.gateway ? this.status.gateway.data : null; },
        nameserverData() { return this.status.nameserver ? this.status.nameserver.data : null; },
        selfcheckOk() { return Boolean(this.selfcheck && this.selfcheck.ok); },
        selfcheckText() {
            if (!this.selfcheck) return '自洽校验尚未拉取';
            return this.selfcheck.ok ? '累计加和自洽' : '累计加和不一致';
        },
        selfcheckShort() {
            if (!this.selfcheck) return '自洽 -';
            return this.selfcheck.ok ? '自洽' : '不一致';
        },
        healthyCount() { return this.instances.filter(i => i.healthy).length; },
        unhealthyCount() { return this.instances.filter(i => !i.healthy).length; },
        configGroups() {
            const gateway = this.configs.filter(c => c.component === 'gateway');
            const nameserver = this.configs.filter(c => c.component === 'nameserver');
            return [
                { component: 'gateway', title: 'Gateway', items: gateway },
                { component: 'nameserver', title: 'Nameserver', items: nameserver },
            ];
        },
        qpsPeak() {
            const series = (this.gw && this.gw.qpsSeries) ? this.gw.qpsSeries : [];
            let peak = 0;
            for (const p of series) {
                if ((p.count || 0) > peak) peak = p.count;
            }
            return peak;
        },
        filteredEvents() {
            const type = (this.eventFilter.type || '').trim();
            const service = (this.eventFilter.service || '').trim().toLowerCase();
            return (this.events || []).filter(e => {
                if (type && e.type !== type) return false;
                if (service && String(e.serviceName || '').toLowerCase().indexOf(service) < 0) return false;
                return true;
            });
        },
        eventTotalPages() {
            return Math.max(1, Math.ceil(this.filteredEvents.length / this.eventPageSize));
        },
        pagedEvents() {
            const page = Math.min(this.eventPage, this.eventTotalPages);
            const start = (page - 1) * this.eventPageSize;
            return this.filteredEvents.slice(start, start + this.eventPageSize);
        },
        eventTypeOptions() {
            // 固定展示完整事件目录；没有发生过的类型仍可筛选出 0 条。
            return ['EXPIRE_EVICT', 'MARK_UNHEALTHY', 'PUSH', 'REGISTER', 'UNREGISTER'];
        },
    },

    mounted() {
        this.$nextTick(() => this.initCharts());
        this.fetchOverview();
        this.fetchLive();
        // live 只服务仪表盘可视时；其它页靠 overview 探活，避免观察税随页面/标签页空转
        this.liveTimer = setInterval(() => this.fetchLive(), 1000);
        this.overviewTimer = setInterval(() => this.fetchOverview(), 15000);
        this.pageTimer = setInterval(() => this.pollCurrentPage(), 3000);
        this._resizeHandler = () => this.resizeCharts();
        this._visibilityHandler = () => {
            if (!document.hidden && this.page === 'dashboard') this.fetchLive();
        };
        window.addEventListener('resize', this._resizeHandler);
        document.addEventListener('visibilitychange', this._visibilityHandler);
    },

    beforeUnmount() {
        clearInterval(this.liveTimer);
        clearInterval(this.overviewTimer);
        clearInterval(this.pageTimer);
        window.removeEventListener('resize', this._resizeHandler);
        document.removeEventListener('visibilitychange', this._visibilityHandler);
    },

    methods: {
        toast(type, message) {
            const id = ++this.toastSeq;
            this.toasts.push({ id, type, message });
            if (this.toasts.length > 4) this.toasts.shift();
            setTimeout(() => {
                this.toasts = this.toasts.filter(t => t.id !== id);
            }, 3200);
        },
        num(v) {
            if (v === null || v === undefined) return '0';
            if (typeof v === 'number') {
                return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2);
            }
            return String(v);
        },
        fmtTime(millis) {
            if (!millis) return '-';
            const d = new Date(millis);
            const pad = n => String(n).padStart(2, '0');
            return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
        },
        fmtSecond(epochSecond) {
            const d = new Date(epochSecond * 1000);
            const pad = n => String(n).padStart(2, '0');
            return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
        },
        fmtFullTime(millis) { return this.fmtTime(millis); },
        fmtUptime(seconds) {
            if (seconds === null || seconds === undefined) return '-';
            const s = Number(seconds);
            if (s < 60) return Math.floor(s) + 's';
            if (s < 3600) return Math.floor(s / 60) + 'm ' + Math.floor(s % 60) + 's';
            const h = Math.floor(s / 3600);
            const m = Math.floor((s % 3600) / 60);
            return (h >= 24 ? Math.floor(h / 24) + 'd ' : '') + (h % 24) + 'h ' + m + 'm';
        },
        fmtHeap(jvm) {
            if (!jvm) return '-';
            return (jvm.heapUsedPercent || 0).toFixed(1) + '%';
        },
        fmtCpu(jvm) {
            if (!jvm || jvm.processCpuPercent === undefined) return '-';
            return Number(jvm.processCpuPercent).toFixed(1) + '%';
        },
        idleText(millis) {
            if (!millis) return '-';
            const sec = Math.max(0, Math.floor((Date.now() - millis) / 1000));
            if (sec < 5) return '刚刚';
            if (sec < 60) return sec + 's';
            return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
        },
        switchPage(page) {
            this.page = page;
            this.refreshAll();
            if (page === 'dashboard') {
                this.disposeCharts();
                this.$nextTick(() => this.initCharts());
            } else if (page !== 'traces') {
                this.selectedTrace = null;
            }
        },
        disposeCharts() {
            Object.values(this.charts).forEach(c => c && c.dispose());
            this.charts = { qps: null, status: null };
        },
        async refreshAll() {
            this.loading = true;
            try {
                if (this.page === 'dashboard') {
                    await this.fetchLive();
                    await this.fetchOverview();
                }
                if (this.page === 'traces') await this.fetchTraces();
                if (this.page === 'routes') await this.fetchRoutes();
                if (this.page === 'instances') await this.fetchInstances();
                if (this.page === 'events') await this.fetchEvents();
                if (this.page === 'configs') await this.fetchConfigs();
            } finally {
                this.loading = false;
            }
        },
        pollCurrentPage() {
            if (document.hidden) return;
            if (this.page === 'traces') this.fetchTraces();
            if (this.page === 'instances') this.fetchInstances();
            if (this.page === 'events') this.fetchEvents();
        },
        setLiveRange(range) {
            this.liveRange = range;
            this.fetchLive();
        },

        /** 纵轴好看的上限：峰值 * 1.15 再取 1/2/5×10^n，避免贴顶和假「上限 5」。 */
        niceCeil(value) {
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
        },
        async fetchLive() {
            // 非仪表盘或后台标签页：不打 Gateway/NS live，砍掉无观察者时的下游成本
            if (this.page !== 'dashboard' || document.hidden) return;
            if (this.liveInFlight) return;
            this.liveInFlight = true;
            try {
                const data = await api('/api/live?range=' + this.liveRange);
                this.live = data;
                this.updatedAt = data.serverTimeMillis || Date.now();
                const gwFail = data.gateway && data.gateway.error;
                const nsFail = data.nameserver && data.nameserver.error;
                if (gwFail && nsFail) {
                    this.liveFailCount += 1;
                    if (this.liveFailCount >= 3) this.metricsError = 'Gateway / Nameserver live 都读不到';
                } else {
                    this.liveFailCount = 0;
                    this.metricsError = gwFail ? 'Gateway live 读失败' : null;
                }
                this.pushJvmSample('gateway', this.gatewayJvm);
                this.pushJvmSample('nameserver', this.nameserverJvm);
                if (this.page === 'dashboard') this.updateCharts();
            } catch (e) {
                this.liveFailCount += 1;
                if (this.liveFailCount >= 3) this.metricsError = e.message;
            } finally {
                this.liveInFlight = false;
            }
        },

        async fetchOverview() {
            try {
                const overview = await api('/api/overview');
                this.status = overview.status || { gateway: null, nameserver: null };
                this.discoveryType = overview.discoveryType || 'UNKNOWN';
                this.selfcheck = overview.selfcheck && overview.selfcheck.error ? null : overview.selfcheck;
            } catch (e) {
                // overview 失败不影响 live
            }
        },

        pushJvmSample(side, jvm) {
            if (!jvm) return;
            const arr = this.jvmHistory[side];
            arr.push({
                t: Date.now(),
                heap: Number(jvm.heapUsedPercent) || 0,
                gcCount: Number(jvm.gcCount) || 0,
                gcTime: Number(jvm.gcTimeMillis) || 0,
            });
            const cutoff = Date.now() - 60000;
            this.jvmHistory[side] = arr.filter(s => s.t >= cutoff);
        },
        gcWindow(side) {
            const arr = this.jvmHistory[side] || [];
            if (arr.length < 2) return { count: 0, time: 0 };
            const first = arr[0];
            const last = arr[arr.length - 1];
            return {
                count: Math.max(0, last.gcCount - first.gcCount),
                time: Math.max(0, last.gcTime - first.gcTime),
            };
        },
        sparkPoints(side) {
            const arr = this.jvmHistory[side] || [];
            if (!arr.length) return '';
            const w = 120;
            const h = 28;
            return arr.map((s, i) => {
                const x = arr.length === 1 ? 0 : (i / (arr.length - 1)) * w;
                const y = h - Math.min(100, s.heap) / 100 * (h - 2) - 1;
                return x.toFixed(1) + ',' + y.toFixed(1);
            }).join(' ');
        },

        initCharts() {
            if (!this.charts.qps && document.getElementById('chart-qps')) {
                this.charts.qps = echarts.init(document.getElementById('chart-qps'));
            }
            if (!this.charts.status && document.getElementById('chart-status')) {
                this.charts.status = echarts.init(document.getElementById('chart-status'));
            }
            this.updateCharts();
        },
        resizeCharts() {
            Object.values(this.charts).forEach(c => c && c.resize());
        },
        updateCharts() {
            this.updateQpsChart();
            this.updateStatusChart();
        },
        updateQpsChart() {
            const chart = this.charts.qps;
            if (!chart || !this.gw) return;
            const series = this.gw.qpsSeries || [];
            const peak = this.qpsPeak;
            const idle = peak <= 0;
            chart.setOption({
                grid: { left: 52, right: 16, top: 16, bottom: 28 },
                // 无流量：明确空态，别留 0～5 大网格让人以为上限只有 5
                title: idle ? {
                    text: `近 ${this.liveRange} 秒无请求`,
                    left: 'center',
                    top: 'middle',
                    textStyle: { color: '#8a9a90', fontSize: 14, fontWeight: 500 },
                } : { text: '' },
                tooltip: {
                    show: !idle,
                    trigger: 'axis',
                    formatter: (params) => {
                        const p = params[0];
                        return `${p.axisValue}<br/>该秒完成 <b>${p.data}</b> 个请求`;
                    },
                },
                xAxis: {
                    show: !idle,
                    type: 'category',
                    data: series.map(p => this.fmtSecond(p.second)),
                    axisLine: { lineStyle: { color: '#d8e0db' } },
                    axisLabel: { color: '#6b7a72', fontSize: 11 },
                },
                yAxis: {
                    show: !idle,
                    type: 'value',
                    name: '请求/秒',
                    nameTextStyle: { color: '#6b7a72', fontSize: 11, padding: [0, 0, 0, 8] },
                    minInterval: 1,
                    // 峰值×1.15 再取整档；纵轴不是容量上限
                    max: idle ? 1 : this.niceCeil(peak),
                    splitLine: { show: !idle, lineStyle: { color: '#eef2ef' } },
                    axisLabel: { color: '#6b7a72', fontSize: 11 },
                },
                series: idle ? [] : [{
                    name: '请求/秒',
                    type: 'line',
                    // 非平滑：每秒一个台阶，不编造中间趋势
                    smooth: false,
                    step: 'end',
                    showSymbol: false,
                    data: series.map(p => p.count),
                    lineStyle: { color: '#1f6f5b', width: 2 },
                    areaStyle: {
                        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                            { offset: 0, color: 'rgba(31, 111, 91, 0.22)' },
                            { offset: 1, color: 'rgba(31, 111, 91, 0)' },
                        ]),
                    },
                }],
            }, true);
        },
        updateStatusChart() {
            const chart = this.charts.status;
            if (!chart) return;
            const s = this.windowStatus;
            const total = (s['2xx'] || 0) + (s['3xx'] || 0) + (s['4xx'] || 0) + (s['5xx'] || 0);
            // 全 0 时别画四等分假饼图
            if (total <= 0) {
                chart.setOption({
                    title: {
                        text: `近 ${this.liveRange} 秒无状态码样本`,
                        left: 'center',
                        top: 'middle',
                        textStyle: { color: '#8a9a90', fontSize: 14, fontWeight: 500 },
                    },
                    series: [],
                    legend: { show: false },
                    tooltip: { show: false },
                }, true);
                return;
            }
            chart.setOption({
                title: { text: '' },
                tooltip: { show: true, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
                legend: { show: true, bottom: 0, textStyle: { color: '#6b7a72', fontSize: 12 } },
                series: [{
                    type: 'pie',
                    radius: ['46%', '70%'],
                    center: ['50%', '44%'],
                    avoidLabelOverlap: true,
                    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
                    label: { show: false },
                    emphasis: { label: { show: true, fontWeight: 'bold', fontSize: 14 } },
                    color: ['#1f6f5b', '#5aa08a', '#e0a458', '#c05555'],
                    data: [
                        { name: '2xx', value: s['2xx'] || 0 },
                        { name: '3xx', value: s['3xx'] || 0 },
                        { name: '4xx', value: s['4xx'] || 0 },
                        { name: '5xx', value: s['5xx'] || 0 },
                    ],
                }],
            }, true);
        },
        toggleRoute(routeId) {
            this.selectedRouteId = this.selectedRouteId === routeId ? null : routeId;
        },
        instanceEntries(route) {
            return route && route.instances ? Object.keys(route.instances) : [];
        },
        /** 指标里的特殊路由 id 换成可读文案。 */
        routeLabel(routeId) {
            if (!routeId) return '-';
            if (routeId === '__unmatched__') return '未匹配';
            return routeId;
        },

        async fetchTraces() {
            this.tracesError = null;
            const params = new URLSearchParams();
            if (this.traceFilter.traceId) params.set('traceId', this.traceFilter.traceId);
            if (this.traceFilter.path) params.set('path', this.traceFilter.path);
            if (this.traceFilter.slow) params.set('slow', 'true');
            const qs = params.toString() ? ('?' + params.toString()) : '';
            try {
                const data = await api('/api/traces' + qs);
                this.tracesMeta = {
                    count: data.count,
                    capacity: data.capacity,
                    sampleRate: data.sampleRate,
                    slowThresholdMillis: data.slowThresholdMillis,
                };
                this.traces = data.traces || [];
                if (this.selectedTrace
                    && !this.traces.some(t => t.traceId === this.selectedTrace.traceId)) {
                    this.selectedTrace = null;
                }
            } catch (e) {
                this.tracesError = e.message;
            }
        },
        selectTrace(trace) {
            if (this.selectedTrace && this.selectedTrace.traceId === trace.traceId) {
                this.selectedTrace = null;
                return;
            }
            this.selectedTrace = trace;
        },
        phaseLabel(name) {
            const map = {
                receive: '接收/解码',
                filter: 'Filter 链',
                route: '路由匹配',
                discovery: '服务发现',
                loadbalance: '负载均衡',
                connect: '连接上游',
                proxy: '上游处理',
                write: '响应写回',
            };
            return map[name] || name;
        },
        phaseTip(name) {
            const map = {
                receive: '网关收到请求并解码',
                filter: '插件/过滤器链处理',
                route: '按路径匹配路由规则',
                discovery: '查该服务有哪些实例',
                loadbalance: '从实例里挑一个上游',
                connect: '连上游（新建连接时才有耗时）',
                proxy: '等上游算完并回包，通常最占时间',
                write: '把响应写回客户端',
            };
            return map[name] || '';
        },
        phasePercent(costMs) {
            const total = this.selectedTrace ? this.selectedTrace.totalCostMs : 0;
            if (!total) return 0;
            return Math.max(0.5, Math.min(100, (costMs / total) * 100));
        },
        statusBadge(code) {
            if (code >= 500) return 'bad';
            if (code >= 400) return 'warn';
            return 'ok';
        },

        async fetchEvents() {
            this.eventsError = null;
            try {
                const rows = await api('/api/events');
                const nextKeys = new Set();
                this.events = (rows || []).map(e => {
                    const key = this.eventKey(e);
                    nextKeys.add(key);
                    e._fresh = this.knownEventKeys.size > 0 && !this.knownEventKeys.has(key);
                    return e;
                });
                this.knownEventKeys = nextKeys;
                if (this.eventPage > this.eventTotalPages) {
                    this.eventPage = this.eventTotalPages;
                }
            } catch (e) {
                this.eventsError = e.message;
            }
        },
        resetEventPage() {
            this.eventPage = 1;
        },
        eventKey(e) {
            return [e.timestampMillis, e.type, e.serviceName, e.instanceId, e.detail].join('|');
        },
        eventLabel(type) {
            const map = {
                REGISTER: '注册',
                UNREGISTER: '注销',
                EXPIRE_EVICT: '心跳剔除',
                MARK_UNHEALTHY: '标不健康',
                PUSH: '变更推送',
            };
            return map[type] || type;
        },
        eventBadge(type) {
            const map = {
                REGISTER: 'ok',
                UNREGISTER: 'warn',
                EXPIRE_EVICT: 'bad',
                MARK_UNHEALTHY: 'bad',
                PUSH: 'comp',
            };
            return map[type] || 'comp';
        },

        async fetchRoutes() {
            this.routesError = null;
            try {
                this.routes = await api('/api/routes');
            } catch (e) {
                this.routesError = e.message;
            }
        },
        resetRouteForm() {
            this.routeForm = { id: '', businessPrefix: '', serviceName: '', targetUrl: '', targetUrls: '', group: '', stripPrefix: '' };
            this.editingPrefix = null;
        },
        openRouteDrawer(route) {
            if (route) {
                this.editingPrefix = route.businessPrefix;
                this.routeForm = {
                    id: route.id || '',
                    businessPrefix: route.businessPrefix || '',
                    serviceName: route.serviceName || '',
                    targetUrl: route.targetUrl || '',
                    targetUrls: route.targetUrls || '',
                    group: route.group || '',
                    stripPrefix: route.stripPrefix || '',
                };
            } else {
                this.resetRouteForm();
            }
            this.routeDrawerOpen = true;
        },
        closeRouteDrawer() {
            this.routeDrawerOpen = false;
            this.resetRouteForm();
        },
        async saveRoute() {
            const form = this.routeForm;
            if (!form.businessPrefix) {
                this.toast('error', 'businessPrefix 必填');
                return;
            }
            if (this.discoveryType === 'NAMESERVER' && !form.serviceName) {
                this.toast('error', '动态模式需要填写 serviceName');
                return;
            }
            this.savingRoute = true;
            try {
                const result = await api('/api/routes', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(form),
                });
                this.toast('success', result.message || '路由已保存并热生效');
                this.closeRouteDrawer();
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
            } finally {
                this.savingRoute = false;
            }
        },
        async deleteRoute(prefix) {
            if (!confirm(`确定删除路由 ${prefix} 吗？`)) return;
            try {
                const result = await api('/api/routes?businessPrefix=' + encodeURIComponent(prefix), { method: 'DELETE' });
                this.toast('success', result.message || '路由已删除');
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
            }
        },

        async fetchInstances() {
            this.instancesError = null;
            try {
                this.instances = await api('/api/instances');
            } catch (e) {
                this.instancesError = e.message;
            }
        },

        async fetchConfigs() {
            this.configsError = null;
            try {
                const rows = await api('/api/configs');
                this.configs = (rows || []).map(c => {
                    c._saved = c.value;
                    return c;
                });
            } catch (e) {
                this.configsError = e.message;
            }
        },
        isDirty(c) {
            return String(c.value) !== String(c._saved);
        },
        isBoolConfig(c) {
            const opts = (c.options || []).map(v => String(v).toLowerCase());
            return opts.length === 2 && opts.includes('true') && opts.includes('false');
        },
        isNumberConfig(c) {
            const key = c.key || '';
            // 只按末尾配置名判断；否则 loadbalance.strategy 中的 "rate" 会被误判为数字配置。
            if (/(millis|seconds|rate|timeout|interval|expire)$/i.test(key)) return true;
            return c.options && c.options.length && c.options.every(v => !Number.isNaN(Number(v)));
        },
        isEnumConfig(c) {
            return c.options && c.options.length
                && !this.isBoolConfig(c)
                && !this.isNumberConfig(c);
        },
        selectOptions(c) {
            const options = (c.options || []).map(v => String(v));
            const value = String(c.value || '');
            if (value && !options.includes(value)) {
                return [value, ...options];
            }
            return options;
        },
        numberUnit(c) {
            if (/rateLimit\.permitsPerSecond$/i.test(c.key || '')) return '请求/秒';
            if (/rateLimit\.(burst|limit)$/i.test(c.key || '')) return '请求';
            if (/rate/i.test(c.key || '')) return '';
            if (/seconds/i.test(c.key || '')) return '秒';
            return 'ms';
        },
        numberStep(c) {
            return /rate/i.test(c.key || '') ? '0.01' : '1';
        },
        numberMin(c) {
            return /rate/i.test(c.key || '') ? '0' : '1';
        },
        numberMax(c) {
            return /rate/i.test(c.key || '') ? '1' : undefined;
        },
        /** 采样率快捷按钮用白话，别只扔 0/1。 */
        configChipLabel(c, opt) {
            if ((c.key || '').endsWith('sampleRate')) {
                if (String(opt) === '0') return '只记慢请求';
                if (String(opt) === '1') return '全量记录';
            }
            return opt;
        },
        /** 采样率只给两个常用档；中间值可手输。 */
        configQuickOptions(c) {
            if ((c.key || '').endsWith('sampleRate')) return ['0', '1'];
            return c.options || [];
        },
        revertConfig(c) {
            c.value = c._saved;
        },
        async updateConfig(c) {
            this.configBusyKey = c.key;
            try {
                const result = await api('/api/configs', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ component: c.component, key: c.key, value: String(c.value) }),
                });
                this.toast('success', `${c.component}.${c.key} 已更新：${result.message || ''}`);
                await this.fetchConfigs();
            } catch (e) {
                this.toast('error', e.message);
            } finally {
                this.configBusyKey = null;
            }
        },
    },
}).mount('#app');
