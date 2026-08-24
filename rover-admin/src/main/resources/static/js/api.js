/** Admin API：统一 fetch + JSON 错误信息。 */
async function api(url, options) {
    const res = await fetch(url, options);
    let json = null;
    try { json = await res.json(); } catch (e) { /* 非 JSON */ }
    if (!res.ok) {
        throw new Error((json && json.message) ? json.message : ('HTTP ' + res.status));
    }
    return json;
}

window.RoverAdminApi = { api };
