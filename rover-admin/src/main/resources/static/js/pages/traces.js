/** 请求追踪页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.traces = {
    data() {
        return {
            traces: [],
            tracesMeta: null,
            tracesError: null,
            tracesLoading: false,
            tracesRequest: 0,
            tracesQuery: null,
            traceFilter: { traceId: '', path: '', slow: false },
            selectedTrace: null,
        };
    },

    computed: {
        traceQueryChanged() {
            if (!this.tracesQuery) return false;
            return Object.keys(this.traceFilter).some(key => (this.traceFilter[key] || '') !== (this.tracesQuery[key] || ''));
        },
    },

    methods: {
        async fetchTraces(force = false) {
            if (this.tracesLoading && !force) return;
            const request = ++this.tracesRequest;
            this.tracesLoading = true;
            this.tracesError = null;
            const filters = force || !this.tracesQuery ? { ...this.traceFilter } : { ...this.tracesQuery };
            const params = new URLSearchParams();
            const traceId = filters.traceId.trim();
            const path = filters.path.trim();
            if (traceId) params.set('traceId', traceId);
            if (path) params.set('path', path);
            if (filters.slow) params.set('slow', 'true');
            const qs = params.toString() ? ('?' + params.toString()) : '';
            try {
                const data = await RoverAdminApi.api('/api/traces' + qs);
                if (request !== this.tracesRequest) return;
                this.tracesMeta = {
                    count: data.count,
                    capacity: data.capacity,
                    sampleRate: data.sampleRate,
                    slowThresholdMillis: data.slowThresholdMillis,
                };
                this.traces = data.traces || [];
                this.tracesQuery = filters;
                if (this.selectedTrace
                    && !this.traces.some(t => t.traceId === this.selectedTrace.traceId)) {
                    this.selectedTrace = null;
                }
            } catch (e) {
                if (request === this.tracesRequest) this.tracesError = e.message;
            } finally {
                if (request === this.tracesRequest) this.tracesLoading = false;
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
    },
};
