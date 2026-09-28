/*
 * 登录页：向服务端要 CSRF 令牌，放进表单后提交。口令校验在服务端。
 */
(function () {
    'use strict';

    var form = document.getElementById('loginForm');
    var errorBanner = document.getElementById('errorBanner');
    var submitBtn = document.getElementById('submitBtn');

    var params = new URLSearchParams(window.location.search);
    if (params.get('error')) {
        errorBanner.hidden = false;
    }

    var redirect = params.get('redirect');
    if (redirect) {
        var back = document.createElement('input');
        back.type = 'hidden';
        back.name = 'redirect';
        back.value = redirect;
        form.appendChild(back);
    }

    fetch('/api/auth/status', { headers: { Accept: 'application/json' } })
        .then(function (res) { return res.ok ? res.json() : null; })
        .then(function (status) {
            if (status && status.authenticated) {
                window.location.href = redirect && redirect.startsWith('/') && !redirect.startsWith('//')
                    ? redirect : '/';
                return;
            }
            var token = document.createElement('input');
            token.type = 'hidden';
            token.name = (status && status.csrfParameterName) || '_csrf';
            token.value = (status && status.csrfToken) || '';
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
