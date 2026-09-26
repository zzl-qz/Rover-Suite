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

/** 空态里可直接点的问题示例：都落在已接入的只读能力范围内，点了就填进输入框而不是直接发送。 */
const WB_EXAMPLES = [
    '网关现在 QPS 多少？',
    'order-service 有几个健康实例？',
    '为什么 /api/demo/tt 调用失败？',
    '你能做什么？',
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
            /** 本地回显的用户消息：服务端受理前先显示出来，IM 的手感是「发出去就立刻看到」。 */
            wbPending: [],
            wbDetailOpen: {},
            wbStreamTaskId: '',
            wbStreamTexts: {},
            wbStreaming: false,
            /** 视图是否贴着对话底部：决定自动跟随，以及要不要显示「回到最新」。 */
            wbAtBottom: true,
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

        /** 空态可直接点的问题示例，与注册表里的只读能力一一对应。 */
        wbExamples() {
            return WB_EXAMPLES;
        },

        /**
         * 聊天窗头部状态：受理 / 生成解读 / 调查中 / 在线。
         *
         * 依据就是页面上已有的三份事实（提交中、事件流在推、有任务没定型），不另做一套判断。
         */
        wbAgentState() {
            if (this.wbSending) return { label: '正在受理…', tone: 'busy' };
            if (this.wbStreaming) return { label: '正在生成解读…', tone: 'busy' };
            const running = Object.values(this.wbTasks).some(task => task && !this.wbTaskSettled(task.status));
            if (running) return { label: '调查中…', tone: 'busy' };
            return { label: '在线 · 只读', tone: 'ok' };
        },

        /**
         * 中栏时间线：会话消息与本地回显按时间归并，再按天插入日期分隔。
         *
         * 一次提问只对应一条 Agent 回答——过程条与答案同属这条回答（模板里是同一个 turn），
         * 与主流 Agent 一样：过程收在答案上方，点开才看真实经过。
         *
         * 一个任务可能留下多条 Agent 消息（「已开始调查 X」→ 目标解析的澄清 → 最终结论），
         * 只有最后一条算回答、由它携带过程条；中间播报并入过程条，不在时间线上另起一条，
         * 否则一次提问看起来是两条回复。还没产出回答的任务自己成一条过程条。
         */
        wbTurns() {
            const tasks = {};
            Object.values(this.wbTasks).forEach((task) => { if (task) tasks[task.taskId] = task; });

            /** 每个任务的最后一条 Agent 消息：这条是回答，其余都是过程播报。 */
            const answer = {};
            this.wbMessages.forEach((message) => {
                if (message.role !== 'AGENT' || !message.relatedTaskId || !tasks[message.relatedTaskId]) return;
                const previous = answer[message.relatedTaskId];
                if (!previous || (message.createdAtMillis || 0) >= (previous.createdAtMillis || 0)) {
                    answer[message.relatedTaskId] = message;
                }
            });

            const askedAt = {};
            const turns = [];
            this.wbMessages.forEach((message) => {
                const taskId = message.relatedTaskId;
                if (taskId && message.role === 'USER' && askedAt[taskId] === undefined) {
                    askedAt[taskId] = message.createdAtMillis || 0;
                }
                if (message.role === 'AGENT' && taskId && tasks[taskId] && answer[taskId] !== message) {
                    return;
                }
                turns.push({
                    kind: 'message',
                    key: 'm-' + message.messageId,
                    at: message.createdAtMillis || 0,
                    // 消息经 relatedTaskId 稳定绑定任务：回答带着过程条，是同一条回复的两个部分
                    task: message.role === 'AGENT' && taskId ? (tasks[taskId] || null) : null,
                    message,
                });
            });
            this.wbPending.forEach((item) => turns.push({
                kind: 'message',
                key: item.key,
                at: item.at,
                task: null,
                message: { role: 'USER', content: item.content, createdAtMillis: item.at },
            }));
            Object.values(tasks).forEach((task) => {
                if (answer[task.taskId]) return;
                // 任务注册比用户消息落库早 1~2ms，直接按时间排会跑到提问前面；锚到那次提问之后。
                const asked = askedAt[task.taskId];
                turns.push({
                    kind: 'task',
                    key: 't-' + task.taskId,
                    at: asked === undefined ? (task.createdAtMillis || 0) : asked + 1,
                    task,
                    message: null,
                });
            });
            const sorted = turns.sort((a, b) => a.at - b.at);
            const withDays = [];
            let lastDay = '';
            sorted.forEach((turn) => {
                const day = this.wbDayKey(turn.at);
                if (day && day !== lastDay) {
                    withDays.push({ kind: 'day', key: 'day-' + day, at: turn.at, label: this.wbDayLabel(turn.at) });
                    lastDay = day;
                }
                withDays.push(turn);
            });
            return withDays;
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

    /**
     * 对话是一扇滚动窗口：新消息、新步骤与流式增量都要把视图带到底部。
     *
     * 跟随发生在「用户本来就贴着底部」时；往上翻历史时新内容不打扰阅读，与 IM 一致。
     */
    watch: {
        wbTurns() { this.wbScrollDown(false); },
        wbStreamTexts() { this.wbScrollDown(false); },
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
            this.wbPending = [];
            this._wbLoadedSessionId = '';
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
            this.wbPending = [];
            this._wbLoadedSessionId = '';
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
                // 切到别的会话（含首次载入）直接落到底部；同一会话的刷新则尊重用户当前的阅读位置。
                const switched = this._wbLoadedSessionId !== sessionId;
                this._wbLoadedSessionId = sessionId;
                if (switched) {
                    this.wbAtBottom = true;
                    this.wbScrollDown(true);
                } else {
                    this.wbScrollDown(false);
                }
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
            this.wbInput = '';
            const pending = {
                key: 'p-' + Date.now() + '-' + Math.random().toString(36).slice(2, 7),
                content: message,
                at: Date.now(),
            };
            this.wbPending = [...this.wbPending, pending];
            this.wbAtBottom = true;
            this.wbScrollDown(true);
            try {
                // 没有会话时直接提问：先开一个空会话再发，主入口就是这一个输入框。
                let sessionId = this.wbActiveSessionId;
                if (!sessionId) {
                    sessionId = await this.wbCreateSession();
                    this.wbActiveSessionId = sessionId;
                    this.wbScrollDown(true);
                }
                // 202 只给任务句柄：目标解析与调查在 Agent Worker 里，进度靠任务事件流观察。
                const response = await RoverAdminApi.api(
                    '/api/agent/sessions/' + encodeURIComponent(sessionId) + '/messages',
                    {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(Object.assign({ message }, extra)),
                    });
                this.wbDropPending(pending.key);
                await this.wbLoadSession(sessionId);
                this.wbLoadSessions();
                if (response && response.taskId) this.wbOpenStream(response.taskId);
            } catch (e) {
                this.wbDropPending(pending.key);
                // 没发出去就把内容还回输入框：用户不必重新打一遍（已经在打新内容时不覆盖）。
                if (!this.wbInput.trim()) this.wbInput = message;
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
                this.wbFocusInput();
            }
        },

        /** 去掉本地回显：服务端已受理（或明确失败）后，它就该从时间线上消失。 */
        wbDropPending(key) {
            this.wbPending = this.wbPending.filter(item => item.key !== key);
        },

        /** Enter 发送、Shift+Enter 换行；输入法组合中的 Enter 是在选词，不能当发送。 */
        wbOnKeydown(event) {
            if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return;
            event.preventDefault();
            this.wbSend();
        },

        /** 示例问题只填入输入框，发不发由用户决定——点了就发会剥夺改词的机会。 */
        wbUseExample(text) {
            this.wbInput = text;
            this.wbFocusInput();
        },

        wbFocusInput() {
            this.$nextTick(() => {
                const el = this.$refs.wbInputEl;
                if (el) el.focus();
            });
        },

        /**
         * IM 式滚动：只在用户本来就贴着底部时跟随新内容。
         *
         * 往上翻历史时新消息不打断阅读；主动发消息与切换会话则强制落到底部。
         */
        wbOnScroll() {
            const el = this.$refs.wbBody;
            if (!el) return;
            this.wbAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        },

        wbScrollDown(force) {
            this.$nextTick(() => {
                const el = this.$refs.wbBody;
                // 默认视为贴在底部：首次渲染与切换会话都能直接看到最新内容。
                if (!el || (!force && !this.wbAtBottom)) return;
                el.scrollTop = el.scrollHeight;
                this.wbAtBottom = true;
            });
        },

        /** 「回到最新」：翻完历史一键落回底部，并把自动跟随重新打开。 */
        wbJumpToLatest() {
            this.wbAtBottom = true;
            this.wbScrollDown(true);
        },

        /** 这条任务的解读是不是正在流式生成：决定要不要闪烁光标与状态点。 */
        wbIsStreaming(task) {
            return Boolean(task && this.wbStreaming && this.wbStreamTaskId === task.taskId);
        },

        wbDayKey(millis) {
            if (!millis) return '';
            const date = new Date(millis);
            return date.getFullYear() + '-' + (date.getMonth() + 1) + '-' + date.getDate();
        },

        wbDayLabel(millis) {
            const day = this.wbDayKey(millis);
            if (day === this.wbDayKey(Date.now())) return '今天';
            if (day === this.wbDayKey(Date.now() - 86400000)) return '昨天';
            const date = new Date(millis);
            return (date.getMonth() + 1) + '月' + date.getDate() + '日';
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
                INTENT_RESOLUTION: '意图识别',
                PLANNING: '调查规划',
                ACTION_PLANNING: '生成处置计划',
                ANSWER: '回答',
                TARGET_RESOLUTION: '目标解析',
                ROUTE_INVESTIGATION: '读取路由',
                INSTANCE_INVESTIGATION: '读取实例',
                METRIC_INVESTIGATION: '读取指标',
                TRACE_INVESTIGATION: '读取追踪',
                DIAGNOSIS: '生成结论',
                AI_EXPLANATION: 'AI 解读',
            }[type] || type || '步骤';
        },

        /** 任务类型：一次提问最终以什么形态执行，后端 taskType 直接给出，不靠文本猜。 */
        wbTaskTypeLabel(type) {
            return {
                QUERY: '状态查询', INVESTIGATION: '故障调查', ACTION_PLAN: '处置计划',
                EXPLAIN: '解释说明', UNSUPPORTED: '暂未开放',
            }[type] || '故障调查';
        },

        wbTaskTypeBadge(type) {
            return { INVESTIGATION: 'comp', QUERY: 'ok', EXPLAIN: 'ok', ACTION_PLAN: 'warn', UNSUPPORTED: 'warn' }[type]
                || 'comp';
        },

        wbIntentLabel(intent) {
            return {
                QUERY_STATE: '查询状态', INVESTIGATE: '故障调查', EXPLAIN: '解释说明',
                ACTION_REQUEST: '处置请求', CREATE_INSPECTION: '定时巡检', KNOWLEDGE_QUERY: '知识检索',
                UNKNOWN: '未识别',
            }[intent] || intent || '未识别';
        },

        /** 只读能力的中文名：与后端 AgentCapability 一一对应，用于计划与已执行能力展示。 */
        wbCapabilityLabel(capability) {
            return {
                ROUTE_QUERY: '路由查询', INSTANCE_QUERY: '实例查询', GATEWAY_METRICS_QUERY: '指标查询',
                TRACE_QUERY: '追踪查询', CONFIG_READ: '配置读取', EVENT_QUERY: '事件查询',
            }[capability] || capability || '-';
        },

        /** 计划步骤的执行情况：已执行的能力在计划里就地标出来，不必对照两份清单。 */
        wbPlanState(task, capability) {
            const executed = (task && task.executedCapabilities) || [];
            return executed.includes(capability) ? 'done' : 'pending';
        },

        wbPlanMark(task, capability) {
            return this.wbPlanState(task, capability) === 'done' ? '✓' : '○';
        },

        wbActionTypeLabel(type) {
            return {
                DRAIN_INSTANCE: '摘除实例', RESTORE_INSTANCE: '恢复实例', UPDATE_ROUTE_TIMEOUT: '调整路由超时',
                UPDATE_RATE_LIMIT: '调整限流', UNKNOWN: '未识别的动作',
            }[type] || type || '未识别的动作';
        },

        wbRiskLabel(level) {
            return { LOW: '低', MEDIUM: '中', HIGH: '高' }[level] || level || '未评估';
        },

        wbRiskBadge(level) {
            return { LOW: 'ok', MEDIUM: 'warn', HIGH: 'bad' }[level] || 'comp';
        },

        /** 处置计划的目标展示：优先用预检解析出的对象，解析不出时如实退回用户原文。 */
        wbActionTargetText(plan) {
            if (!plan) return '-';
            const value = plan.target && plan.target.value;
            return value || plan.targetDescription || '未指定';
        },

        /** 已执行能力的中文清单；没有时返回空串，由模板决定是否展示这一行。 */
        wbExecutedCapabilities(task) {
            return ((task && task.executedCapabilities) || []).map(cap => this.wbCapabilityLabel(cap)).join('、');
        },

        /** 被调查对象：目标解析完成前路径为空，如实显示待解析而不是伪造一个对象。 */
        wbTargetText(task) {
            const target = task && task.target;
            if (target && target.value) return target.value;
            return (task && task.path) || '';
        },

        /**
         * 任务卡是否展开：默认只留一行「过程条」，点开才看真实调查过程。
         *
         * 调查中的任务例外——步骤正在往上滚，铺开才有意义；定型后自动折回一行。
         * 用户手动开合过就以用户的为准（{@link #wbDetailOpen} 里存着手动状态）。
         */
        wbTaskOpen(task) {
            if (!task) return false;
            const manual = this.wbDetailOpen[task.taskId];
            if (manual !== undefined) return manual;
            return !this.wbTaskSettled(task.status);
        },

        wbToggleTask(taskId) {
            const task = this.wbTasks[taskId];
            if (!task) return;
            this.wbDetailOpen = Object.assign({}, this.wbDetailOpen, { [taskId]: !this.wbTaskOpen(task) });
        },

        /** 折叠条上的进度：3/4 步；后端没上报步骤时不占位置。 */
        wbTaskStepText(task) {
            const steps = this.wbSteps(task);
            if (!steps.length) return '';
            return steps.filter(step => step.state === 'done').length + '/' + steps.length + ' 步';
        },

        wbTaskToggleHint(task) {
            if (this.wbTaskOpen(task)) return '收起过程';
            return this.wbAnalysisText(task) ? '查看调查过程 · 含 AI 解读' : '查看调查过程';
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

        /**
         * 证据的统计口径摘要：窗口秒数与样本量直接来自证据 metadata。
         * 样本不足时前端也要看得见「样本量」，否则无法判断「有没有异常」这句话的依据够不够。
         */
        wbEvidenceScope(item) {
            const meta = (item && item.metadata) || {};
            const parts = [];
            const windowSeconds = Number(meta.windowSeconds);
            if (windowSeconds > 0) parts.push('窗口 ' + windowSeconds + 's');
            else if (windowSeconds === 0) parts.push('时点快照');
            const sampleSize = Number(meta.sampleSize);
            if (Number.isFinite(sampleSize) && sampleSize > 0) parts.push('样本 ' + sampleSize);
            if (meta.routeId) parts.push('路由 ' + meta.routeId);
            if (meta.hostPort) parts.push('上游 ' + meta.hostPort);
            if (meta.component) parts.push('组件 ' + meta.component);
            return parts.join(' · ');
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