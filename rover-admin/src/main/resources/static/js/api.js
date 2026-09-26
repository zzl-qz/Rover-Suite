/**
 * Admin API：统一 fetch + JSON 错误信息 + CSRF 头 + 401 跳登录。
 *
 * 写请求（非 GET/HEAD）自动附 CSRF 头：令牌优先读 XSRF-TOKEN cookie，
 * 拿不到时问一次 /api/auth/status，让服务端把 cookie 铺上。
 * 401 视为会话失效：API 请求回 401 JSON，这里负责把浏览器送回登录页并带上回跳地址。
 */
(function () {
    'use strict';

    var HEADER = 'X-XSRF-TOKEN';
    var token = '';
    var probed = false;

    function fromCookie() {
        var match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    async function ensureToken() {
        var cookieToken = fromCookie();
        if (cookieToken) {
            token = cookieToken;
            return token;
        }
        if (!probed) {
            probed = true;
            try {
                var res = await fetch('/api/auth/status', { headers: { Accept: 'application/json' } });
                if (res.ok) {
                    var status = await res.json();
                    token = status.csrfToken || '';
                    if (status.csrfHeaderName) HEADER = status.csrfHeaderName;
                } else {
                    probed = false;
                }
            } catch (e) {
                probed = false;
            }
        }
        return token || fromCookie();
    }

    async function api(url, options) {
        var opts = Object.assign({}, options);
        var method = String(opts.method || 'GET').toUpperCase();
        if (method !== 'GET' && method !== 'HEAD') {
            var csrf = await ensureToken();
            if (csrf) {
                opts.headers = Object.assign({}, opts.headers);
                opts.headers[HEADER] = csrf;
            }
        }
        var res = await fetch(url, opts);
        if (res.status === 401 && url.indexOf('/api/') === 0) {
            var back = encodeURIComponent(window.location.pathname + window.location.search);
            window.location.href = '/login.html?redirect=' + back;
            throw new Error('登录状态已失效，请重新登录');
        }
        var json = null;
        try { json = await res.json(); } catch (e) { /* 非 JSON */ }
        if (!res.ok) {
            // 403（CSRF 失败）/409（会话已有任务）/429（限流或繁忙）同样透出服务端文案，不吞成裸 HTTP 码；
            // 同时把状态码与错误体挂到异常上，调用方才能按 code / taskId 做分支处理。
            var error = new Error((json && json.message) ? json.message : ('HTTP ' + res.status));
            error.status = res.status;
            error.body = json;
            throw error;
        }
        return json;
    }

    /** 登出：POST 带 CSRF（GET 登出无法受 CSRF 保护），成功后回登录页。 */
    async function logout() {
        var csrf = await ensureToken();
        var headers = {};
        if (csrf) headers[HEADER] = csrf;
        try {
            await fetch('/api/logout', { method: 'POST', headers: headers });
        } finally {
            window.location.href = '/login.html';
        }
    }

    window.RoverAdminApi = { api: api, logout: logout };
})();