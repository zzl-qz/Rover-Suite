/** 路由管理页。 */
window.RoverAdminPages = window.RoverAdminPages || {};
/** 与网关 ServiceInstance.DEFAULT_WEIGHT 一致：静态地址不写 |权重 时按这个值分流。 */
const DEFAULT_STATIC_WEIGHT = 100;
const EMPTY_ROUTE_FORM = {
    id: '', businessPrefix: '',
    // 静态地址一行一台机器；weight 为空串表示不指定，网关按默认权重算
    staticTargets: [],
    // 原路由只写了单个 targetUrl 时，保存仍写回 targetUrl，避免预览里冒出无意义的差异
    keepSingleTargetUrl: false,
    targets: [], stickyHeader: '', stripPrefix: '',
    upstreamKind: 'discovery',
};
window.RoverAdminPages.routes = {
    data() {
        return {
            routes: [],
            // 读路由表时一起拿到的版本号；提交时必须原样回传，网关用它判并发冲突
            routesRevision: 0,
            routesError: null,
            savingRoute: false,
            previewingRoute: false,
            // 网关返回的预览差异；为空表示还没预览过，供抽屉里的结果区渲染
            routePreview: null,
            editingPrefix: null,
            routeDrawerOpen: false,
            routeForm: { ...EMPTY_ROUTE_FORM },
            // 回滚目标版本号：网关只保留最近 5 次已应用快照，越界的版本号会被明确拒绝
            rollbackRevision: '',
        };
    },

    computed: {
        routeUpstreamOptions() {
            return [
                { label: '注册中心（按版本分流）', value: 'discovery' },
                { label: '静态地址', value: 'static' },
            ];
        },
        showDiscoveryFields() {
            return this.dynamicDiscovery && this.routeForm.upstreamKind === 'discovery';
        },
        showStaticFields() {
            return !this.dynamicDiscovery || this.routeForm.upstreamKind === 'static';
        },
        /** 权重总和，用于把权重换算成真实流量百分比。 */
        targetWeightTotal() {
            return this.routeForm.targets.reduce((sum, t) => sum + weightOf(t), 0);
        },
        staticWeightTotal() {
            return this.routeForm.staticTargets
                .filter(t => String(t.url || '').trim())
                .reduce((sum, t) => sum + staticWeightOf(t), 0);
        },
    },

    methods: {
        async fetchRoutes() {
            this.routesError = null;
            try {
                const state = await RoverAdminApi.api('/api/routes');
                this.routes = state.routes || [];
                this.routesRevision = state.revision || 0;
            } catch (e) {
                this.routesError = e.message;
            }
        },
        resetRouteForm() {
            this.routeForm = {
                ...EMPTY_ROUTE_FORM,
                targets: [],
                staticTargets: [],
                upstreamKind: this.dynamicDiscovery ? 'discovery' : 'static',
            };
            this.editingPrefix = null;
            this.routePreview = null;
        },
        openRouteDrawer(route) {
            if (route) {
                this.editingPrefix = route.businessPrefix;
                const hasStatic = Boolean(route.targetUrl || route.targetUrls);
                const targets = (route.targets || []).map(t => ({
                    serviceName: t.serviceName || '',
                    group: t.group || '',
                    weight: weightOf(t),
                }));
                const staticTargets = parseStaticTargets(route);
                this.routeForm = {
                    id: route.id || '',
                    businessPrefix: route.businessPrefix || '',
                    staticTargets: staticTargets.length ? staticTargets : [{ url: '', weight: '' }],
                    keepSingleTargetUrl: Boolean(route.targetUrl) && !route.targetUrls,
                    targets: targets.length ? targets : [{ serviceName: '', group: '', weight: 100 }],
                    stickyHeader: route.stickyHeader || '',
                    stripPrefix: route.stripPrefix || '',
                    upstreamKind: hasStatic ? 'static' : (this.dynamicDiscovery ? 'discovery' : 'static'),
                };
            } else {
                this.resetRouteForm();
                this.routeForm.targets = [{ serviceName: '', group: '', weight: 100 }];
                this.routeForm.staticTargets = [{ url: '', weight: '' }];
            }
            this.routePreview = null;
            this.rollbackRevision = '';
            this.routeDrawerOpen = true;
        },
        closeRouteDrawer() {
            this.routeDrawerOpen = false;
            this.resetRouteForm();
        },
        addTarget() {
            this.routeForm.targets.push({ serviceName: '', group: '', weight: 0 });
        },
        removeTarget(index) {
            if (this.routeForm.targets.length <= 1) {
                this.toast('error', '至少保留一个分组');
                return;
            }
            this.routeForm.targets.splice(index, 1);
        },
        addStaticTarget() {
            this.routeForm.staticTargets.push({ url: '', weight: '' });
        },
        removeStaticTarget(index) {
            if (this.routeForm.staticTargets.length <= 1) {
                this.toast('error', '至少保留一个地址');
                return;
            }
            this.routeForm.staticTargets.splice(index, 1);
        },
        /** 静态地址的实际流量占比；没填地址的空行不参与计算。 */
        staticShare(target) {
            const total = this.staticWeightTotal;
            if (!total || !String(target.url || '').trim()) return '-';
            return (staticWeightOf(target) / total * 100).toFixed(1) + '%';
        },
        /** 单个版本的实际流量占比，让「权重」看起来是可信的放量刻度。 */
        targetShare(target) {
            const total = this.targetWeightTotal;
            if (!total) return '0%';
            return (weightOf(target) / total * 100).toFixed(1) + '%';
        },
        /** 只填 serviceName 的第二列及以后的目标，用来提示「同服务不同版本」。 */
        serviceNameHint() {
            const first = this.routeForm.targets.find(t => t.serviceName);
            return first ? first.serviceName : 'demo-service';
        },
        /** 列表里每个分组一行：分组名、权重、在同一服务内的实际占比。 */
        routeTargetRows(route) {
            const targets = (route && route.targets) || [];
            const total = targets.reduce((sum, t) => sum + weightOf(t), 0);
            return targets.map(t => ({
                group: t.group || '',
                // 空分组在 nameserver 里是通配组：该服务所有分组的实例都算，不是「没分组的实例」
                groupLabel: t.group ? `分组 ${t.group}` : '不限分组（全部实例）',
                weight: weightOf(t),
                share: total ? (weightOf(t) / total * 100).toFixed(1) + '%' : '0%',
            }));
        },
        /** 列表里静态地址一台一行：地址、权重（标出是否默认）、占比。 */
        routeStaticRows(route) {
            const rows = parseStaticTargets(route);
            const total = rows.reduce((sum, t) => sum + staticWeightOf(t), 0);
            return rows.map(t => ({
                url: t.url,
                weight: staticWeightOf(t),
                isDefault: t.weight === '',
                share: total ? (staticWeightOf(t) / total * 100).toFixed(1) + '%' : '0%',
            }));
        },
        /** 列表里的服务名：取第一个版本目标（校验保证同一条路由只有一个服务）。 */
        routeServiceName(route) {
            const targets = route && route.targets;
            return targets && targets.length ? (targets[0].serviceName || '') : '';
        },
        /**
         * 校验抽屉表单并组装成一条网关认识的路由对象（不带 revision）。
         *
         * <p>「保存」和「预览差异」必须走同一套校验与字段组装：否则会出现「预览说没问题、
         * 保存却被网关拒绝」，或者反过来预览的内容跟真正提交的不是同一条路由。
         * 校验不过时就地 toast 并返回 null，由调用方决定要不要继续。
         */
        buildRoutePayload() {
            const form = this.routeForm;
            if (!form.businessPrefix) {
                this.toast('error', 'businessPrefix 必填');
                return null;
            }
            const payload = {
                id: form.id,
                businessPrefix: form.businessPrefix,
                stripPrefix: form.stripPrefix,
                targetUrl: '',
                targetUrls: '',
                targets: [],
                stickyHeader: '',
            };
            if (this.showDiscoveryFields) {
                const targets = form.targets
                    .filter(t => t.serviceName && String(t.serviceName).trim())
                    .map(t => ({
                        serviceName: String(t.serviceName).trim(),
                        group: String(t.group || '').trim(),
                        weight: weightOf(t),
                    }));
                if (!targets.length) {
                    this.toast('error', '至少填一个版本目标的服务名');
                    return null;
                }
                const names = new Set(targets.map(t => t.serviceName));
                if (names.size > 1) {
                    this.toast('error', '同一条路由的版本目标必须属于同一个服务，只区分 group');
                    return null;
                }
                if (!targets.some(t => t.weight > 0)) {
                    this.toast('error', '至少一个版本权重大于 0，否则这条路由没有版本可接流');
                    return null;
                }
                payload.targets = targets;
                payload.stickyHeader = String(form.stickyHeader || '').trim();
            } else {
                const rows = form.staticTargets
                    .map(t => ({ url: String(t.url || '').trim(), weight: String(t.weight ?? '').trim() }))
                    .filter(t => t.url);
                if (!rows.length) {
                    this.toast('error', '请至少填一个静态地址');
                    return null;
                }
                const badUrl = rows.find(t => !/^https?:\/\/[^\s,|]+$/i.test(t.url));
                if (badUrl) {
                    this.toast('error', `地址格式不对：${badUrl.url}，要写成 http://主机:端口`);
                    return null;
                }
                const badWeight = rows.find(t => t.weight && !/^[1-9]\d*$/.test(t.weight));
                if (badWeight) {
                    this.toast('error', `${badWeight.url} 的权重要填正整数，或者留空按 ${DEFAULT_STATIC_WEIGHT} 算`);
                    return null;
                }
                // 网关的 targetUrls 是逗号拼接串，单项写成 地址|权重，不写权重即默认
                const joined = rows.map(t => (t.weight ? `${t.url}|${t.weight}` : t.url));
                if (form.keepSingleTargetUrl && rows.length === 1 && !rows[0].weight) {
                    payload.targetUrl = joined[0];
                } else {
                    payload.targetUrls = joined.join(',');
                }
            }
            return payload;
        },
        async saveRoute() {
            const route = this.buildRoutePayload();
            if (!route) return;
            const operationId = newOperationId();
            const payload = { revision: this.routesRevision, operationId, ...route };
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
                // 冲突或超时后本地快照都可能已经过期，立刻刷新，避免用户反复提交同一个旧版本
                if (await this.recoverWriteOutcome(operationId, e)
                        || String(e.message || '').includes('版本冲突')) {
                    await this.fetchRoutes();
                }
            } finally {
                this.savingRoute = false;
            }
        },
        /**
         * 预览当前表单改动：只回差异，不落盘、不生效。
         *
         * <p>网关按整表比对，所以要拿「未改动的其它路由 + 本表单」拼成候选整表，否则别的路由
         * 会被误报成「删除」。被编辑的那条按 id（没有 id 才用 businessPrefix）替换，与网关的
         * 路由标识口径保持一致。网关不可达时同样落到 catch，靠 finally 摘掉 loading。
         */
        async previewRoute() {
            const candidate = this.buildRoutePayload();
            if (!candidate) return;
            this.previewingRoute = true;
            try {
                // 先拉一次最新路由表：拿过期的本地快照拼候选整表，会把别人刚改的增删算成
                // 「我的差异」；顺带把 revision 刷到最新，预览通过后再保存也不容易撞 409
                await this.fetchRoutes();
                const key = r => (r.id ? 'id:' + r.id : 'prefix:' + (r.businessPrefix || ''));
                const candidateKey = key(candidate);
                const merged = this.routes
                    .filter(r => key(r) !== candidateKey)
                    .map(r => ({ ...r }));
                merged.push(candidate);
                const result = await RoverAdminApi.api('/api/routes/preview', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ routes: merged }),
                });
                this.routePreview = result;
                if (result.changeCount) {
                    this.toast('success', result.message || `预览通过，共 ${result.changeCount} 处差异`);
                } else {
                    this.toast('success', '与当前版本一致，无差异');
                }
            } catch (e) {
                this.routePreview = null;
                this.toast('error', e.message);
            } finally {
                this.previewingRoute = false;
            }
        },
        /** 预览差异类型的中文名，网关回的是 ADDED / REMOVED / MODIFIED。 */
        changeKindLabel(kind) {
            return { ADDED: '新增', REMOVED: '删除', MODIFIED: '修改' }[kind] || kind;
        },
        async deleteRoute(prefix) {
            if (!confirm(`确定删除路由 ${prefix} 吗？`)) return;
            const operationId = newOperationId();
            try {
                const query = '?businessPrefix=' + encodeURIComponent(prefix)
                    + '&revision=' + this.routesRevision
                    + '&operationId=' + encodeURIComponent(operationId);
                const result = await RoverAdminApi.api('/api/routes' + query, { method: 'DELETE' });
                this.toast('success', result.message || '路由已删除');
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
                if (await this.recoverWriteOutcome(operationId, e)
                        || String(e.message || '').includes('版本冲突')) {
                    await this.fetchRoutes();
                }
            }
        },
        /**
         * 只调整一个版本的权重：灰度放量 / 停推的专用通道。
         *
         * 与「保存整条路由」的区别是它走网关收窄的原语——只改这一个版本，不会误动同一条路由上的
         * 其它目标；乐观锁（revision）与超时回查（operationId）则与保存路由完全一致，
         * 所以写失败后的对账逻辑可以直接复用。
         */
        async applyTargetWeight(target) {
            if (!target || !String(target.serviceName || '').trim()) {
                this.toast('error', '版本目标缺少服务名');
                return;
            }
            const weight = weightOf(target);
            const operationId = newOperationId();
            const payload = {
                routeId: this.routeForm.id || this.routeForm.businessPrefix,
                serviceName: String(target.serviceName).trim(),
                group: String(target.group || '').trim(),
                weight,
                revision: this.routesRevision,
                operationId,
            };
            this.savingRoute = true;
            try {
                const result = await RoverAdminApi.api('/api/routes/targets/weight', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload),
                });
                this.toast('success',
                    result.message || `${payload.group ? '分组 ' + payload.group : '不限分组'} 权重已调整为 ${weight}`);
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
                if (await this.recoverWriteOutcome(operationId, e)
                        || String(e.message || '').includes('版本冲突')) {
                    await this.fetchRoutes();
                }
            } finally {
                this.savingRoute = false;
            }
        },
        /** 把某个版本一次性停推：权重归零，等价于一键摘掉这个灰度版本。 */
        stopTarget(target) {
            if (!target) return;
            target.weight = 0;
            return this.applyTargetWeight(target);
        },
        /**
         * 回滚路由表到某个历史版本。
         *
         * 网关以「产生新版本」的方式回滚（不覆盖历史），且只保留最近 5 次已应用快照——
         * 不在窗口内的版本号会被网关明确拒绝，这里把原因原样透给操作者，不自己猜能不能回滚。
         */
        async rollbackRoutes() {
            const toRevision = Number(this.rollbackRevision);
            if (!Number.isInteger(toRevision) || toRevision <= 0) {
                this.toast('error', '请填写要回滚到的版本号（正整数）');
                return;
            }
            if (!confirm(`确定把路由表回滚到版本 ${toRevision} 吗？这会以「新版本」的形式生效。`)) return;
            const operationId = newOperationId();
            const payload = { toRevision, revision: this.routesRevision, operationId };
            this.savingRoute = true;
            try {
                const result = await RoverAdminApi.api('/api/routes/rollback', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload),
                });
                this.toast('success', result.message || `已回滚到版本 ${toRevision}`);
                this.rollbackRevision = '';
                await this.fetchRoutes();
            } catch (e) {
                this.toast('error', e.message);
                if (await this.recoverWriteOutcome(operationId, e)
                        || String(e.message || '').includes('版本冲突')) {
                    await this.fetchRoutes();
                }
            } finally {
                this.savingRoute = false;
            }
        },
        /**
         * 写请求失败后的对账：用同一个 operationId 问网关「这次写到底执行了没有」。
         *
         * <p>Admin 调网关有 5 秒超时，超时的响应可能已经落盘、也可能没有；靠重提是猜，
         * 而重提可能真的多改一次。网关保留了操作记录，所以每次写都自带 operationId，
         * 超时/下游 5xx 后立刻按号回查，把真实终态告诉操作者。
         *
         * @returns {boolean} 是否已经回查到了终态（false 表示还需按普通失败处理）
         */
        async recoverWriteOutcome(operationId, error) {
            // 4xx 是网关明确拒绝（校验失败/版本冲突），状态已经确定，不必回查
            if (error && error.status >= 400 && error.status < 500) {
                return false;
            }
            let record;
            try {
                record = await RoverAdminApi.api('/api/routes/operations/' + encodeURIComponent(operationId));
            } catch (queryError) {
                this.toast('error',
                    `提交结果未知，且回查失败：${queryError.message}。可用操作号 ${operationId} 稍后再查`);
                return false;
            }
            const label = {
                APPLIED: '已生效',
                CONFLICT: '未生效（版本冲突）',
                REJECTED: '未生效（校验失败）',
                FAILED: '未生效（落盘失败）',
                UNKNOWN: '网关没有这条记录',
            }[record.status] || record.status;
            const message = `提交结果未知，已按操作号回查：${label}。${record.message || ''}`;
            if (record.status === 'APPLIED') {
                this.toast('success', message);
            } else {
                this.toast('error', message);
            }
            return true;
        },
    },
};

