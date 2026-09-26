/**
 * Agent 工作台：把「一次路径诊断」升级成带会话上下文的连续调查。
 *
 * 三栏：左栏会话列表（内存存储，重启即清空），中栏对话与调查进度，右栏当前事件上下文。
 * 数据来源分两条：会话的展示数据一次取自聚合接口 workspace（会话 + 对话 + 事件 + 最近任务），
 * 任务卡上的步骤、状态与解读由「任务事件流」实时推送——轮询只做断线、刷新与后台恢复时的兜底。
 *
 * 用户身份由后端认证上下文决定，这里不提交也不展示 userId。
 */

/** 聚合接口一次取回的任务条数：更早的历史任务不在时间线里，避免把整个会话搬进页面。 */
const WB_TASK_LIMIT = 50;

/** 事件流重连退避：1s → 2s → 4s → 8s，上限 15s；收到快照即视为连接已恢复并重置。 */
const WB_STREAM_RETRY_MIN_MILLIS = 1000;
const WB_STREAM_RETRY_MAX_MILLIS = 15000;

/** 任务事件类型：事件名就是类型，数据是完整信封（eventId / type / timestampMillis / payload）。 */
const WB_EVENT_TYPES = [
    'SNAPSHOT', 'TASK_CREATED', 'TASK_STARTED', 'STEP_STARTED', 'STEP_COMPLETED', 'STEP_FAILED',
    'EVIDENCE_ADDED', 'ANALYSIS_DELTA', 'CLARIFICATION_REQUIRED', 'TASK_COMPLETED', 'TASK_FAILED',
    'TASK_CANCELLED',
];

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
            wbLoading: false,
            wbInput: '',
            wbContext: { path: '', service: '', instance: '', from: '', to: '' },
            wbContextOpen: false,
            wbSending: false,
            wbDetailOpen: {},
            wbStreamTaskId: '',
            wbStreamTexts: {},
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

        /** 需要补充信息的任务：最近的澄清提问显示在输入框上方（可能有多次提问留在会话里）。 */
        wbHint() {
            const waiting = Object.values(this.wbTasks).filter(task => task && task.clarification);
            if (!waiting.length) return null;
            return waiting.reduce((best, task) => (task.createdAtMillis >= best.createdAtMillis ? task : best))
                .clarification;
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
            this.wbStreamTexts = {};
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
            this.wbStreamTexts = {};
            this.wbDetailOpen = {};
            this.wbError = null;
            await this.wbLoadSession(sessionId);
        },

        /**
         * 载入一个会话的展示数据：一次聚合取齐会话、对话、事件与最近任务。
         *
         * 之前是「会话详情 → 逐个事件 → 逐个任务」的 N+1 请求，随历史增长会越来越慢；
         * 聚合接口同时给出事件列表与最近若干条任务，中栏时间线与右栏事实都从这一份数据来。
         */
        async wbLoadSession(sessionId) {
            if (!sessionId) return;
            this.wbLoading = true;
            try {
                const workspace = await RoverAdminApi.api('/api/agent/sessions/' + encodeURIComponent(sessionId)
                    + '/workspace?limit=' + WB_TASK_LIMIT);
                if (this.wbActiveSessionId !== sessionId) return;
                this.wbSession = workspace.session || null;
                this.wbMessages = workspace.messages || [];
                this.wbActiveIncident = workspace.activeIncident || null;
                const incidents = {};
                (workspace.incidents || []).forEach((incident) => { incidents[incident.incidentId] = incident; });
                if (this.wbActiveIncident) incidents[this.wbActiveIncident.incidentId] = this.wbActiveIncident;
                this.wbIncidents = incidents;
                const tasks = {};
                (workspace.tasks || []).forEach((task) => { tasks[task.taskId] = task; });
                this.wbTasks = tasks;
                this.wbError = null;
                this.wbFollowRunningTask();
            } catch (e) {
                if (this.wbActiveSessionId === sessionId) {
                    this.wbError = '加载会话失败：' + e.message;
                }
            } finally {
                this.wbLoading = false;
            }
        },

        /** 取一次任务快照：断线对齐、终态收尾与 409 复用已有任务卡都靠它。 */
        async wbReloadTask(taskId) {
            const task = await RoverAdminApi.api('/api/agent/tasks/' + encodeURIComponent(taskId))
                .catch(() => null);
            if (task) this.wbMergeTask(task);
            return task;
        },

        /** 载入会话后自动接上在跑任务的事件流：刷新页面、切回会话都能继续实时看进度。 */
        wbFollowRunningTask() {
            // 已连上、或正在退避等待重连时不重复开流：否则每次兜底轮询都会把退避冲掉。
            if (this._wbStream || this._wbStreamTimer || typeof EventSource === 'undefined') return;
            const running = Object.values(this.wbTasks)
                .filter(task => task && !this.wbTaskSettled(task.status))
                .sort((a, b) => b.createdAtMillis - a.createdAtMillis);
            if (running.length) this.wbOpenStream(running[0].taskId);
        },

        /**
         * 轮询兜底：SSE 正常时不轮询，只在「首次打开 / 刷新 / 断线 / 超时 / 后台标签恢复」时取一次快照。
         *
         * 任务状态、步骤与解读都已由任务事件流实时推送，固定 3 秒轮询全部在跑任务只是多余的观察税。
         */
        async wbPollSession() {
            if (!this.wbActiveSessionId || this._wbStream) return;
            const ids = Object.values(this.wbTasks)
                .filter(task => task && !this.wbTaskSettled(task.status))
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
            // 任务刚结束：事件摘要与当前结论在服务端已更新，重新载入才能让右栏跟上。
            if (justFinished && this.wbActiveSessionId) await this.wbLoadSession(this.wbActiveSessionId);
            else this.wbFollowRunningTask();
        },

        async wbSend() {
            const message = (this.wbInput || '').trim();
            if (!message || this.wbSending) return;
            const extra = this.wbRequestContext();
            if (extra === null) return;
            this.wbSending = true;
            this.wbError = null;
            try {
                // 没有会话时直接提问：先开一个空会话再发，主入口就是这一个输入框。
                let sessionId = this.wbActiveSessionId;
                if (!sessionId) {
                    sessionId = await this.wbCreateSession();
                    this.wbActiveSessionId = sessionId;
                }
                // 202 只给任务句柄：目标解析与调查在 Agent Worker 里，进度靠任务事件流观察。
                const response = await RoverAdminApi.api(
                    '/api/agent/sessions/' + encodeURIComponent(sessionId) + '/messages',
                    {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(Object.assign({ message }, extra)),
                    });
                this.wbInput = '';
                await this.wbLoadSession(sessionId);
                this.wbLoadSessions();
                if (response && response.taskId) this.wbOpenStream(response.taskId);
            } catch (e) {
                if (e.status === 409) {
                    // 同会话已有执行中的任务：复用那张任务卡并接上它的事件流，而不是再开一张。
                    this.wbError = e.message;
                    const runningTaskId = e.body && e.body.taskId;
                    if (runningTaskId) {
                        await this.wbReloadTask(runningTaskId);
                        this.wbOpenStream(runningTaskId);
                    }
                } else {
                    this.wbError = '提问失败：' + e.message;
                }
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
         * 订阅任务事件流：事件名是事件类型，数据是完整信封。
         *
         * 步骤、状态与解读都从这条流实时落到任务卡上，不再等下一次轮询；右栏结论与证据在
         * 终态事件后刷新一次即可。断线由 {@link #wbStreamFailed} 按指数退避重连。
         */
        wbOpenStream(taskId) {
            if (!taskId || typeof EventSource === 'undefined') return;
            this.wbDetachStream();
            const source = new EventSource('/api/agent/tasks/' + encodeURIComponent(taskId) + '/events');
            this._wbStream = source;
            this._wbStreamTaskId = taskId;
            this.wbStreamTaskId = taskId;
            this.wbStreaming = true;
            WB_EVENT_TYPES.forEach((type) => {
                source.addEventListener(type, (event) => this.wbApplyEvent(taskId, type, event));
            });
            source.onerror = () => this.wbStreamFailed(taskId);
        },

        /** 只断开当前连接与待重连定时器，不动退避计数（重连路径要用它累加）。 */
        wbDetachStream() {
            if (this._wbStreamTimer) {
                clearTimeout(this._wbStreamTimer);
                this._wbStreamTimer = null;
            }
            if (this._wbStream) {
                this._wbStream.close();
                this._wbStream = null;
            }
            this._wbStreamTaskId = '';
            this.wbStreaming = false;
        },

        /** 主动关闭订阅：切换会话、任务收尾与离开工作台都走这里，退避计数一并归零。 */
        wbCloseStream() {
            this.wbDetachStream();
            this._wbStreamAttempt = 0;
        },

        /**
         * 把一条任务事件落到页面上：快照覆盖全量、增量追加解读、步骤就地合并，终态收尾。
         *
         * 快照优先于增量：重连后先按快照对齐全量，再接着收增量，不重复也不遗漏。
         */
        wbApplyEvent(taskId, type, event) {
            let envelope = null;
            try {
                envelope = JSON.parse(event.data);
            } catch (e) {
                return;
            }
            const payload = envelope && envelope.payload;
            if (!payload) return;
            if (type === 'SNAPSHOT') {
                // 能收到快照说明这条连接是通的：重置退避，下次断线仍从 1s 起步。
                this._wbStreamAttempt = 0;
                this.wbMergeTask(payload.task);
                if (typeof payload.analysis === 'string') {
                    this.wbStreamTexts = Object.assign({}, this.wbStreamTexts, { [taskId]: payload.analysis });
                }
                return;
            }
            if (type === 'ANALYSIS_DELTA' && typeof payload.text === 'string') {
                const current = this.wbStreamTexts[taskId] || '';
                this.wbStreamTexts = Object.assign({}, this.wbStreamTexts, { [taskId]: current + payload.text });
                return;
            }
            if (type === 'TASK_CREATED' || type === 'TASK_STARTED') {
                this.wbMergeTask(Object.assign({}, this.wbTasks[taskId], { status: payload.status }));
                return;
            }
            if (type === 'STEP_STARTED' || type === 'STEP_COMPLETED' || type === 'STEP_FAILED') {
                this.wbMergeStep(taskId, payload.step, type);
                return;
            }
            if (type === 'CLARIFICATION_REQUIRED') {
                // 停在澄清点：任务不再产出事件，卡片上直接展示要用户补什么。
                this.wbMergeTask(Object.assign({}, this.wbTasks[taskId], {
                    status: 'WAITING_INPUT',
                    clarification: payload.clarification,
                }));
                this.wbFinishStream();
                return;
            }
            if (type === 'TASK_FAILED') {
                this.wbMergeTask(Object.assign({}, this.wbTasks[taskId], {
                    status: 'FAILED',
                    error: payload.error,
                }));
                this.wbFinishStream();
                return;
            }
            if (type === 'TASK_COMPLETED' || type === 'TASK_CANCELLED') {
                this.wbMergeTask(Object.assign({}, this.wbTasks[taskId], { status: payload.status }));
                this.wbFinishStream();
            }
            // EVIDENCE_ADDED 紧随 TASK_COMPLETED：证据与结论在同一份快照里，收尾时一次取齐。
        },

        /** 任务卡上的最小合并：只替换这一条任务，其它任务不动。 */
        wbMergeTask(task) {
            if (!task || !task.taskId) return;
            this.wbTasks = Object.assign({}, this.wbTasks, { [task.taskId]: task });
        },

        /** 把事件里的步骤合并进任务卡：同一 stepId 就地覆盖，同一阶段不会出现两行。 */
        wbMergeStep(taskId, step, type) {
            if (!step) return;
            const task = this.wbTasks[taskId];
            if (!task) return;
            const steps = (task.steps || []).filter(item => item.stepId !== step.stepId);
            steps.push(step);
            this.wbMergeTask(Object.assign({}, task, {
                steps,
                status: task.status === 'PENDING' ? 'RUNNING' : task.status,
                currentStage: step.type || task.currentStage,
            }));
        },

        /**
         * 流断开或超时：先取一次任务快照对齐，任务仍在执行时按指数退避重连。
         *
         * 不立即重连也不轮询替代：EventSource 默认会自己重连，那样会在服务端故障时打成一串请求，
         * 这里改为显式退避（1s → 2s → 4s → 8s，上限 15s），收到快照即视为恢复。
         */
        wbStreamFailed(taskId) {
            const sessionId = this.wbActiveSessionId;
            if (!sessionId || this._wbStreamTaskId !== taskId) return;
            this.wbDetachStream();
            this.wbReloadTask(taskId).then((task) => {
                if (!task || this.wbActiveSessionId !== sessionId || this.wbTaskSettled(task.status)) return;
                const attempt = (this._wbStreamAttempt || 0) + 1;
                this._wbStreamAttempt = attempt;
                const delay = Math.min(WB_STREAM_RETRY_MIN_MILLIS * Math.pow(2, attempt - 1),
                    WB_STREAM_RETRY_MAX_MILLIS);
                this._wbStreamTimer = setTimeout(() => {
                    this._wbStreamTimer = null;
                    if (this.wbActiveSessionId === sessionId) this.wbOpenStream(taskId);
                }, delay);
            }).catch(() => { /* 快照都取不到：等下一次兜底轮询或用户手动刷新 */ });
        },

        /** 任务收尾：断开订阅，重新取一次聚合视图，让结论、证据与事件状态一起跟上。 */
        wbFinishStream() {
            const sessionId = this.wbActiveSessionId;
            this.wbCloseStream();
            this.wbLoadSessions();
            if (sessionId) this.wbLoadSession(sessionId);
        },

        /** 任务卡上的解读文本：落库全文优先（终态后以它为准），生成中显示流式增量。 */
        wbAnalysisText(task) {
            const final = task && task.result && task.result.aiAnalysis;
            if (final) return final;
            return (task && this.wbStreamTexts[task.taskId]) || '';
        },

        wbTaskTerminal(status) {
            return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status);
        },

        /** 已定型、不会再自己变化的任务：终态之外，等你补充信息的澄清点也算。 */
        wbTaskSettled(status) {
            return this.wbTaskTerminal(status) || status === 'WAITING_INPUT';
        },

        /**
         * 任务卡上的步骤：完全按后端上报的 steps 展示，后端没执行的阶段不凭空构造。
         *
         * 之前页面上写死「路由 → 实例 → 指标 → 追踪」四阶段，Graph 每加一个节点都要改前端；
         * 现在只用 type / name / status 渲染，新增节点时前端不用动。
         */
        wbSteps(task) {
            return ((task && task.steps) || []).map((step) => ({
                key: step.stepId,
                label: step.name || this.wbStepTypeLabel(step.type),
                mark: { COMPLETED: '✓', FAILED: '!', RUNNING: '●' }[step.status] || '○',
                state: { COMPLETED: 'done', FAILED: 'failed', RUNNING: 'running' }[step.status] || 'pending',
                title: step.error || step.outputSummary || step.inputSummary || '',
            }));
        },

        /** 当前阶段：最后一个执行中的步骤，没有就取最后上报的那一步。 */
        wbCurrentStage(task) {
            const steps = (task && task.steps) || [];
            const running = steps.filter(step => step.status === 'RUNNING').pop();
            const step = running || steps[steps.length - 1];
            if (!step) return task && task.status === 'PENDING' ? '等待执行' : '';
            return step.name || this.wbStepTypeLabel(step.type);
        },

        /** 步骤类型的中文名：只在后端没给 step.name 时兜底，不参与任何流程判断。 */
        wbStepTypeLabel(type) {
            return {
                TARGET_RESOLUTION: '目标解析',
                ROUTE_INVESTIGATION: '读取路由',
                INSTANCE_INVESTIGATION: '读取实例',
                METRIC_INVESTIGATION: '读取指标',
                TRACE_INVESTIGATION: '读取追踪',
                DIAGNOSIS: '生成结论',
                AI_EXPLANATION: 'AI 解读',
            }[type] || type || '步骤';
        },

        /** 被调查对象：目标解析完成前路径为空，如实显示待解析而不是伪造一个对象。 */
        wbTargetText(task) {
            const target = task && task.target;
            if (target && target.value) return target.value;
            return (task && task.path) || '';
        },

        wbToggleDetail(taskId) {
            this.wbDetailOpen = Object.assign({}, this.wbDetailOpen, { [taskId]: !this.wbDetailOpen[taskId] });
        },

        wbSessionAt(session) {
            return session.lastActiveAtMillis || session.createdAtMillis || 0;
        },

        wbStatusLabel(status) {
            return {
                PENDING: '排队中', RUNNING: '调查中', WAITING_INPUT: '待补充', COMPLETED: '已完成',
                FAILED: '失败', CANCELLED: '已取消',
            }[status] || status || '等待中';
        },

        wbStatusBadge(status) {
            return {
                COMPLETED: 'ok', FAILED: 'bad', CANCELLED: 'warn', RUNNING: 'warn', WAITING_INPUT: 'warn',
            }[status] || 'comp';
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