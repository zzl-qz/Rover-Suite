/**
 * 模型配置页：预设 + 自定义、保存即生效、连接测试与效果验证。
 *
 * 密钥只在这里被"写"一次，读回来永远是掩码：留空 = 不改，显式点「清除已存密钥」才会清除。
 * 这样即使用户只是想改模型名，也不会因为表单里没有密钥而把已存的密钥抹掉。
 */
window.RoverAdminPages = window.RoverAdminPages || {};
window.RoverAdminPages.model = {
    data() {
        return {
            modelConfig: null,
            modelError: null,
            modelForm: {
                enabled: false,
                baseUrl: '',
                model: '',
                apiKey: '',
                timeoutSeconds: 30,
            },
            modelPresetIndex: '',
            modelClearKey: false,
            modelSaving: false,
            modelTesting: false,
            modelVerifying: false,
            modelTestResult: null,
            modelVerifyResult: null,
        };
    },

    computed: {
        modelPresets() {
            return (this.modelConfig && this.modelConfig.presets) || [];
        },
        modelConfigured() { return Boolean(this.modelConfig && this.modelConfig.configured); },
        modelAvailable() { return Boolean(this.modelConfig && this.modelConfig.available); },
        modelStateBadge() {
            if (!this.modelConfigured) return 'comp';
            if (!this.modelAvailable) return 'bad';
            return (this.modelConfig && this.modelConfig.applied) ? 'ok' : 'restart';
        },
        modelStateLabel() {
            if (!this.modelConfig) return '未知';
            if (!this.modelConfigured) return '未配置';
            if (!this.modelAvailable) return '不可用';
            return this.modelConfig.applied ? '已生效' : '待生效';
        },
        modelSourceLabel() {
            const source = this.modelConfig && this.modelConfig.source;
            return { FILE: '页面保存', ENV: '环境变量播种', NONE: '未配置' }[source] || '-';
        },
        modelKeyHint() {
            if (this.modelClearKey) return '保存后会清除已存密钥，适合换成本地无鉴权模型。';
            if (this.modelConfig && this.modelConfig.apiKeyMasked) return '已存密钥，留空表示不改动。';
            return '本地无鉴权的模型服务（Ollama / vLLM）可以留空。';
        },
        /** 表单与已存配置是否有差异；只是提示，不阻止保存。 */
        modelDirty() {
            const config = this.modelConfig;
            if (!config) return false;
            const form = this.modelForm;
            if (Boolean(form.enabled) !== Boolean(config.enabled)) return true;
            if (String(form.baseUrl || '').trim() !== String(config.baseUrl || '')) return true;
            if (String(form.model || '').trim() !== String(config.model || '')) return true;
            if (Number(form.timeoutSeconds) !== Number(config.timeoutSeconds)) return true;
            return Boolean(form.apiKey) || this.modelClearKey;
        },
    },

    methods: {
        async fetchModelConfig() {
            this.modelError = null;
            try {
                const config = await RoverAdminApi.api('/api/model/config');
                this.modelConfig = config;
                this.syncModelForm(config);
            } catch (e) {
                this.modelError = e.message;
            }
        },
        /** 用服务端状态重置表单：密钥永远留空，避免把掩码当成真密钥发回去。 */
        syncModelForm(config) {
            this.modelForm = {
                enabled: Boolean(config.enabled),
                baseUrl: config.baseUrl || '',
                model: config.model || '',
                apiKey: '',
                timeoutSeconds: config.timeoutSeconds || 30,
            };
            this.modelPresetIndex = '';
            this.modelClearKey = false;
        },
        /**
         * 套用预设后立刻把下拉复位：否则再选同一个预设不会触发 change，
         * 而且下拉会一直显示某个"看起来还在生效"的服务商，与手改后的地址矛盾。
         */
        applyModelPreset() {
            const index = this.modelPresetIndex;
            if (index === '' || index === null) return;
            const preset = this.modelPresets[Number(index)];
            if (!preset) return;
            this.modelForm.baseUrl = preset.baseUrl;
            this.modelForm.model = preset.model;
            this.modelPresetIndex = '';
        },
        modelPayload() {
            const payload = {
                enabled: Boolean(this.modelForm.enabled),
                baseUrl: String(this.modelForm.baseUrl || '').trim(),
                model: String(this.modelForm.model || '').trim(),
                timeoutSeconds: Number(this.modelForm.timeoutSeconds) || 30,
            };
            // 留空 = 不改：不把空串发过去，否则服务端会当成"清空密钥"
            if (this.modelClearKey) {
                payload.clearApiKey = true;
            } else if (this.modelForm.apiKey) {
                payload.apiKey = this.modelForm.apiKey;
            }
            return payload;
        },
        async saveModelConfig() {
            this.modelSaving = true;
            this.modelError = null;
            try {
                const result = await RoverAdminApi.api('/api/model/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.modelPayload()),
                });
                this.modelConfig = result;
                this.syncModelForm(result);
                this.toast(result.available ? 'success' : 'error', result.message || '模型配置已保存');
            } catch (e) {
                this.modelError = e.message;
                this.toast('error', e.message);
            } finally {
                this.modelSaving = false;
            }
        },
        async testModelConfig() {
            this.modelTesting = true;
            this.modelTestResult = null;
            try {
                this.modelTestResult = await RoverAdminApi.api('/api/model/test', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.modelPayload()),
                });
            } catch (e) {
                this.modelTestResult = { ok: false, errorCode: 'UNKNOWN', message: e.message, latencyMs: -1 };
            } finally {
                this.modelTesting = false;
            }
        },
        async verifyModelConfig() {
            this.modelVerifying = true;
            this.modelVerifyResult = null;
            try {
                this.modelVerifyResult = await RoverAdminApi.api('/api/model/verify', { method: 'POST' });
            } catch (e) {
                this.modelVerifyResult = { ok: false, errorCode: 'UNKNOWN', message: e.message, latencyMs: -1 };
            } finally {
                this.modelVerifying = false;
            }
        },
        /** 错误码 → 白话标签，页面不直接甩 AUTH/NETWORK 这种码。 */
        modelErrorLabel(code) {
            return {
                AUTH: '鉴权失败',
                PERMISSION: '无访问权限',
                QUOTA: '额度或频率受限',
                MODEL_NOT_FOUND: '模型不存在',
                BAD_REQUEST: '请求不被接受',
                NETWORK: '网络不通',
                SERVER: '服务端错误',
                TIMEOUT: '请求超时',
                NOT_CONFIGURED: '尚未配置',
                UNAVAILABLE: '当前不可用',
                UNKNOWN: '未识别错误',
            }[code] || (code || '未识别错误');
        },
        modelErrorAdvice(code) {
            return {
                AUTH: '核对 API Key 是否正确、是否已过期。',
                PERMISSION: '该密钥没有访问此模型的权限，换密钥或换模型。',
                QUOTA: '额度用尽或触发限流，稍后重试或换密钥。',
                MODEL_NOT_FOUND: '模型名写错了或该服务没有这个模型。',
                BAD_REQUEST: '服务地址或模型名不被接受，确认是否要带 /v1 这类路径后缀。',
                NETWORK: '连不上服务地址：确认地址、端口与出网策略。',
                SERVER: '模型服务端出错，稍后重试或联系服务商。',
                TIMEOUT: '8 秒内没回包：地址不可达或模型响应太慢。',
                NOT_CONFIGURED: '填写服务地址、模型名与密钥后保存。',
                UNAVAILABLE: '看「最近错误」并重新保存配置。',
                UNKNOWN: '看下方原始信息与 Admin 日志。',
            }[code] || '';
        },
        modelAppliedAtText() {
            const appliedAt = this.modelConfig && this.modelConfig.appliedAt;
            if (!appliedAt) return '从未生效';
            const millis = Date.parse(appliedAt);
            return Number.isNaN(millis) ? appliedAt : this.fmtTime(millis);
        },
    },
};