/*
 * 登录页脚本：只做三件事 —— 判断是否启用鉴权、铺 CSRF 隐藏域、提交时防重复点击。
 * 口令校验完全交给服务端的 Spring Security，前端不接触、不缓存任何凭据。
 */
(function () {
    'use strict';

    var form = document.getElementById('loginForm');
    var errorBanner = document.getElementById('errorBanner');
    var errorDetail = document.getElementById('errorDetail');
    var openBanner = document.getElementById('openBanner');
    var submitBtn = document.getElementById('submitBtn');

    var params = new URLSearchParams(window.location.search);
    if (params.get('error')) {
        errorBanner.hidden = false;
        errorDetail.textContent = params.get('error') === '1' ? '' : params.get('error');
    }

    // 会话失效时前端会带 ?redirect= 过来，随表单提交回去，登录后直接回到原页面（服务端仍会做站内校验）。
    var redirect = params.get('redirect');
    if (redirect) {
        var back = document.createElement('input');
        back.type = 'hidden';
        back.name = 'redirect';
        back.value = redirect;
        form.appendChild(back);
    }

    // 先问服务端当前鉴权配置，未启用时直接引导进入控制台，不要给一个永远登不进去的表单。
    fetch('/api/auth/status', { headers: { Accept: 'application/json' } })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (status) {
            if (!status) {
                // 状态读不到（网关 5xx / 网络异常）：保守地保留表单，让服务端给出最终判定。
                form.hidden = false;
                return;
            }
            if (!status.authEnabled) {
                errorBanner.hidden = true;
                openBanner.hidden = false;
                return;
            }
            var token = document.createElement('input');
            token.type = 'hidden';
            token.name = status.csrfParameterName || '_csrf';
            token.value = status.csrfToken || '';
            form.appendChild(token);
            form.hidden = false;
        })
        .catch(function () {
            form.hidden = false;
        });

    form.addEventListener('submit', function () {
        submitBtn.disabled = true;
        submitBtn.textContent = '登录中...';
    });
})();