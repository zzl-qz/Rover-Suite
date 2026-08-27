/** 路由管理页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
const EMPTY_ROUTE_FORM = {
    id: '', businessPrefix: '', serviceName: '', targetUrl: '', targetUrls: '', group: '', stripPrefix: '',
    upstreamKind: 'discovery',
};
window.RoverAdminPages.routes = {
    data() {
        return {
            routes: [],
            routesError: null,
            savingRoute: false,
            editingPrefix: null,
            routeDrawerOpen: false,
            routeForm: { ...EMPTY_ROUTE_FORM },
        };
    },

    computed: {
        routeUpstreamOptions() {
            return [
                { label: '注册中心（服务名）', value: 'discovery' },
                { label: '静态地址', value: 'static' },
            ];
        },
        showDiscoveryFields() {
            return this.dynamicDiscovery && this.routeForm.upstreamKind === 'discovery';
        },
        showStaticFields() {
            return !this.dynamicDiscovery || this.routeForm.upstreamKind === 'static';
        },
    },

    methods: {
        async fetchRoutes() {
            this.routesError = null;
            try {
                this.routes = await RoverAdminApi.api('/api/routes');
            } catch (e) {
                this.routesError = e.message;
            }
        },
        resetRouteForm() {
            this.routeForm = {
                ...EMPTY_ROUTE_FORM,
                upstreamKind: this.dynamicDiscovery ? 'discovery' : 'static',
            };
            this.editingPrefix = null;
        },
        openRouteDrawer(route) {
            if (route) {
                this.editingPrefix = route.businessPrefix;
                const hasStatic = Boolean(route.targetUrl || route.targetUrls);
                this.routeForm = {
                    id: route.id || '',
                    businessPrefix: route.businessPrefix || '',
                    serviceName: route.serviceName || '',
                    targetUrl: route.targetUrl || '',
                    targetUrls: route.targetUrls || '',
                    group: route.group || '',
                    stripPrefix: route.stripPrefix || '',
                    upstreamKind: hasStatic ? 'static' : (this.dynamicDiscovery ? 'discovery' : 'static'),
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
            const payload = {
                id: form.id,
                businessPrefix: form.businessPrefix,
                stripPrefix: form.stripPrefix,
                group: form.group,
                serviceName: '',
                targetUrl: '',
                targetUrls: '',
            };
            if (this.showDiscoveryFields) {
                if (!form.serviceName) {
                    this.toast('error', '请填服务名');
                    return;
                }
                payload.serviceName = form.serviceName;
                payload.group = form.group;
            } else {
                if (!form.targetUrl && !form.targetUrls) {
                    this.toast('error', '请填静态地址');
                    return;
                }
                payload.group = '';
                payload.targetUrl = form.targetUrl;
                payload.targetUrls = form.targetUrls;
            }
            this.savingRoute = true;
            try {
                const result = await RoverAdminApi.api('/api/routes', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload),
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
                const result = await RoverAdminApi.api('/api/routes?businessPrefix=' + encodeURIComponent(prefix), { method: 'DELETE' });
                this.toast('success', result.message || '路由已删除');
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
            }
        },
    },
};
