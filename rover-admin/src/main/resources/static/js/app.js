/**
 * Rover Admin：Vue 3 + ECharts。
 * 仪表盘可视时 1 秒拉 /api/live，15 秒拉 overview；非仪表盘/后台标签页不拉 live。
 * 轮询绝不打整页 loading。
 * 页面逻辑在 pages/*.js，这里只做拼装。
 */
const { createApp } = Vue;

const P = RoverAdminPages;

createApp({
    data() {
        return Object.assign(
            {},
            P.shared.data(),
            P.dashboard.data(),
            P.metrics.data(),
            P.workbench.data(),
            P.traces.data(),
            P.routes.data(),
            P.instances.data(),
            P.events.data(),
            P.configs.data(),
            P.model.data(),
        );
    },

    computed: Object.assign(
        {},
        P.shared.computed,
        P.dashboard.computed,
        P.metrics.computed,
        P.workbench.computed,
        P.traces.computed,
        P.routes.computed,
        P.instances.computed,
        P.events.computed,
        P.configs.computed,
        P.model.computed,
    ),

    // 目前只有工作台需要 watch（对话窗口的自动滚动）；其余页面按需再往这里加。
    watch: Object.assign({}, P.workbench.watch),

    mounted() {
        this.$nextTick(() => this.initCharts());
        this.fetchAuthStatus();
        this.fetchOverview();
        this.fetchLive();
        // live 只服务仪表盘可视时；其它页靠 overview 探活，避免观察税随页面/标签页空转
        this.liveTimer = setInterval(() => this.fetchLive(), 1000);
        this.overviewTimer = setInterval(() => this.fetchOverview(), 15000);
        this.pageTimer = setInterval(() => this.pollCurrentPage(), 3000);
        this.metricsTimer = setInterval(() => this.refreshMetrics(), 10000);
        this._resizeHandler = () => this.resizeCharts();
        this._visibilityHandler = () => {
            if (!document.hidden && this.page === 'dashboard') this.fetchLive();
            if (!document.hidden && this.page === 'metrics') this.refreshMetrics();
            if (!document.hidden && this.page === 'workbench') this.wbPollSession();
        };
        window.addEventListener('resize', this._resizeHandler);
        document.addEventListener('visibilitychange', this._visibilityHandler);
    },

    beforeUnmount() {
        clearInterval(this.liveTimer);
        clearInterval(this.overviewTimer);
        clearInterval(this.pageTimer);
        clearInterval(this.metricsTimer);
        window.removeEventListener('resize', this._resizeHandler);
        document.removeEventListener('visibilitychange', this._visibilityHandler);
    },

    methods: Object.assign(
        {},
        P.shared.methods,
        P.dashboard.methods,
        P.metrics.methods,
        P.workbench.methods,
        P.traces.methods,
        P.routes.methods,
        P.instances.methods,
        P.events.methods,
        P.configs.methods,
        P.model.methods,
    ),
}).mount('#app');
