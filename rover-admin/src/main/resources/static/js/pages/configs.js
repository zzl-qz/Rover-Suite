/** 配置管理页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.configs = {
    data() {
        return {
            configs: [],
            configsError: null,
            configBusyKey: null,
        };
    },

    computed: {
        configGroups() {
            const gateway = this.configs.filter(c => c.component === 'gateway');
            const nameserver = this.configs.filter(c => c.component === 'nameserver');
            return [
                { component: 'gateway', title: 'Gateway', items: gateway },
                { component: 'nameserver', title: 'Nameserver', items: nameserver },
            ];
        },
    },

    methods: {
        async fetchConfigs() {
            this.configsError = null;
            try {
                const rows = await RoverAdminApi.api('/api/configs');
                this.configs = (rows || []).map(c => {
                    c._saved = c.value;
                    return c;
                });
            } catch (e) {
                this.configsError = e.message;
            }
        },
        isDirty(c) {
            return String(c.value) !== String(c._saved);
        },
        isBoolConfig(c) {
            const opts = (c.options || []).map(v => String(v).toLowerCase());
            return opts.length === 2 && opts.includes('true') && opts.includes('false');
        },
        isNumberConfig(c) {
            const key = c.key || '';
            // 只按末尾配置名判断；否则 loadbalance.strategy 中的 "rate" 会被误判为数字配置。
            if (/(millis|seconds|rate|timeout|interval|expire)$/i.test(key)) return true;
            return c.options && c.options.length && c.options.every(v => !Number.isNaN(Number(v)));
        },
        isEnumConfig(c) {
            return c.options && c.options.length
                && !this.isBoolConfig(c)
                && !this.isNumberConfig(c);
        },
        selectOptions(c) {
            const options = (c.options || []).map(v => String(v));
            const value = String(c.value || '');
            if (value && !options.includes(value)) {
                return [value, ...options];
            }
            return options;
        },
        numberUnit(c) {
            if (/rateLimit\.permitsPerSecond$/i.test(c.key || '')) return '请求/秒';
            if (/rateLimit\.(burst|limit)$/i.test(c.key || '')) return '请求';
            if (/rate/i.test(c.key || '')) return '';
            if (/seconds/i.test(c.key || '')) return '秒';
            return 'ms';
        },
        numberStep(c) {
            return /rate/i.test(c.key || '') ? '0.01' : '1';
        },
        numberMin(c) {
            return /rate/i.test(c.key || '') ? '0' : '1';
        },
        numberMax(c) {
            return /rate/i.test(c.key || '') ? '1' : undefined;
        },
        /** 采样率快捷按钮用白话，别只扔 0/1。 */
        configChipLabel(c, opt) {
            if ((c.key || '').endsWith('sampleRate')) {
                if (String(opt) === '0') return '只记慢请求';
                if (String(opt) === '1') return '全量记录';
            }
            return opt;
        },
        /** 采样率只给两个常用档；中间值可手输。 */
        configQuickOptions(c) {
            if ((c.key || '').endsWith('sampleRate')) return ['0', '1'];
            return c.options || [];
        },
        revertConfig(c) {
            c.value = c._saved;
        },
        async updateConfig(c) {
            this.configBusyKey = c.key;
            try {
                const result = await RoverAdminApi.api('/api/configs', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ component: c.component, key: c.key, value: String(c.value) }),
                });
                this.toast('success', `${c.component}.${c.key} 已更新：${result.message || ''}`);
                await this.fetchConfigs();
            } catch (e) {
                this.toast('error', e.message);
            } finally {
                this.configBusyKey = null;
            }
        },
    },
};
