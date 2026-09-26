/**
 * Agent 工作台：把「一次路径诊断」升级成带会话上下文的连续调查。
 *
 * 三栏：左栏会话列表（内存存储，重启即清空），中栏对话与调查进度，右栏当前事件上下文。
 * 数据全部来自 Agent 接口——会话详情给出消息与当前事件，任务详情给出步骤/证据/结论；
 * 「AI 解读」增量走 SSE，任务状态仍由轮询兜底，断线不影响调查本身。
 *
 * 用户身份由后端认证上下文决定，这里不提交也不展示 userId。
 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.workbench = {
    data() {
        return {
            wbSessions: [],
            wbActiveSessionId: '',
            wbSession: null,
            wbMessages: [],
            wbActiveIncident: null,
            wbIncidents: {},
            wbTasks: {},
            wbError: null,
            wbHint: null,
            wbLoading: false,
            wbInput: '',
            wbContext: { path: '', service: '', instance: '', from: '', to: '' },
            wbContextOpen: false,
            wbSending: false,
            wbDetailOpen: {},
            wbStreamTaskId: '',
            wbStreamText: '',
            wbStreaming: false,
        };
    },

    computed: {
        /** 左栏分组：今天 / 昨天 / 更早，组内按最近活动倒序。 */
        wbSessionGroups() {
            const startOfToday = new Date();
            startOfToday.setHours(0, 0, 0, 0);
            const todayStart = startOfToday.getTime();
            const groups = [
                { key: 'today', label: '今天', items: [] },
                { key: 'yesterday', label: '昨天', items: [] },
                { key: 'earlier', label: '更早', items: [] },
            ];
            const sorted = [...this.wbSessions].sort((a, b) => this.wbSessionAt(b) - this.wbSessionAt(a));
            sorted.forEach((session) => {
                const at = this.wbSessionAt(session);
                if (at >= todayStart) groups[0].items.push(session);
                else if (at >= todayStart - 86400000) groups[1].items.push(session);
                else groups[2].items.push(session);
            });
            return groups.filter(group => group.items.length);
        },

        /**
         * 中栏时间线：会话消息与事件下的调查任务按时间归并。
         *
         * 同一毫秒内先消息后任务卡片（提问 → 回复 → 调查进度），因此每轮问答的卡片都紧跟在它后面。
         * 任务字段只在任务详情里，所以这里只拼接已经取到的任务。
         */
        wbTurns() {
            const turns = [];
            this.wbMessages.forEach((message) => {
                turns.push({
                    kind: 'message',
                    key: 'm-' + message.messageId,
                    at: message.createdAtMillis || 0,
                    rank: 0,
                    message,
                });
            });
            Object.values(this.wbTasks).forEach((task) => {
                if (!task) return;
                turns.push({
                    kind: 'task',
                    key: 't-' + task.taskId,
                    at: task.createdAtMillis || 0,
                    rank: 1,
                    task,
                });
            });
            return turns.sort((a, b) => (a.at - b.at) || (a.rank - b.rank));
        },

        /** 当前事件下最近一次产出结论的任务，右栏「当前诊断」与「证据」都用它。 */
        wbLatestResultTask() {
            const incidentId = this.wbActiveIncident ? this.wbActiveIncident.incidentId : null;
            const tasks = Object.values(this.wbTasks)
                .filter(task => task && task.result && (!incidentId || task.incidentId === incidentId));
            if (!tasks.length) return null;
            return tasks.reduce((best, task) => (task.createdAtMillis >= best.createdAtMillis ? task : best));
        },
    },

    methods: {
        /** 进入工作台时调用：拉会话列表，必要时选中最近的会话。 */
        async wbRefresh() {
            await this.wbLoadSessions();
            const current = this.wbSessions.find(s => s.sessionId === this.wbActiveSessionId);
            if (current) {
                await this.wbLoadSession(current.sessionId);
                return;
            }
            this.wbCloseStream();
            this.wbActiveSessionId = '';
            this.wbSession = null;
            this.wbMessages = [];
            this.wbActiveIncident = null;
            this.wbIncidents = {};
            this.wbTasks = {};
            this.wbHint = null;
        },

        async wbLoadSessions() {
            try {
                this.wbSessions = (await RoverAdminApi.api('/api/agent/sessions')) || [];
            } catch (e) {
                this.wbError = '加载会话列表失败：' + e.message;
            }
        },

        /** 开一个空会话并返回 sessionId；左侧「新建会话」与「没有会话时直接提问」共用。 */
        async wbCreateSession() {
            const session = await RoverAdminApi.api('/api/agent/sessions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: '{}',
            });
            if (!session || !session.sessionId) throw new Error('服务端未返回会话 ID');
            return session.sessionId;
        },

        async wbNewSession() {
            if (this.wbSending) return;
            try {
                const sessionId = await this.wbCreateSession();
                await this.wbLoadSessions();
                await this.wbSwitchSession(sessionId);
                this.wbError = null;
            } catch (e) {
                this.wbError = '新建会话失败：' + e.message;
            }
        },

        async wbSwitchSession(sessionId) {
            if (!sessionId || sessionId === this.wbActiveSessionId) return;
            this.wbCloseStream();
            this.wbActiveSessionId = sessionId;
            this.wbSession = null;
            this.wbMessages = [];
            this.wbActiveIncident = null;
            this.wbIncidents = {};
            this.wbTasks = {};
            this.wbDetailOpen = {};
            this.wbHint = null;
            this.wbError = null;
            await this.wbLoadSession(sessionId);
        },

        /**
         * 载入一个会话的全部展示数据。
         *
         * 会话详情只带当前事件，所以再按会话的 incidentIds 补齐其它事件，才能把历史调查的任务卡片
         * 一起留在对话里。数量受会话自身的事件数限制（内存实现上限 100），代价可接受。
         */
        async wbLoadSession(sessionId) {
            if (!sessionId) return;
            this.wbLoading = true;
            try {
                const detail = await RoverAdminApi.api('/api/agent/sessions/' + encodeURIComponent(sessionId));
                if (this.wbActiveSessionId !== sessionId) return;
                this.wbSession = detail.session || null;
                this.wbMessages = detail.messages || [];
                this.wbActiveIncident = detail.activeIncident || null;
                const incidentIds = (this.wbSession && this.wbSession.incidentIds) || [];
                const loaded = await Promise.all(incidentIds.map((incidentId) => {
                    if (this.wbActiveIncident && incidentId === this.wbActiveIncident.incidentId) {
                        return Promise.resolve(this.wbActiveIncident);
                    }
                    return RoverAdminApi.api('/api/agent/incidents/' + encodeURIComponent(incidentId))
                        .catch(() => null);
                }));
                if (this.wbActiveSessionId !== sessionId) return;
                const incidents = {};
                loaded.filter(Boolean).forEach((incident) => { incidents[incident.incidentId] = incident; });
                if (this.wbActiveIncident) incidents[this.wbActiveIncident.incidentId] = this.wbActiveIncident;
                this.wbIncidents = incidents;
                const taskIds = [];
                Object.values(incidents).forEach((incident) => (incident.taskIds || []).forEach((taskId) => {
                    if (!taskIds.includes(taskId)) taskIds.push(taskId);
                }));
                const fetched = await Promise.all(taskIds.map(taskId =>
                    RoverAdminApi.api('/api/agent/tasks/' + encodeURIComponent(taskId)).catch(() => null)));
                if (this.wbActiveSessionId !== sessionId) return;
                const tasks = {};
                fetched.filter(Boolean).forEach((task) => { tasks[task.taskId] = task; });
                this.wbTasks = tasks;
                this.wbError = null;
            } catch (e) {
                if (this.wbActiveSessionId === sessionId) {
                    this.wbError = '加载会话失败：' + e.message;
                }
            } finally {
                this.wbLoading = false;
            }
        },

        /** 轮询未结束的调查任务；会话消息只在提问时变化，不必跟着轮询。 */
        async wbPollSession() {
            const ids = Object.values(this.wbTasks)
                .filter(task => task && !this.wbTaskTerminal(task.status))
                .map(task => task.taskId);
            if (!ids.length) return;
            const fetched = await Promise.all(ids.map(taskId =>
                RoverAdminApi.api('/api/agent/tasks/' + encodeURIComponent(taskId)).catch(() => null)));
            const tasks = Object.assign({}, this.wbTasks);
            let justFinished = false;
            fetched.filter(Boolean).forEach((task) => {
                const previous = tasks[task.taskId];
                if (previous && !this.wbTaskTerminal(previous.status) && this.wbTaskTerminal(task.status)) {
                    justFinished = true;
                }
                tasks[task.taskId] = task;
            });
            this.wbTasks = tasks;
            // 任务刚结束：事件摘要与状态在服务端已更新，重新载入才能让右栏跟上。
            if (justFinished && this.wbActiveSessionId) await this.wbLoadSession(this.wbActiveSessionId);
        },

        async wbSend() {
            const message = (this.wbInput || '').trim();
            if (!message || this.wbSending) return;
            const extra = this.wbRequestContext();
            if (extra === null) return;
            this.wbSending = true;
            this.wbError = null;
            this.wbHint = null;
            try {
                // 没有会话时直接提问：先开一个空会话再发，主入口就是这一个输入框。
                let sessionId = this.wbActiveSessionId;
                if (!sessionId) {
                    sessionId = await this.wbCreateSession();
                    this.wbActiveSessionId = sessionId;
                }
                const response = await RoverAdminApi.api(
                    '/api/agent/sessions/' + encodeURIComponent(sessionId) + '/messages',
                    {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(Object.assign({ message }, extra)),
                    });
                this.wbInput = '';
                if (response && response.clarification) this.wbHint = response.clarification;
                await this.wbLoadSession(sessionId);
                this.wbLoadSessions();
                if (response && response.task) this.wbOpenStream(response.task.taskId);
            } catch (e) {
                this.wbError = '提问失败：' + e.message;
            } finally {
                this.wbSending = false;
            }
        },

        /** 高级上下文：只把填了的项发给服务端；时间范围要么都填要么都不填。 */
        wbRequestContext() {
            const context = this.wbContext;
            const body = {};
            const path = (context.path || '').trim();
            const service = (context.service || '').trim();
            const instance = (context.instance || '').trim();
            if (path) body.path = path;
            else if (service) body.service = service;
            else if (instance) body.instance = instance;
            const from = this.wbLocalMillis(context.from);
            const to = this.wbLocalMillis(context.to);
            if (from === null && to === null) return body;
            if (from === null || to === null || to < from) {
                this.wbError = '时间范围要么都填、要么都不填，且结束不能早于开始。';
                return null;
            }
            body.fromMillis = from;
            body.toMillis = to;
            return body;
        },

        wbLocalMillis(value) {
            if (!value) return null;
            const millis = new Date(value).getTime();
            return Number.isNaN(millis) ? null : millis;
        },

        /**
         * 订阅「AI 解读」增量：只把模型输出实时贴到页面上。
         * 步骤与任务状态仍由轮询兜底，所以断开只是看不到实时文字，不影响调查本身。
         */
        wbOpenStream(taskId) {
            if (typeof EventSource === 'undefined') return;
            this.wbCloseStream();
            const source = new EventSource('/api/agent/diagnoses/' + encodeURIComponent(taskId) + '/stream');
            this._wbStream = source;
            this.wbStreamTaskId = taskId;
            this.wbStreamText = '';
            this.wbStreaming = true;
            source.addEventListener('snapshot', (event) => {
                if (this.wbStreamTaskId === taskId) this.wbStreamText = event.data;
            });
            source.addEventListener('delta', (event) => {
                if (this.wbStreamTaskId === taskId) this.wbStreamText += event.data;
            });
            source.addEventListener('end', () => {
                this.wbCloseStream();
                this.wbPollSession();
                this.wbLoadSession(this.wbActiveSessionId);
            });
            source.addEventListener('error', () => this.wbCloseStream());
        },

        wbCloseStream() {
            if (this._wbStream) {
                this._wbStream.close();
                this._wbStream = null;
            }
            this.wbStreaming = false;
        },

        /** 任务卡片的解读文本：结束后以落库全文为准，生成中看流式增量。 */
        wbAnalysisText(task) {
            const final = task && task.result && task.result.aiAnalysis;
            if (final) return final;
            if (this.wbStreaming && task && this.wbStreamTaskId === task.taskId) return this.wbStreamText;
            return '';
        },

        wbTaskTerminal(status) {
            return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status);
        },

        /** 固定四阶段进度：按步骤的真实状态打勾，未上报的阶段如实显示为未执行。 */
        wbProgress(task) {
            const stages = [
                { type: 'ROUTE_INVESTIGATION', label: '读取路由' },
                { type: 'INSTANCE_INVESTIGATION', label: '读取实例' },
                { type: 'METRIC_INVESTIGATION', label: '读取指标' },
                { type: 'TRACE_INVESTIGATION', label: '读取追踪' },
            ];
            const steps = (task && task.steps) || [];
            return stages.map((stage) => {
                const step = [...steps].reverse().find(item => item.type === stage.type);
                if (!step) {
                    return { type: stage.type, label: stage.label, state: 'pending', mark: '○', title: '该阶段未执行' };
                }
                if (step.status === 'COMPLETED') {
                    return { type: stage.type, label: stage.label, state: 'done', mark: '✓', title: step.outputSummary || '' };
                }
                if (step.status === 'FAILED') {
                    return { type: stage.type, label: stage.label, state: 'failed', mark: '!', title: step.error || '该阶段失败' };
                }
                return { type: stage.type, label: stage.label, state: 'running', mark: '●', title: step.inputSummary || '' };
            });
        },

        wbToggleDetail(taskId) {
            this.wbDetailOpen = Object.assign({}, this.wbDetailOpen, { [taskId]: !this.wbDetailOpen[taskId] });
        },

        wbSessionAt(session) {
            return session.lastActiveAtMillis || session.createdAtMillis || 0;
        },

        wbStatusLabel(status) {
            return {
                PENDING: '排队中', RUNNING: '调查中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
            }[status] || status || '等待中';
        },

        wbStatusBadge(status) {
            return { COMPLETED: 'ok', FAILED: 'bad', CANCELLED: 'warn', RUNNING: 'warn' }[status] || 'comp';
        },

        wbIncidentStatusLabel(status) {
            return { OPEN: '已登记', INVESTIGATING: '调查中', RESOLVED: '已结论' }[status] || status || '未知';
        },

        wbIncidentBadge(status) {
            return { RESOLVED: 'ok', INVESTIGATING: 'warn', OPEN: 'comp' }[status] || 'comp';
        },

        wbSeverityLabel(severity) {
            return { UNKNOWN: '未判定', LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' }[severity] || severity || '-';
        },

        wbOriginLabel(origin) {
            return { USER: '用户提问', ALERT: '告警', INSPECTION: '巡检' }[origin] || origin || '-';
        },

        wbTargetTypeLabel(type) {
            return { ROUTE: '路由', SERVICE: '服务', INSTANCE: '实例', UNKNOWN: '未确定' }[type] || type || '未确定';
        },

        wbEvidenceTypeLabel(type) {
            return {
                ROUTE: '路由', INSTANCE: '实例', METRIC: '指标', TRACE: '追踪',
                LOG: '日志', EVENT: '事件', CONFIG: '配置', KNOWLEDGE: '知识',
            }[type] || type || '-';
        },

        wbTimeRangeText(range) {
            if (!range || (!range.fromMillis && !range.toMillis)) return '默认窗口';
            return this.fmtTime(range.fromMillis) + ' ~ ' + this.fmtTime(range.toMillis);
        },

        wbConfidenceLabel(value) {
            return { HIGH: '高', MEDIUM: '中', LOW: '低', UNKNOWN: '未知' }[value] || value || '未标注';
        },

        wbHypothesisLabel(status) {
            return { CONFIRMED: '确认', REJECTED: '排除', UNKNOWN: '无法验证' }[status] || status || '未验证';
        },

        wbHypothesisBadge(status) {
            return { CONFIRMED: 'bad', REJECTED: 'ok', UNKNOWN: 'warn' }[status] || 'comp';
        },

        /** 「哪些判断仍不确定」的口径：只有 UNKNOWN 才算不确定，展开详情才能看到全部假设。 */
        wbUncertainties(task) {
            const hypotheses = (task && task.result && task.result.hypotheses) || [];
            return hypotheses.filter(item => item.status === 'UNKNOWN');
        },

        /** 不确定判断的一句话摘要，供默认视图直接展示，不必展开调查详情。 */
        wbUncertaintyText(task) {
            return this.wbUncertainties(task).map(item => item.statement).join('；');
        },

        wbStepTone(status) {
            return { COMPLETED: 'ok', FAILED: 'bad', RUNNING: 'warn' }[status] || 'comp';
        },
    },
};