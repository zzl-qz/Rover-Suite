/** 跨页共用：导航、探活、toast、格式化、切页刷新。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.shared = {
    data() {
        const ICONS = RoverAdminIcons.ICONS;
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
            updatedAt: 0,
            envOpen: true,
            discoveryType: 'UNKNOWN',
            status: { gateway: null, nameserver: null },
            metricsError: null,
            selfcheck: null,
            toasts: [],
            toastSeq: 0,
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
        dynamicDiscovery() {
            const type = this.discoveryType;
            return Boolean(type) && type !== 'STATIC' && type !== 'UNKNOWN';
        },
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
        ...RoverAdminFormatters,
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
        async fetchOverview() {
            try {
                const overview = await RoverAdminApi.api('/api/overview');
                this.status = overview.status || { gateway: null, nameserver: null };
                this.discoveryType = overview.discoveryType || 'UNKNOWN';
                this.selfcheck = overview.selfcheck && overview.selfcheck.error ? null : overview.selfcheck;
            } catch (e) {
                // overview 失败不影响 live
            }
        },
    },
};
