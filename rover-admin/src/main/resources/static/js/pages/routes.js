/** 路由管理页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.routes = {
    data() {
        return {
            routes: [],
            routesError: null,
            savingRoute: false,
            editingPrefix: null,
            routeDrawerOpen: false,
            routeForm: { id: '', businessPrefix: '', serviceName: '', targetUrl: '', targetUrls: '', group: '', stripPrefix: '' },
        };
    },

    computed: {},

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
                const result = await RoverAdminApi.api('/api/routes', {
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
                const result = await RoverAdminApi.api('/api/routes?businessPrefix=' + encodeURIComponent(prefix), { method: 'DELETE' });
                this.toast('success', result.message || '路由已删除');
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
            }
        },
    },
};
