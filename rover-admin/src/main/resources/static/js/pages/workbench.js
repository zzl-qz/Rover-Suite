/**
 * Agent 工作台：把「一次路径诊断」升级成带会话上下文的连续调查。
 *
 * 三栏：左栏会话列表（原话与调查过程落在记录库），中栏对话与调查进度，右栏当前事件上下文。
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

/** 打字机节奏：30ms 一拍；每拍至少补 {@link #WB_TYPE_MIN_CHARS_PER_TICK} 字，落后越多补得越快。 */
const WB_TYPE_TICK_MILLIS = 30;
const WB_TYPE_MIN_CHARS_PER_TICK = 2;
/** 追赶系数：每拍补「落后字数 / 8」，保证积压时能追上而不是越拖越远。 */
const WB_TYPE_CATCH_UP_DIVISOR = 8;

/** 任务事件类型：事件名就是类型，数据是完整信封（eventId / type / timestampMillis / payload）。 */
const WB_EVENT_TYPES = [
    'SNAPSHOT', 'TASK_CREATED', 'TASK_STARTED', 'STEP_STARTED', 'STEP_COMPLETED', 'STEP_FAILED',
    'EVIDENCE_ADDED', 'ANALYSIS_DELTA', 'THINKING_DELTA', 'CLARIFICATION_REQUIRED', 'TASK_COMPLETED',
    'TASK_FAILED', 'TASK_CANCELLED',
];

/**
 * 空态里可直接点的问题示例：都落在已接入的能力范围内（只读调查 + 一个待审批的权重变更），
 * 点了只填进输入框而不是直接发送——含变更意图的例子更要留出改词的机会。
 */
