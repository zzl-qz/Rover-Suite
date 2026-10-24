/** 配置管理页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.configs = {
    data() {
        return {
            configs: [],
            configsError: null,
            configBusyKey: null,
            configsRequest: 0,
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
            const request = ++this.configsRequest;
            this.configsError = null;
            try {
                const rows = await RoverAdminApi.api('/api/configs');
                // 保存或手动刷新并发时，只应用最后发起的读取，避免旧快照回滚新状态。
                if (request !== this.configsRequest) return;
                const previous = new Map(this.configs.map(c => [`${c.component}:${c.key}`, c]));
                this.configs = (rows || []).map(c => {
                    const old = previous.get(`${c.component}:${c.key}`);
                    return {
                        ...c,
                        // Refresh the saved baseline without discarding edits to other rows.
                        value: old && this.isDirty(old) ? old.value : c.value,
                        _saved: c.value,
                    };
                });
            } catch (e) {
                if (request === this.configsRequest) this.configsError = e.message;
            }
        },
        isDirty(c) {
            return String(c.value) !== String(c._saved);
        },
        isBoolConfig(c) {
            const opts = (c.options || []).map(v => this.optionValue(v).toLowerCase());
            return opts.length === 2 && opts.includes('true') && opts.includes('false');
        },
        optionValue(opt) {
            if (opt && typeof opt === 'object' && opt.value !== undefined) {
                return String(opt.value);
            }
            return String(opt);
        },
        optionLabel(c, opt) {
            if (opt && typeof opt === 'object' && opt.label !== undefined) {
                return String(opt.label);
            }
            return this.configChipLabel(c, this.optionValue(opt));
        },
        isNumberConfig(c) {
            const key = c.key || '';
            // 只按末尾配置名判断；否则 loadbalance.strategy 中的 "rate" 会被误判为数字配置。
            if (/(millis|seconds|rate|timeout|interval|expire)$/i.test(key)) return true;
            return c.options && c.options.length && c.options.every(v => !Number.isNaN(Number(this.optionValue(v))));
        },
        isEnumConfig(c) {
            return c.options && c.options.length
                && !this.isBoolConfig(c)
                && !this.isNumberConfig(c);
        },
        selectOptions(c) {
            const options = (c.options || []).map(v => {
                if (v && typeof v === 'object' && v.value !== undefined) {
                    return { label: v.label != null ? String(v.label) : String(v.value), value: String(v.value) };
                }
                const value = String(v);
                return { label: this.configChipLabel(c, value), value };
            });
            const value = String(c.value || '');
            if (value && !options.some(opt => opt.value === value)) {
                return [{ label: this.configChipLabel(c, value), value }, ...options];
            }
            return options;
        },
        numberUnit(c) {
            if (/rateLimit\.permitsPerSecond$/i.test(c.key || '')) return '请求/秒';
            if (/rateLimit\.(burst|limit)$/i.test(c.key || '')) return '请求';
            if (/failureThreshold$/i.test(c.key || '')) return '次';
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
            if ((c.key || '').endsWith('circuitBreaker.recovery')) {
                if (String(opt) === 'all') return '到期全开';
                if (String(opt) === 'half') return '只发一个探测';
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
            if (this.configBusyKey) return;
            const submittedValue = String(c.value);
            this.configBusyKey = `${c.component}:${c.key}`;
            try {
                const result = await RoverAdminApi.api('/api/configs', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ component: c.component, key: c.key, value: submittedValue }),
                });
                // Make this row clean at the submitted value; newer edits made while saving stay dirty.
                c._saved = submittedValue;
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
