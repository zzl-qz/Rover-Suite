/** 请求追踪页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.traces = {
    data() {
        return {
            traces: [],
            tracesMeta: null,
            tracesError: null,
            traceFilter: { traceId: '', path: '', slow: false },
            selectedTrace: null,
        };
    },

    computed: {},

    methods: {
        async fetchTraces() {
            this.tracesError = null;
            const params = new URLSearchParams();
            if (this.traceFilter.traceId) params.set('traceId', this.traceFilter.traceId);
            if (this.traceFilter.path) params.set('path', this.traceFilter.path);
            if (this.traceFilter.slow) params.set('slow', 'true');
            const qs = params.toString() ? ('?' + params.toString()) : '';
            try {
                const data = await RoverAdminApi.api('/api/traces' + qs);
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
    },
};
