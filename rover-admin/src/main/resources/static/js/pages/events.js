/** 最近事件页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.events = {
    data() {
        return {
            events: [],
            eventsError: null,
            knownEventKeys: new Set(),
            eventFilter: { type: '', service: '' },
            eventPage: 1,
            eventPageSize: 20,
        };
    },

    computed: {
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

    methods: {
        async fetchEvents() {
            this.eventsError = null;
            try {
                const rows = await RoverAdminApi.api('/api/events');
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
    },
};