const WB_EXAMPLES = [
    '网关现在 QPS 多少？',
    'order-service 有几个健康实例？',
    '为什么 /api/demo/tt 调用失败？',
    '把 /api/order 的 v2 放量到 20（会生成待审批变更）',
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
            /** 点旧对话卡片时带上的会话 ID，只对这一次发送有效。 */
            wbRecallSessionId: '',
            /** 本地回显的用户消息：服务端受理前先显示出来，IM 的手感是「发出去就立刻看到」。 */
            wbPending: [],
            wbDetailOpen: {},
            wbStreamTaskId: '',
            /** 流式增量的完整目标文本（服务端推多少就是多少，不在渲染层做裁剪）。 */
            wbStreamTexts: {},
            /**
             * 已渲染到屏幕的文本：目标文本先落这里，再由打字机逐字追上。
             *
             * 分两份是为了「手感」：模型一帧吐一大段时，直接渲染会整段蹦出来；
             * 拆成目标与进度之后，无论上游是逐字流还是整段到达，屏幕上的字都是匀速长出来的。
             */
            wbRenderTexts: {},
            /** 模型思考文本：与答案同构的两份，各自走打字机，互不干扰。 */
            wbThinkingTexts: {},
            wbRenderThinking: {},
            /** 思考用时的起止时刻：首条思考增量到首条答案增量之间，就是这次「深度思考」花掉的时间。 */
            wbThinkingStart: {},
            wbThinkingEnd: {},
            /** 用户手动开合过思考面板的标记：手动优先，没动过则「思考中展开、出答案收拢」。 */
            wbThinkManual: {},
            wbStreaming: false,
            /** 每秒推进一次的时钟：让「进行中」的步骤耗时与思考用时自己往上走，不必等下一个事件。 */
            wbNow: 0,
            /** 视图是否贴着对话底部：决定自动跟随，以及要不要显示「回到最新」。 */
            wbAtBottom: true,
            /** 当前会话的变更记录（新的在前）：待审批、执行结果与回滚都从服务端读回来。 */
            wbActions: [],
            /** 每条变更正在进行的操作（approve / reject / rollback / resolve）：用来禁用按钮，避免连点。 */
            wbActionBusy: {},
            /** 每条变更最近一次操作失败的原因：服务端拒绝（409/404）时显示在卡片里，而不是整页报错。 */
            wbActionError: {},
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
            const active = Object.values(this.wbTasks)
                .filter(task => task && !this.wbTaskSettled(task.status))
                .sort((a, b) => (b.createdAtMillis || 0) - (a.createdAtMillis || 0))[0];
            if (active) {
                // 有步骤在执行就直接说正在做什么，取不到才退回泛化措辞
                return { label: this.wbCurrentAction(active) || '调查中…', tone: 'busy' };
            }
            if (this.wbStreaming) return { label: '正在生成解读…', tone: 'busy' };
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
        /**
         * 待人工处置的变更条数：批准 / 拒绝 / 回滚 / 确认结果都算「等一个决定」。
         *
         * 回读确认中、执行中的不算待人工处置（程序正在跑），失败与已完成也不算。
         */
        wbPendingActions() {
            return this.wbActions.filter(action => ['PENDING_APPROVAL', 'UNCERTAIN', 'SUCCESS']
                .includes(action.status)).length;
        },

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
        // 打字机每推进一拍都可能长出新行：跟随滚动要挂在渲染进度上，
        // 挂在目标文本上会在「还没写到那里」时提前把视图拉到底。
        wbRenderTexts() { this.wbScrollDown(false); },
        wbRenderThinking() { this.wbFollowThinking(); },
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
            this.wbStopPump();
            this.wbTasks = {};
            this.wbStreamTexts = {};
            this.wbRenderTexts = {};
            this.wbThinkingTexts = {};
            this.wbRenderThinking = {};
            this.wbThinkingStart = {};
            this.wbThinkingEnd = {};
            this.wbThinkManual = {};
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
            this.wbStopPump();
            this.wbTasks = {};
            this.wbStreamTexts = {};
            this.wbRenderTexts = {};
            this.wbThinkingTexts = {};
            this.wbRenderThinking = {};
            this.wbThinkingStart = {};
            this.wbThinkingEnd = {};
            this.wbThinkManual = {};
            this.wbPending = [];
            // 变更记录属于会话：切会话先把上一场的卡片清掉，再拉新会话自己的记录
            this.wbActions = [];
            this.wbActionBusy = {};
            this.wbActionError = {};
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
                // 变更记录与对话一起对齐：任务刚结束的时候，正是新提议出现的时候。
                // 不 await：它只影响右栏一张卡，不该拖慢对话与任务卡的渲染。
                this.wbLoadActions(sessionId);
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
            if (this.wbRecallSessionId) extra.recallSessionId = this.wbRecallSessionId;
            this.wbRecallSessionId = '';
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

        /** 取消一个仍在执行中的任务：协作式——标记取消并中断执行线程，结论不会再产出。 */
        async wbCancelTask(task) {
            if (!task || !this.wbCanCancel(task)) return;
            task._cancelling = true;
            try {
                await RoverAdminApi.api(
                    '/api/agent/tasks/' + encodeURIComponent(task.taskId) + '/cancel',
                    { method: 'POST' });
                await this.wbReloadTask(task.taskId);
            } catch (e) {
                this.wbError = '取消失败：' + (e && e.message ? e.message : e);
            } finally {
                task._cancelling = false;
            }
        },

        // ---------------------------------------------------------------- 变更与处置

        /** 读当前会话的变更记录；读不到就按「没有变更」渲染，不打扰主流程（对话与调查不受影响）。 */
        async wbLoadActions(sessionId) {
            if (!sessionId) {
                this.wbActions = [];
                return;
            }
            try {
                const list = await RoverAdminApi.api('/api/agent/actions/sessions/' + encodeURIComponent(sessionId));
                if (this.wbActiveSessionId !== sessionId) return;
                this.wbActions = Array.isArray(list) ? list : [];
            } catch (e) {
                if (this.wbActiveSessionId === sessionId) this.wbActions = [];
            }
        },

        /**
         * 处置一条变更：批准 / 拒绝 / 回滚 / 确认结果。
         *
         * 服务端的执行是同步的，因此这里只需等返回值——返回的就是终态。
         * 前端不做「先乐观改成成功」：变更有没有生效只能由服务端的回读说了算，
         * 页面上提前变绿就是在替它撒谎。
         */
        async wbActionCommand(action, command) {
            if (!action || this.wbActionBusy[action.actionId]) return;
            this.wbActionBusy = Object.assign({}, this.wbActionBusy, { [action.actionId]: command });
            this.wbActionError = Object.assign({}, this.wbActionError, { [action.actionId]: '' });
            try {
                const updated = await RoverAdminApi.api(
                    '/api/agent/actions/' + encodeURIComponent(action.actionId) + '/' + command,
                    { method: 'POST' });
                this.wbReplaceAction(updated);
            } catch (e) {
                // 被拒（已被处理过、状态不允许）或执行异常：记在卡片上，并重新拉一次真实状态
                this.wbActionError = Object.assign({}, this.wbActionError,
                    { [action.actionId]: (e && e.message) ? e.message : '操作失败' });
                await this.wbLoadActions(this.wbActiveSessionId);
            } finally {
                const busy = Object.assign({}, this.wbActionBusy);
                delete busy[action.actionId];
                this.wbActionBusy = busy;
            }
        },

        /** 就地替换一条变更：不整表重取，避免列表顺序与正在看的卡片跳动。 */
        wbReplaceAction(updated) {
            if (!updated || !updated.actionId) return;
            const exists = this.wbActions.some(item => item.actionId === updated.actionId);
            this.wbActions = exists
                ? this.wbActions.map(item => (item.actionId === updated.actionId ? updated : item))
                : [updated].concat(this.wbActions);
        },

        /** 是否没有正在进行的操作（按钮据此禁用，避免双击发出两次请求）。 */
        wbActionIdle(action) {
            return action && !this.wbActionBusy[action.actionId];
        },

        wbActionTypeLabel(type) {
            return { ADJUST_ROUTE_TARGET_WEIGHT: '灰度权重调整' }[type] || type || '变更';
        },

        wbActionStatusLabel(status) {
            return {
                PENDING_APPROVAL: '待审批',
                EXECUTING: '执行中',
                VERIFYING: '验证中',
                SUCCESS: '已完成',
                FAILED: '失败',
                PRECONDITION_FAILED: '预检未通过',
                UNCERTAIN: '结果未知',
                REJECTED: '已拒绝',
                ROLLING_BACK: '回滚中',
                ROLLED_BACK: '已回滚',
                ROLLBACK_PRECONDITION_FAILED: '回滚预检未通过',
            }[status] || status || '';
        },

        /** 状态徽标色：绿=确认生效/已补偿，红=确认失败，黄=等你决定，灰=已拒绝，其余为进行中。 */
        wbActionBadge(status) {
            return {
                SUCCESS: 'ok',
                ROLLED_BACK: 'ok',
                FAILED: 'bad',
                PRECONDITION_FAILED: 'bad',
                ROLLBACK_PRECONDITION_FAILED: 'bad',
                UNCERTAIN: 'warn',
                PENDING_APPROVAL: 'warn',
                REJECTED: 'comp',
            }[status] || 'comp';
        },

        wbActionTarget(action) {
            if (!action) return '';
            return action.serviceName + ' · ' + (action.group ? '分组 ' + action.group : '不限分组');
        },

        /** 服务端 impact 形如「缩量：…（80 → 70）」，数字卡片上已有，只取冒号前的动作名当标签。 */
        wbActionKind(action) {
            return action && action.impact ? action.impact.split('：')[0] : '';
        },

        /**
         * 预计流量占比：审批真正要看的东西。
         *
         * 权重是路由表里的相对值，单独看不出影响——5 → 20 到底是多少流量，
         * 取决于同路由其他版本的权重。占比由服务端按整条路由算好带过来：
         * 让「批准」这个动作建立在「改完之后流量会变成什么样」上，而不是一个抽象数字。
         */
        wbActionShare(action) {
            if (!action || action.beforeTrafficPercent == null || action.desiredTrafficPercent == null) {
                return null;
            }
            return {
                from: action.beforeTrafficPercent.toFixed(1) + '%',
                to: action.desiredTrafficPercent.toFixed(1) + '%',
            };
        },

        /** 预览只有一行且不是差异（如「预览不可用：…」「无差异」）时直接露出，这类话审批人必须看到。 */
        wbActionPreviewNote(action) {
            const lines = (action && action.preview) || [];
            return lines.length === 1 && !/^(ADDED|REMOVED|MODIFIED)\b/.test(lines[0]) ? lines[0] : '';
        },

        /**
         * 把网关差异原文还原成「分组 / 改前 / 改后」表，让审批人看到整条路由而不止被改的那一行。
         * 原文是 targets 列表的 toString：[{serviceName=x, group=v1, weight=100}, …] → […]；
         * 对不上这个格式就返回 null，模板退回展示原文。
         */
        wbActionDiffRows(action) {
            const lines = (action && action.preview) || [];
            if (lines.length !== 1) return null;
            const halves = lines[0].split(' → ');
            if (halves.length !== 2) return null;
            const parse = (text) => {
                const map = new Map();
                for (const m of text.matchAll(/\{serviceName=([^,}]*), group=([^,}]*), weight=(\d+)\}/g)) {
                    map.set(m[2], Number(m[3]));
                }
                return map;
            };
            const before = parse(halves[0]);
            const after = parse(halves[1]);
            if (!before.size && !after.size) return null;
            const share = (map, group) => {
                const total = [...map.values()].reduce((sum, w) => sum + w, 0);
                if (!map.has(group)) return '—';
                const w = map.get(group);
                return w + '（' + (total ? (w / total * 100).toFixed(1) : '0.0') + '%）';
            };
            const groups = [...new Set([...before.keys(), ...after.keys()])];
            return groups.map(group => ({
                group,
                label: group || '不限分组',
                before: share(before, group),
                after: share(after, group),
                changed: before.get(group) !== after.get(group),
            }));
        },

        /**
         * 请求口径：用户当初要的到底是「权重 20」还是「20% 流量」。
         *
         * 这两者在小数量纲的路由上能差两个数量级，卡片上必须留着原始口径，
         * 否则事后只能看到换算结果，说不清批的究竟是什么。
         */
        wbActionRequestText(action) {
            if (!action) return '';
            if (!action.requestedUnit) return '权重 ' + action.desiredWeight + '（未声明单位）';
            if (action.requestedUnit === 'TRAFFIC_PERCENT') return '流量占比 ' + action.requestedValue + '%';
            return '权重值 ' + action.requestedValue;
        },

        /**
         * 卡片底部那行进度：只由服务端状态推出，一句话说清「现在到哪一步、有没有确认过」。
         *
         * 刻意不给未确认的变更画绿勾：网关说成功不等于目标达成，
         * 只有回读确认过才会显示「已确认」。
         */
        wbActionProgress(action) {
            const submitted = Boolean(action.applyOperationId);
            switch (action.status) {
                case 'PENDING_APPROVAL':
                    return { mark: '○', text: '尚未执行，等人工批准', tone: 'idle' };
                case 'REJECTED':
                    return { mark: '○', text: '已拒绝，未执行', tone: 'idle' };
                case 'EXECUTING':
                    return { mark: '●', text: '已批准，正在校验当前状态并提交', tone: 'run' };
                case 'VERIFYING':
                    return { mark: '●', text: '已提交，正在回读路由确认结果', tone: 'run' };
                case 'ROLLING_BACK':
                    return { mark: '●', text: '正在把权重补偿回变更前的值', tone: 'run' };
                case 'SUCCESS':
                    return { mark: '✓', text: '已提交并回读确认，权重已生效', tone: 'ok' };
                case 'ROLLED_BACK':
                    return { mark: '✓', text: '已补偿回滚并回读确认', tone: 'ok' };
                case 'PRECONDITION_FAILED':
                    return { mark: '!', text: '预检未通过，Gateway 未被改动', tone: 'bad' };
                case 'ROLLBACK_PRECONDITION_FAILED':
                    return { mark: '!', text: '当前权重已不是这次写入的值，未自动补偿', tone: 'bad' };
                case 'FAILED':
                    return {
                        mark: '!',
                        text: submitted ? '已提交但确认未达成' : '提交前失败，Gateway 未被改动',
                        tone: 'bad',
                    };
                case 'UNCERTAIN':
                    return { mark: '!', text: '已提交但结果未确认，请用原操作号核对', tone: 'bad' };
                default:
                    return { mark: '○', text: '', tone: 'idle' };
            }
        },

        /** 卡片角落的署名：谁提的、谁批的、什么时候——变更必须能追到人。 */
        wbActionMeta(action) {
            const parts = [];
            if (action.requestedBy) parts.push('提议 ' + action.requestedBy);
            if (action.approvedBy) parts.push('批准 ' + action.approvedBy);
            parts.push(this.fmtTime(action.updatedAtMillis));
            return parts.join(' · ');
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

        /**
         * 这条任务的解读是不是「还在写」：决定要不要闪烁光标与状态点。
         *
         * 除订阅在推之外还有第二个条件——屏幕上的字还没追上已收到的文本。少了这一条，
         * 任务定型的瞬间渲染会从半句直接跳到全文，观感上就是「整段蹦出来」。
         */
        wbIsStreaming(task) {
            if (!task) return false;
            const streamed = this.wbStreamTexts[task.taskId];
            const rendered = this.wbRenderTexts[task.taskId];
            if (typeof streamed === 'string' && typeof rendered === 'string' && rendered.length < streamed.length) {
                return true;
            }
            return Boolean(this.wbStreaming && this.wbStreamTaskId === task.taskId);
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
            this.wbStartClock();
            WB_EVENT_TYPES.forEach((type) => {
                source.addEventListener(type, (event) => this.wbApplyEvent(taskId, type, event));
            });
            source.onerror = () => this.wbStreamFailed(taskId);
        },

        /**
         * 每秒推进一次的时钟：让执行中步骤的耗时自己往上走。
         *
         * 没有它，耗时只在收到事件时刷新一次，看起来像卡住了；有它，「已进行 3.4s」是活的，
         * 等待期因此不像在干等。
         */
        wbStartClock() {
            if (this._wbClockTimer) return;
            this.wbNow = Date.now();
            this._wbClockTimer = setInterval(() => { this.wbNow = Date.now(); }, 1000);
        },

        wbStopClock() {
            if (!this._wbClockTimer) return;
            clearInterval(this._wbClockTimer);
            this._wbClockTimer = null;
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
            // 时钟跟着订阅走：没有订阅就不会再有「进行中」的步骤要找它计时
            this.wbStopClock();
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
                    // 快照是补齐既有内容（首连或断线重连），直接落到渲染位，不重放一遍打字机
                    this.wbStreamTexts = Object.assign({}, this.wbStreamTexts, { [taskId]: payload.analysis });
                    this.wbRenderTexts = Object.assign({}, this.wbRenderTexts, { [taskId]: payload.analysis });
                }
                if (typeof payload.thinking === 'string' && payload.thinking) {
                    this.wbThinkingTexts = Object.assign({}, this.wbThinkingTexts, { [taskId]: payload.thinking });
                    this.wbRenderThinking = Object.assign({}, this.wbRenderThinking, { [taskId]: payload.thinking });
                }
                return;
            }
            if (type === 'THINKING_DELTA' && typeof payload.text === 'string') {
                const current = this.wbThinkingTexts[taskId] || '';
                this.wbThinkingTexts = Object.assign({}, this.wbThinkingTexts, { [taskId]: current + payload.text });
                if (this.wbRenderThinking[taskId] === undefined) {
                    this.wbRenderThinking = Object.assign({}, this.wbRenderThinking, { [taskId]: '' });
                }
                if (!this.wbThinkingStart[taskId]) {
                    // 首个增量到达才算开始：模型排队与建连的时间不该算进「深度思考」
                    this.wbThinkingStart = Object.assign({}, this.wbThinkingStart, { [taskId]: Date.now() });
                }
                this.wbPumpStream();
                return;
            }
            if (type === 'ANALYSIS_DELTA' && typeof payload.text === 'string') {
                const current = this.wbStreamTexts[taskId] || '';
                this.wbStreamTexts = Object.assign({}, this.wbStreamTexts, { [taskId]: current + payload.text });
                // 首次增量时给出渲染起点：从零开始逐字长出来，正是「正在写」的观感来源
                if (this.wbRenderTexts[taskId] === undefined) {
                    this.wbRenderTexts = Object.assign({}, this.wbRenderTexts, { [taskId]: '' });
                }
                this.wbMarkThinkingDone(taskId);
                this.wbPumpStream();
                return;
            }
            if (type === 'TASK_CREATED' || type === 'TASK_STARTED') {
                this.wbMergeTask(Object.assign({}, this.wbTasks[taskId], { status: payload.status }));
                return;
            }
            if (type === 'STEP_STARTED' || type === 'STEP_COMPLETED' || type === 'STEP_FAILED') {
                this.wbMergeStep(taskId, payload.step, type);
                // 变更计划是在对话进行中产生的：它一落库就把右栏的卡片拉出来，
                // 不让用户等到回答收尾才知道「有一张待审批的单子」。
                if (payload.step && payload.step.type === 'ACTION_PROPOSAL' && type !== 'STEP_STARTED') {
                    this.wbLoadActions(this.wbActiveSessionId);
                }
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

        /**
         * 回答正文：一次提问只对应这一个气泡，里面就是模型（或规则）产出的那段话。
         *
         * 生成中优先显示流式增量、边推边出；定型后以任务结论为准，最后才退回这条 Agent 回复本身。
         * 「已继续调查…」这类受理播报属于过程，不是答案——它只在结论还没落到时间线上时临时兜底。
         */
        wbAnswerText(turn) {
            const task = turn && turn.task;
            if (task) {
                const streamed = this.wbStreamTexts[task.taskId];
                const rendered = this.wbRenderTexts[task.taskId];
                // 打字机还没追上就以进度为准：任务定型也不会让正文从半句跳成全文
                if (typeof streamed === 'string' && typeof rendered === 'string' && rendered.length < streamed.length) {
                    return rendered;
                }
            }
            if (task && this.wbIsStreaming(task)) {
                const streamed = this.wbStreamTexts[task.taskId];
                if (streamed) return streamed;
            }
            const analysis = task && task.result && task.result.aiAnalysis;
            return analysis || (turn.message && turn.message.content) || '';
        },

        /**
         * 打字机泵：把「已收到的文本」逐拍补进「已渲染的文本」。
         *
         * 上游是逐 token 流时落后量很小，每拍补几个字，屏幕上是匀速书写；
         * 上游一帧吐一大段（或重连补快照）时落后量大，按比例多补，很快追上但仍是长出来的。
         * 全部追平后停泵，不留空转的定时器。
         */
        wbPumpStream() {
            if (this._wbTypeTimer) return;
            this._wbTypeTimer = setInterval(() => {
                const answer = this.wbAdvance(this.wbStreamTexts, this.wbRenderTexts);
                const thinking = this.wbAdvance(this.wbThinkingTexts, this.wbRenderThinking);
                if (answer) this.wbRenderTexts = answer;
                if (thinking) this.wbRenderThinking = thinking;
                if (!answer && !thinking && !this.wbStreaming) this.wbStopPump();
            }, WB_TYPE_TICK_MILLIS);
        },

        /**
         * 把一批目标文本各自向前推一拍：答案与思考共用同一套节奏，只是落在不同的渲染位上。
         *
         * 没有变化时返回 null——泵据此判断是否已经追平，可以停下来。
         */
        wbAdvance(targets, rendered) {
            let changed = false;
            const next = Object.assign({}, rendered);
            Object.keys(targets).forEach((key) => {
                const full = targets[key] || '';
                const current = next[key] || '';
                if (current === full) return;
                // 目标文本被更短的快照覆盖（重连补齐）时以目标为准，只减不增
                if (current.length > full.length) {
                    next[key] = full;
                    changed = true;
                    return;
                }
                const lag = full.length - current.length;
                const step = Math.max(WB_TYPE_MIN_CHARS_PER_TICK, Math.ceil(lag / WB_TYPE_CATCH_UP_DIVISOR));
                next[key] = full.slice(0, current.length + step);
                changed = true;
            });
            return changed ? next : null;
        },

        wbStopPump() {
            if (!this._wbTypeTimer) return;
            clearInterval(this._wbTypeTimer);
            this._wbTypeTimer = null;
        },

        wbTaskTerminal(status) {
            return ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(status);
        },

        /** 已定型、不会再自己变化的任务：终态之外，等你补充信息的澄清点也算。 */
        wbTaskSettled(status) {
            return this.wbTaskTerminal(status) || status === 'WAITING_INPUT';
        },

        /** 任务仍可取消：还在排队或执行中；已定型或待补充的任务取消无意义。 */
        wbCanCancel(task) {
            return task && (task.status === 'PENDING' || task.status === 'RUNNING');
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
                status: step.status || 'PENDING',
                title: step.error || step.outputSummary || step.inputSummary || '',
                // 进行中的说明在 inputSummary（「正在查什么」），结束时结果说明在 outputSummary
                detail: step.error || step.outputSummary || step.inputSummary || '',
                duration: this.wbStepDuration(step),
            }));
        },

        /**
         * 步骤耗时：进行中按当前时刻算（靠 {@link #wbNow} 每秒推进），完成后用真实起止时刻。
         *
         * 让「它确实在干活、干了多久」变成可见事实，而不是只有一个转圈。
         */
        wbStepDuration(step) {
            const started = Number(step && step.startedAtMillis);
            if (!Number.isFinite(started) || started <= 0) return '';
            const finished = Number(step && step.completedAtMillis);
            const end = Number.isFinite(finished) && finished > 0 ? finished : (this.wbNow || Date.now());
            return this.wbDurationText(Math.max(0, end - started));
        },

        wbDurationText(millis) {
            if (!Number.isFinite(millis) || millis < 0) return '';
            if (millis < 1000) return Math.round(millis) + 'ms';
            const seconds = millis / 1000;
            if (seconds < 10) return seconds.toFixed(1) + 's';
            if (seconds < 60) return Math.round(seconds) + 's';
            const minutes = Math.floor(seconds / 60);
            return minutes + 'm ' + Math.round(seconds % 60) + 's';
        },

        /**
         * 当前正在做的事：取最后一个执行中步骤的说明。
         *
         * 执行中的说明写在 {@code inputSummary}（「正在查什么」），结束时结果写在
         * {@code outputSummary}，两者分开，所以这里只认前者，不会把已完成的结果当成进度。
         */
        wbCurrentAction(task) {
            const steps = (task && task.steps) || [];
            const running = steps.filter(step => step.status === 'RUNNING').pop();
            if (!running) return '';
            return running.inputSummary || running.name || this.wbStepTypeLabel(running.type);
        },

        /**
         * 思考正文：同样是「追平前以渲染进度为准」。
         *
         * 思考由模型逐字产出，但一帧吐一大段时直接渲染会整段蹦出，打字机让它是长出来的。
         */
        wbThinkingText(task) {
            if (!task) return '';
            const full = this.wbThinkingTexts[task.taskId] || '';
            const rendered = this.wbRenderThinking[task.taskId];
            return typeof rendered === 'string' && rendered.length < full.length ? rendered : full;
        },

        /** 是否仍在思考：有思考内容，且答案还没开始产出、任务也还没定型。 */
        wbThinkingLive(task) {
            if (!task || !this.wbThinkingTexts[task.taskId]) return false;
            return !this.wbThinkingEnd[task.taskId] && !this.wbTaskSettled(task.status);
        },

        /** 思考面板标题：进行中报「正在深度思考」，结束后给出实际用时。 */
        wbThinkingLabel(task) {
            const used = this.wbThinkingDuration(task && task.taskId);
            if (this.wbThinkingLive(task)) {
                return used ? '正在深度思考 · ' + used : '正在深度思考';
            }
            return used ? '已深度思考 · 用时 ' + used : '已深度思考';
        },

        /** 思考用时：进行中按当前时刻算（靠每秒时钟推进），结束后取固定的首尾差。 */
        wbThinkingDuration(taskId) {
            const start = this.wbThinkingStart[taskId];
            if (!start) return '';
            const end = this.wbThinkingEnd[taskId] || this.wbNow || Date.now();
            return this.wbDurationText(Math.max(0, end - start));
        },

        /**
         * 思考面板是否展开：思考中默认展开（这时它就是主要内容），出答案后收拢成一行，把位置让给正文。
         * 用户手动开合过就听用户的，不跟自动策略较劲。
         */
        wbThinkOpen(task) {
            if (!task) return false;
            const manual = this.wbThinkManual[task.taskId];
            return manual !== undefined ? manual : this.wbThinkingLive(task);
        },

        wbToggleThink(taskId) {
            const task = this.wbTasks[taskId];
            if (!task) return;
            this.wbThinkManual = Object.assign({}, this.wbThinkManual, { [taskId]: !this.wbThinkOpen(task) });
        },

        /** 答案开始产出即视为「思考结束」：用时只算到这一刻，之后是正文生成的时间。 */
        wbMarkThinkingDone(taskId) {
            if (this.wbThinkingStart[taskId] && !this.wbThinkingEnd[taskId]) {
                this.wbThinkingEnd = Object.assign({}, this.wbThinkingEnd, { [taskId]: Date.now() });
            }
        },

        /**
         * 让正在生成的思考面板跟到底部。
         *
         * 思考面板是独立滚动容器（有最大高度），新内容超出可视区后要自己滚——不跟随的话，
         * 用户看到的永远是最开头那几句，反而不像在思考。
         */
        wbFollowThinking() {
            this.$nextTick(() => {
                const root = this.$el;
                if (!root || !root.querySelectorAll) return;
                root.querySelectorAll('.wb-think-body.live').forEach((el) => {
                    el.scrollTop = el.scrollHeight;
                });
            });
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
                PLANNING: '调查规划',
                ANSWER: '回答',
                TARGET_RESOLUTION: '目标解析',
                ROUTE_INVESTIGATION: '读取路由',
                INSTANCE_INVESTIGATION: '读取实例',
                METRIC_INVESTIGATION: '读取指标',
                TRACE_INVESTIGATION: '读取追踪',
                CONFIG_INVESTIGATION: '读取配置',
                EVENT_INVESTIGATION: '读取事件',
                DIAGNOSIS: '生成结论',
                ACTION_PROPOSAL: '变更计划',
                MEMORY: '记忆',
                AI_EXPLANATION: 'AI 解读',
            }[type] || type || '步骤';
        },

        /** 任务类型：一次执行以什么形态进行，后端 taskType 直接给出，不靠文本猜。 */
        wbTaskTypeLabel(type) {
            return {
                CONVERSATION: '智能问答',
                INVESTIGATION: '故障调查',
            }[type] || '智能问答';
        },

        wbTaskTypeBadge(type) {
            return {
                CONVERSATION: 'ok', INVESTIGATION: 'comp',
            }[type] || 'ok';
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
         * 任务卡是否展开。默认收起，只留一行进度；用户点开才看步骤和证据。
         */
        wbTaskOpen(task) {
            if (!task) return false;
            return this.wbDetailOpen[task.taskId] === true;
        },

        wbRecallCards(task) {
            return (task && task.recalls) || [];
        },

        wbPickRecall(card) {
            if (!card || !card.value || this.wbSending) return;
            this.wbRecallSessionId = card.value;
            this.wbInput = card.label;
            this.wbSend();
        },

        wbToggleTask(taskId) {
            const task = this.wbTasks[taskId];
            if (!task) return;
            this.wbDetailOpen = Object.assign({}, this.wbDetailOpen, { [taskId]: !this.wbTaskOpen(task) });
        },

        /**
         * 折叠条上的进度：有步骤在执行就直接说「正在读什么」，否则给「3/4 步」。
         *
         * 折叠状态下也要看得出它在动——这是「正在思考」最直接的信号，
         * 只给一个完成度数字，等待期就只剩一个不动的计数。
         */
        wbTaskStepText(task) {
            const steps = this.wbSteps(task);
            if (!steps.length) return '';
            const running = steps.filter(step => step.state === 'running').pop();
            if (running) return running.detail || ('正在' + running.label + '…');
            return steps.filter(step => step.state === 'done').length + '/' + steps.length + ' 步';
        },

        wbTaskToggleHint(task) {
            return this.wbTaskOpen(task) ? '收起过程' : '查看调查过程';
        },

        wbSessionAt(session) {
            return session.lastActiveAtMillis || session.createdAtMillis || 0;
        },

        wbStatusLabel(status) {
            return {
                PENDING: '排队中', RUNNING: '调查中', WAITING_INPUT: '待补充', COMPLETED: '已完成',
                FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '已中断',
            }[status] || status || '等待中';
        },

        wbStatusBadge(status) {
            return {
                COMPLETED: 'ok', FAILED: 'bad', CANCELLED: 'warn', RUNNING: 'warn', WAITING_INPUT: 'warn',
                INTERRUPTED: 'warn',
            }[status] || 'comp';
        },

        wbIncidentStatusLabel(status) {
            return { OPEN: '已登记', INVESTIGATING: '调查中', RESOLVED: '已结论' }[status] || status || '未知';
        },

        wbIncidentBadge(status) {
            return { RESOLVED: 'ok', INVESTIGATING: 'warn', OPEN: 'comp' }[status] || 'comp';
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

        /** 本次没能确认的部分：取数边界随结论一起产出，折叠状态下也要能看见提示。 */
        wbUncertainties(task) {
            return (task && task.result && task.result.limitations) || [];
        },

        /** 没能确认的部分的一句话摘要，供折叠状态直接展示，不必展开详情。 */
        wbUncertaintyText(task) {
            return this.wbUncertainties(task).join('；');
        },

        wbStepTone(status) {
            return { COMPLETED: 'ok', FAILED: 'bad', RUNNING: 'warn' }[status] || 'comp';
        },
    },
};