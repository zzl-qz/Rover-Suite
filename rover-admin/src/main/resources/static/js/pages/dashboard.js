/** 仪表盘：live 轮询、图表、JVM 火花线、路由热度。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.dashboard = {
    data() {
        return {
            liveRange: 60,
            live: null,
            liveFailCount: 0,
            liveInFlight: false,
            selectedRouteId: null,
            jvmHistory: { gateway: [], nameserver: [] },
            charts: { qps: null, status: null },
        };
    },

    computed: {
        gw() {
            return (this.live && this.live.gateway && !this.live.gateway.error) ? this.live.gateway : null;
        },
        ns() {
            return (this.live && this.live.nameserver && !this.live.nameserver.error) ? this.live.nameserver : null;
        },
        gwTraffic() {
            return (this.gw && this.gw.traffic) ? this.gw.traffic : RoverAdminConstants.EMPTY_TRAFFIC;
        },
        gwRes() {
            return (this.gw && this.gw.resources) ? this.gw.resources : RoverAdminConstants.EMPTY_RES;
        },
        gatewayErrors() {
            return (this.gw && this.gw.gatewayErrors) ? this.gw.gatewayErrors : RoverAdminConstants.EMPTY_ERR;
        },
        windowStatus() {
            return this.gwTraffic.status || RoverAdminConstants.EMPTY_TRAFFIC.status;
        },
        liveRoutes() { return (this.gw && this.gw.routes) ? this.gw.routes : []; },
        selectedRoute() {
            return this.liveRoutes.find(r => r.routeId === this.selectedRouteId) || null;
        },
        nsInstant() {
            return (this.ns && this.ns.instant) ? this.ns.instant : RoverAdminConstants.EMPTY_INSTANT;
        },
        nsRes() {
            return (this.ns && this.ns.resources) ? this.ns.resources : RoverAdminConstants.EMPTY_RES;
        },
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
        jvmWarn() {
            const g = this.gatewayJvm;
            const n = this.nameserverJvm;
            return (g && g.heapUsedPercent >= 85) || (n && n.heapUsedPercent >= 85)
                || this.gcWindow('gateway').count >= 8 || this.gcWindow('nameserver').count >= 8;
        },
        qpsPeak() {
            const series = (this.gw && this.gw.qpsSeries) ? this.gw.qpsSeries : [];
            let peak = 0;
            for (const p of series) {
                if ((p.count || 0) > peak) peak = p.count;
            }
            return peak;
        },
    },

    methods: {
        setLiveRange(range) {
            this.liveRange = range;
            this.fetchLive();
        },
        disposeCharts() {
            Object.values(this.charts).forEach(c => c && c.dispose());
            this.charts = { qps: null, status: null };
        },
        async fetchLive() {
            // 非仪表盘或后台标签页：不打 Gateway/NS live，砍掉无观察者时的下游成本
            if (this.page !== 'dashboard' || document.hidden) return;
            if (this.liveInFlight) return;
            this.liveInFlight = true;
            try {
                const data = await RoverAdminApi.api('/api/live?range=' + this.liveRange);
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
    },
};