/** 生成一次路由写操作的幂等号：超时后靠它回查，因此必须由发起方生成。 */
function newOperationId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return window.crypto.randomUUID();
    }
    return 'op-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}

/**
 * 把路由上的 targetUrl + targetUrls 拆成「一台机器一行」。
 * 与网关解析口径一致：取最后一个 | 后面的纯数字当权重，否则整段都是地址、权重留空。
 */
function parseStaticTargets(route) {
    if (!route) return [];
    const raws = [route.targetUrl || '', ...String(route.targetUrls || '').split(',')]
        .map(s => s.trim())
        .filter(Boolean);
    return raws.map(raw => {
        const bar = raw.lastIndexOf('|');
        const tail = bar > 0 ? raw.slice(bar + 1).trim() : '';
        if (tail && /^\d+$/.test(tail)) {
            return { url: raw.slice(0, bar).trim(), weight: String(Math.max(1, Number(tail))) };
        }
        return { url: raw, weight: '' };
    });
}

/** 静态地址的生效权重：没填按默认 100，网关会把 0 抬成 1，这里同样处理。 */
function staticWeightOf(target) {
    const text = String((target && target.weight) ?? '').trim();
    if (!text) return DEFAULT_STATIC_WEIGHT;
    const value = Math.floor(Number(text));
    return Number.isFinite(value) ? Math.max(1, value) : DEFAULT_STATIC_WEIGHT;
}

/** 权重取整，坏值按 0 处理，避免把 NaN 提交给网关。 */
function weightOf(target) {
    const value = Number(target && target.weight);
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}
