/** 人工指标诊断：全量快照低频刷新，路由窗口独立于全局配置窗口。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.metrics = {
    data() {
        return {
            fullMetrics: null,
            fullMetricsError: null,
            fullMetricsLoading: false,
            fullMetricsAt: 0,
            metricsTimer: null,
            diagnosticRouteId: '',
            diagnosticRange: 60,
            routeMetrics: null,
            routeMetricsError: null,
            routeMetricsLoading: false,
            routeMetricsAt: 0,
            routeMetricsRequest: 0,
        };
    },

    computed: {
        metricsTotal() { return (this.fullMetrics && this.fullMetrics.total) || {}; },
        metricsResources() { return (this.fullMetrics && this.fullMetrics.resources) || {}; },
        metricsRoutes() { return (this.fullMetrics && this.fullMetrics.routes) || []; },
        metricsUpstreams() { return (this.fullMetrics && this.fullMetrics.upstreams) || []; },
        diagnosticRouteOptions() {
            // 配置中无流量的路由也可选择；历史观测中的路由仍可回看。无 ID 路由不能独立归因。
            return [...new Set([
                ...this.routes.map(r => r.id),
                ...this.metricsRoutes.map(r => r.routeId),
                this.diagnosticRouteId,
            ].filter(Boolean))];
        },
        diagnosticInstances() { return (this.routeMetrics && this.routeMetrics.rows) || []; },
        diagnosticVersions() { return (this.routeMetrics && this.routeMetrics.byVersion) || []; },
        diagnosticVersionCheck() { return (this.routeMetrics && this.routeMetrics.versionCheck) || {}; },
        diagnosticRequests() {
            return this.diagnosticInstances.reduce((sum, row) => sum + Number(row.windowRequests || 0), 0);
        },
        metricsWindowStats() {
            const t = this.metricsTotal;
            return [
                { label: '窗口请求数', value: this.metricNum(t.windowRequests) },
                { label: '窗口 5xx 数', value: this.metricNum(t.windowStatus && t.windowStatus['5xx']) },
                { label: '平均耗时', value: this.metricLatency(t.avgMillis, t.windowRequests) },
                { label: 'P95（采样估计）', value: this.metricLatency(t.p95Millis, t.windowRequests) },
                { label: 'P99（采样估计）', value: this.metricLatency(t.p99Millis, t.windowRequests) },
                { label: '最大耗时', value: this.metricLatency(t.maxMillis, t.windowRequests) },
            ];
        },
        metricsCounters() {
            const res = this.metricsResources;
            const rejects = res.rejects || {};
            const retries = res.retries || {};
            const errors = this.metricsTotal.gatewayErrors || {};
            return [
                { label: '请求总数', value: this.metricsTotal.requests },
                { label: '在途限额拒绝', value: rejects.inflightLimit },
                { label: '无上游拒绝', value: rejects.noUpstream },
                { label: '熔断拒绝', value: rejects.circuitOpen },
                { label: '连接重试', value: retries.connect },
                { label: '未匹配路由', value: errors.routeUnmatched },
                { label: '代理超时', value: errors.proxyTimeout },
                { label: '上游连接失败', value: errors.upstreamConnectFail },
            ];
        },
    },

    methods: {
        async openMetrics(routeId = '') {
            this.diagnosticRouteId = routeId;
            this.resetRouteMetrics();
            await this.switchPage('metrics');
            if (routeId && this.page === 'metrics' && this.diagnosticRouteId === routeId) {
                this.$nextTick(() => document.getElementById('route-diagnostic')?.scrollIntoView({ block: 'start' }));
            }
        },
        resetRouteMetrics() {
            // 使已发出的旧路由/窗口请求失效，避免迟到响应覆盖当前选择。
            this.routeMetricsRequest++;
            this.routeMetrics = null;
            this.routeMetricsError = null;
            this.routeMetricsAt = 0;
            this.routeMetricsLoading = false;
        },
        selectDiagnosticRoute() {
            this.resetRouteMetrics();
            this.fetchRouteMetrics();
        },
        setDiagnosticRange(range) {
            if (this.diagnosticRange === range) return;
            this.diagnosticRange = range;
            this.selectDiagnosticRoute();
        },
        async refreshMetrics() {
            if (this.page !== 'metrics' || document.hidden) return;
            await Promise.all([this.fetchFullMetrics(), this.fetchRouteMetrics()]);
        },
        async fetchFullMetrics() {
            if (this.page !== 'metrics' || document.hidden || this.fullMetricsLoading) return;
            this.fullMetricsLoading = true;
            try {
                const data = await RoverAdminApi.api('/api/metrics');
                if (!data || data.error) throw new Error((data && data.error) || '未返回指标快照');
                this.fullMetrics = data;
                this.fullMetricsAt = Date.now();
                this.fullMetricsError = null;
            } catch (e) {
                this.fullMetricsError = e.message;
            } finally {
                this.fullMetricsLoading = false;
            }
        },
        async fetchRouteMetrics() {
            if (this.page !== 'metrics' || document.hidden || !this.diagnosticRouteId || this.routeMetricsLoading) return;
            const request = ++this.routeMetricsRequest;
            const routeId = this.diagnosticRouteId;
            const range = this.diagnosticRange;
            this.routeMetricsLoading = true;
            try {
                const data = await RoverAdminApi.api('/api/metrics/routes?routeId='
                    + encodeURIComponent(routeId) + '&range=' + range);
                if (request !== this.routeMetricsRequest) return;
                if (!data || data.error) throw new Error((data && data.error) || '未返回路由观测');
                this.routeMetrics = data;
                this.routeMetricsAt = data.observedAtMillis || data.serverTimeMillis || Date.now();
                this.routeMetricsError = null;
            } catch (e) {
                if (request === this.routeMetricsRequest) this.routeMetricsError = e.message;
            } finally {
                if (request === this.routeMetricsRequest) this.routeMetricsLoading = false;
            }
        },
        metricNum(value) {
            return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? this.num(value) : '—';
        },
        metricPercent(value) {
            return typeof value === 'number' && Number.isFinite(value) && value >= 0
                ? (value * 100).toFixed(1) + '%' : '—';
        },
        metricLatency(value, requests) {
            return typeof requests === 'number' && requests > 0 && this.metricNum(value) !== '—'
                ? this.metricNum(value) + ' ms' : '—';
        },
        metricSampleState(requests, sufficient) {
            if (typeof requests !== 'number' || requests < 0) return '样本未知';
            if (requests === 0) return '无流量';
            if (sufficient === false || requests < (this.diagnosticVersionCheck.sampleThreshold || 5)) return '样本不足';
            return '有样本';
        },
        versionTrafficShare(row) {
            return this.diagnosticRequests > 0 ? this.metricPercent(row.windowRequests / this.diagnosticRequests) : '—';
        },
        versionConfiguredShare(row) {
            const targets = (this.routeMetrics && this.routeMetrics.targets) || [];
            const totalWeight = targets.reduce((sum, target) => sum + Math.max(0, Number(target.weight) || 0), 0);
            return row.declared && typeof row.weight === 'number' && totalWeight > 0
                ? this.metricPercent(row.weight / totalWeight) : '—';
        },
        versionLabel(group) { return group || '默认组（无版本）'; },
    },
};
