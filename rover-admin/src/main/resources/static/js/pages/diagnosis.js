/** 智能诊断页：创建只读任务，沿用页面轮询查看执行结果。 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.diagnosis = {
    data() {
        return {
            diagnosisForm: { path: '/api/demo/tt', question: '这个路径为什么调用失败？' },
            diagnosisTask: null,
            diagnosisError: null,
            diagnosisSubmitting: false,
            diagnosisFetching: false,
        };
    },

    computed: {
        diagnosisTerminal() {
            return this.diagnosisTask && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(this.diagnosisTask.status);
        },
    },

    methods: {
        async submitDiagnosis() {
            if (this.diagnosisSubmitting || this.diagnosisFetching || (this.diagnosisTask && !this.diagnosisTerminal)) return;
            const path = this.diagnosisForm.path.trim();
            const question = this.diagnosisForm.question.trim();
            if (!path.startsWith('/') || path.startsWith('//') || !question) {
                this.diagnosisError = '请输入以 / 开头的路径和诊断问题。';
                return;
            }
            this.diagnosisSubmitting = true;
            this.diagnosisError = null;
            try {
                const task = await RoverAdminApi.api('/api/agent/diagnoses', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path, question }),
                });
                if (!task || !task.taskId) throw new Error('服务端未返回任务 ID');
                this.diagnosisTask = { ...task, path, question };
                await this.fetchDiagnosis();
            } catch (e) {
                this.diagnosisError = e.message;
            } finally {
                this.diagnosisSubmitting = false;
            }
        },
        async fetchDiagnosis() {
            if (!this.diagnosisTask || this.diagnosisFetching) return;
            const taskId = this.diagnosisTask.taskId;
            this.diagnosisFetching = true;
            try {
                const task = await RoverAdminApi.api('/api/agent/diagnoses/' + encodeURIComponent(taskId));
                if (!task || task.taskId !== taskId) throw new Error('服务端返回了错误的任务数据');
                if (this.diagnosisTask && this.diagnosisTask.taskId === taskId) {
                    this.diagnosisTask = task;
                    this.diagnosisError = null;
                }
            } catch (e) {
                if (this.diagnosisTask && this.diagnosisTask.taskId === taskId) {
                    this.diagnosisError = '查询诊断进度失败：' + e.message;
                }
            } finally {
                this.diagnosisFetching = false;
            }
        },
        diagnosisStatusLabel(status) {
            return { PENDING: '排队中', RUNNING: '诊断中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[status] || status || '等待中';
        },
        diagnosisStatusBadge(status) {
            return { COMPLETED: 'ok', FAILED: 'bad', CANCELLED: 'warn' }[status] || 'comp';
        },
        diagnosisConfidenceLabel(value) {
            return { HIGH: '高', MEDIUM: '中', LOW: '低', UNKNOWN: '未知' }[value] || value || '未标注';
        },
        diagnosisHypothesisLabel(status) {
            return { CONFIRMED: '确认', REJECTED: '排除', UNKNOWN: '无法验证' }[status] || status || '未验证';
        },
        diagnosisHypothesisBadge(status) {
            return { CONFIRMED: 'bad', REJECTED: 'ok', UNKNOWN: 'warn' }[status] || 'comp';
        },
    },
};
