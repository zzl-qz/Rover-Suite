/**
 * Rover Admin - 轻量控制台
 * Vue 3 (CDN) + ECharts：侧边栏多页面（仪表盘/路由/实例/配置），仪表盘每 5 秒轮询。
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

const EMPTY_TOTAL = {
    requests: 0, windowRequests: 0, expiredRequests: 0, qps: 0,
    avgMillis: 0, p95Millis: 0, p99Millis: 0, maxMillis: 0, errorRequests: 0,
    status: { '2xx': 0, '3xx': 0, '4xx': 0, '5xx': 0 },
    gatewayErrors: { routeUnmatched: 0, proxyTimeout: 0, upstreamConnectFail: 0 },
};

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

            // 仪表盘数据
            discoveryType: 'UNKNOWN',
            status: { gateway: null, nameserver: null },
            metrics: null,
            metricsError: null,
            selfcheck: null,
            instances: [],

            // 路由页
            routes: [],
            routesError: null,
            savingRoute: false,
            editingPrefix: null,
            routeForm: { id: '', businessPrefix: '', serviceName: '', targetUrl: '', targetUrls: '', group: '', stripPrefix: '' },

            // 实例页
            instancesError: null,

            // Nameserver 指标（仪表盘 JVM 卡片）
            nameserverMetrics: null,

            // 请求追踪页
            traces: [],
            tracesMeta: null,
            tracesError: null,
            traceFilter: { traceId: '', path: '', slow: false },
            selectedTrace: null,
            waterfallChart: null,

            // 最近事件页
            events: [],
            eventsError: null,

            // 配置页
            configs: [],
            configsError: null,
            configBusyKey: null,
            openConfigKey: null,

            // Toast
            toasts: [],
            toastSeq: 0,

            // 图表实例
            charts: { qps: null, status: null, routes: null },
            pollTimer: null,
        };
    },

    computed: {
        pageTitle() {
            const item = this.nav.find(n => n.id === this.page);
            return item ? item.label : '';
        },
        pageSubtitle() {
            return {
                dashboard: 'Gateway 流量与组件健康总览',
                traces: '网关内各阶段耗时拆解与 traceId 透传',
                routes: '路由规则热更新与落盘',
                instances: 'Nameserver 注册实例实时状态',
                events: '注册 / 注销 / 剔除等生命周期日志',
                configs: 'Gateway / Nameserver 运行时配置',
            }[this.page] || '';
        },
        total() {
            return (this.metrics && this.metrics.total) ? this.metrics.total : EMPTY_TOTAL;
        },
        gatewayJvm() {
            return (this.metrics && this.metrics.jvm) ? this.metrics.jvm : null;
        },
        nameserverJvm() {
            return (this.nameserverMetrics && this.nameserverMetrics.jvm)
                ? this.nameserverMetrics.jvm : null;
        },
        gatewayOk() {
            return Boolean(this.status.gateway && this.status.gateway.reachable);
        },
        nameserverOk() {
            return Boolean(this.status.nameserver && this.status.nameserver.reachable);
        },
        gatewayData() { return this.status.gateway ? this.status.gateway.data : null; },
        gatewayError() { return this.status.gateway ? this.status.gateway.error : ''; },
        nameserverData() { return this.status.nameserver ? this.status.nameserver.data : null; },
        nameserverError() { return this.status.nameserver ? this.status.nameserver.error : ''; },
        selfcheckOk() {
            return Boolean(this.selfcheck && this.selfcheck.ok);
        },
        selfcheckText() {
            if (!this.selfcheck) return '自洽校验 -';
            return this.selfcheck.ok ? '数据自洽 ✓' : '数据不一致 ✗';
        },
        healthyCount() { return this.instances.filter(i => i.healthy).length; },
        unhealthyCount() { return this.instances.filter(i => !i.healthy).length; },
        serviceCount() {
            return new Set(this.instances.map(i => i.serviceName)).size;
        },
        serviceSummary() {
            const map = new Map();
            for (const ins of this.instances) {
                const key = ins.serviceName;
                if (!map.has(key)) map.set(key, { serviceName: key, total: 0, healthy: 0 });
                const row = map.get(key);
                row.total++;
                if (ins.healthy) row.healthy++;
            }
            return [...map.values()];
        },
    },

    mounted() {
        // 首屏默认在仪表盘，DOM 就绪后初始化图表
        this.$nextTick(() => this.initCharts());
        this.refreshAll();
        this.pollTimer = setInterval(() => {
            if (this.page === 'dashboard') this.fetchDashboard();
        }, 5000);
        this.instancesTimer = setInterval(() => {
            if (this.page === 'instances') this.fetchInstances();
        }, 10000);
        this.tracesTimer = setInterval(() => {
            if (this.page === 'traces') this.fetchTraces();
        }, 5000);
        this.eventsTimer = setInterval(() => {
            if (this.page === 'events') this.fetchEvents();
        }, 5000);
        this._resizeHandler = () => this.resizeCharts();
        window.addEventListener('resize', this._resizeHandler);
    },

    beforeUnmount() {
        clearInterval(this.pollTimer);
        clearInterval(this.instancesTimer);
        clearInterval(this.tracesTimer);
        clearInterval(this.eventsTimer);
        window.removeEventListener('resize', this._resizeHandler);
    },

    methods: {
        // ===== 通用 =====
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
        fmtFullTime(millis) {
            if (!millis) return '-';
            const d = new Date(millis);
            const pad = n => String(n).padStart(2, '0');
            return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
        },
        fmtUptime(seconds) {
            if (seconds === null || seconds === undefined) return '-';
            const s = Number(seconds);
            if (s < 60) return Math.floor(s) + 's';
            if (s < 3600) return Math.floor(s / 60) + 'm ' + Math.floor(s % 60) + 's';
            const h = Math.floor(s / 3600);
            const m = Math.floor((s % 3600) / 60);
            return (h >= 24 ? Math.floor(h / 24) + 'd ' : '') + (h % 24) + 'h ' + m + 'm';
        },
        switchPage(page) {
            this.page = page;
            this.refreshAll();
            if (page === 'dashboard') {
                // v-if 切页会重建图表 DOM，必须销毁旧实例后重新初始化
                this.disposeCharts();
                this.$nextTick(() => this.initCharts());
            } else if (page !== 'traces') {
                this.selectedTrace = null;
                if (this.waterfallChart) {
                    this.waterfallChart.dispose();
                    this.waterfallChart = null;
                }
            }
        },
        disposeCharts() {
            Object.values(this.charts).forEach(c => c && c.dispose());
            this.charts = { qps: null, status: null, routes: null };
        },
        refreshAll() {
            if (this.page === 'dashboard') this.fetchDashboard();
            if (this.page === 'traces') this.fetchTraces();
            if (this.page === 'routes') this.fetchRoutes();
            if (this.page === 'instances') this.fetchInstances();
            if (this.page === 'events') this.fetchEvents();
            if (this.page === 'configs') this.fetchConfigs();
        },

        // ===== 仪表盘 =====
        async fetchDashboard() {
            this.loading = true;
            this.metricsError = null;
            try {
                const overview = await api('/api/overview');
                this.status = overview.status || { gateway: null, nameserver: null };
                this.discoveryType = overview.discoveryType || 'UNKNOWN';
                this.metrics = overview.metrics && !overview.metrics.error ? overview.metrics : null;
                if (overview.metrics && overview.metrics.error) {
                    this.metricsError = overview.metrics.error;
                }
                this.selfcheck = overview.selfcheck && overview.selfcheck.error ? null : overview.selfcheck;
                try {
                    this.instances = await api('/api/instances');
                } catch (e) {
                    this.instances = [];
                }
                try {
                    this.nameserverMetrics = await api('/api/nameserver/metrics');
                    if (this.nameserverMetrics && this.nameserverMetrics.error) {
                        this.nameserverMetrics = null;
                    }
                } catch (e) {
                    this.nameserverMetrics = null;
                }
                this.updateCharts();
            } catch (e) {
                this.metricsError = e.message;
            } finally {
                this.loading = false;
            }
        },

        initCharts() {
            if (!this.charts.qps && document.getElementById('chart-qps')) {
                this.charts.qps = echarts.init(document.getElementById('chart-qps'));
            }
            if (!this.charts.status && document.getElementById('chart-status')) {
                this.charts.status = echarts.init(document.getElementById('chart-status'));
            }
            if (!this.charts.routes && document.getElementById('chart-routes')) {
                this.charts.routes = echarts.init(document.getElementById('chart-routes'));
            }
            this.updateCharts();
        },

        resizeCharts() {
            Object.values(this.charts).forEach(c => c && c.resize());
        },

        updateCharts() {
            if (!this.metrics) return;
            this.updateQpsChart();
            this.updateStatusChart();
            this.updateRoutesChart();
        },

        updateQpsChart() {
            const chart = this.charts.qps;
            if (!chart) return;
            const series = (this.metrics.qpsSeries || []).slice(-120);
            chart.setOption({
                grid: { left: 42, right: 16, top: 24, bottom: 28 },
                tooltip: { trigger: 'axis' },
                xAxis: {
                    type: 'category',
                    data: series.map(p => this.fmtSecond(p.second)),
                    axisLine: { lineStyle: { color: '#d8e0db' } },
                    axisLabel: { color: '#6b7a72', fontSize: 11 },
                },
                yAxis: {
                    type: 'value',
                    minInterval: 1,
                    splitLine: { lineStyle: { color: '#eef2ef' } },
                    axisLabel: { color: '#6b7a72', fontSize: 11 },
                },
                series: [{
                    name: 'QPS',
                    type: 'line',
                    smooth: true,
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
            });
        },

        updateStatusChart() {
            const chart = this.charts.status;
            if (!chart) return;
            const s = this.total.status || EMPTY_TOTAL.status;
            chart.setOption({
                tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
                legend: { bottom: 0, textStyle: { color: '#6b7a72', fontSize: 12 } },
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
            });
        },

        updateRoutesChart() {
            const chart = this.charts.routes;
            if (!chart) return;
            const rows = (this.metrics.routes || [])
                .slice()
                .sort((a, b) => b.windowRequests - a.windowRequests)
                .slice(0, 8)
                .reverse();
            chart.setOption({
                grid: { left: 8, right: 30, top: 10, bottom: 8, containLabel: true },
                tooltip: {
                    trigger: 'axis',
                    axisPointer: { type: 'shadow' },
                    formatter: (params) => {
                        const r = rows[params[0].dataIndex];
                        return `${r.routeId}<br/>请求 ${r.windowRequests}（累计 ${r.requests}）<br/>错误率 ${(r.errorRate * 100).toFixed(2)}%<br/>平均 ${r.avgMillis}ms / P95 ${r.p95Millis}ms`;
                    },
                },
                xAxis: {
                    type: 'value',
                    minInterval: 1,
                    splitLine: { lineStyle: { color: '#eef2ef' } },
                    axisLabel: { color: '#6b7a72', fontSize: 11 },
                },
                yAxis: {
                    type: 'category',
                    data: rows.map(r => r.routeId),
                    axisLine: { show: false },
                    axisTick: { show: false },
                    axisLabel: { color: '#1c2b25', fontSize: 12 },
                },
                series: [{
                    type: 'bar',
                    data: rows.map(r => r.windowRequests),
                    barWidth: 14,
                    itemStyle: { color: '#1f6f5b', borderRadius: [0, 6, 6, 0] },
                    label: { show: true, position: 'right', color: '#6b7a72', fontSize: 11 },
                }],
            });
        },

        // ===== 请求追踪 =====
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
                // 若选中的请求还在列表里则保持展开，否则收起
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
                if (this.waterfallChart) { this.waterfallChart.dispose(); this.waterfallChart = null; }
                return;
            }
            this.selectedTrace = trace;
            this.$nextTick(() => this.updateWaterfallChart());
        },
        updateWaterfallChart() {
            if (!this.selectedTrace) return;
            if (!document.getElementById('chart-waterfall')) return;
            if (!this.waterfallChart) {
                this.waterfallChart = echarts.init(document.getElementById('chart-waterfall'));
            }
            const phases = this.selectedTrace.phases || [];
            // 瀑布图：每个阶段用“透明基座（累计起点）+ 实际耗时条”表达
            let offset = 0;
            const base = [];
            const costs = [];
            const labels = [];
            for (const p of phases) {
                labels.push(this.phaseLabel(p.name));
                base.push(offset);
                costs.push(p.costMs);
                offset += p.costMs;
            }
            this.waterfallChart.setOption({
                grid: { left: 8, right: 40, top: 12, bottom: 8, containLabel: true },
                tooltip: {
                    trigger: 'axis',
                    axisPointer: { type: 'shadow' },
                    formatter: (params) => {
                        const idx = params[0].dataIndex;
                        return `${labels[idx]}：${costs[idx]}ms<br/>开始于 ${base[idx]}ms`;
                    },
                },
                xAxis: {
                    type: 'value',
                    name: 'ms',
                    splitLine: { lineStyle: { color: '#eef2ef' } },
                    axisLabel: { color: '#6b7a72', fontSize: 11 },
                },
                yAxis: {
                    type: 'category',
                    data: labels,
                    axisLine: { show: false },
                    axisTick: { show: false },
                    axisLabel: { color: '#1c2b25', fontSize: 12 },
                },
                series: [
                    {
                        name: '基座',
                        type: 'bar',
                        stack: 'wf',
                        itemStyle: { color: 'transparent' },
                        barWidth: 14,
                        data: base,
                        emphasis: { itemStyle: { color: 'transparent' } },
                        tooltip: { show: false },
                    },
                    {
                        name: '耗时',
                        type: 'bar',
                        stack: 'wf',
                        barWidth: 14,
                        itemStyle: { color: '#1f6f5b', borderRadius: [0, 6, 6, 0] },
                        data: costs,
                        label: {
                            show: true,
                            position: 'right',
                            color: '#6b7a72',
                            fontSize: 11,
                            formatter: p => p.value + 'ms',
                        },
                    },
                ],
            });
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

        // ===== 最近事件 =====
        async fetchEvents() {
            this.eventsError = null;
            try {
                this.events = await api('/api/events');
            } catch (e) {
                this.eventsError = e.message;
            }
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

        // ===== 路由 =====
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
        startEdit(route) {
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
            window.scrollTo({ top: 0, behavior: 'smooth' });
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
                this.resetRouteForm();
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

        // ===== 实例 =====
        async fetchInstances() {
            this.instancesError = null;
            try {
                this.instances = await api('/api/instances');
            } catch (e) {
                this.instancesError = e.message;
            }
        },

        // ===== 配置 =====
        async fetchConfigs() {
            this.configsError = null;
            try {
                this.configs = await api('/api/configs');
            } catch (e) {
                this.configsError = e.message;
            }
        },
        async updateConfig(item) {
            this.configBusyKey = item.key;
            try {
                const result = await api('/api/configs', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ component: item.component, key: item.key, value: item.value }),
                });
                this.toast('success', `${item.component}.${item.key} 已更新：${result.message || ''}`);
                await this.fetchConfigs();
            } catch (e) {
                this.toast('error', e.message);
            } finally {
                this.configBusyKey = null;
            }
        },
        toggleConfigOptions(c) {
            this.openConfigKey = (this.openConfigKey === c.key) ? null : c.key;
        },
        selectConfigOption(c, opt) {
            c.value = opt;
            this.openConfigKey = null;
        },
        closeConfigOptions() {
            this.openConfigKey = null;
        },
    },
}).mount('#app');
