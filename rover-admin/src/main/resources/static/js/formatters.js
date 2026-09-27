/** 数字/时间格式化。shared.js 会 spread 进 Vue methods，模板里 this.num 照旧能用。 */
function num(v) {
    if (v === null || v === undefined) return '0';
    if (typeof v === 'number') {
        return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2);
    }
    return String(v);
}
function fmtTime(millis) {
    if (!millis) return '-';
    const d = new Date(millis);
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
function fmtSecond(epochSecond) {
    const d = new Date(epochSecond * 1000);
    const pad = n => String(n).padStart(2, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
function fmtFullTime(millis) { return fmtTime(millis); }
function fmtUptime(seconds) {
    if (seconds === null || seconds === undefined) return '-';
    const s = Number(seconds);
    if (s < 60) return Math.floor(s) + 's';
    if (s < 3600) return Math.floor(s / 60) + 'm ' + Math.floor(s % 60) + 's';
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return (h >= 24 ? Math.floor(h / 24) + 'd ' : '') + (h % 24) + 'h ' + m + 'm';
}
function fmtHeap(jvm) {
    if (!jvm) return '-';
    return (jvm.heapUsedPercent || 0).toFixed(1) + '%';
}
function fmtCpu(jvm) {
    if (!jvm || jvm.processCpuPercent === undefined) return '-';
    return Number(jvm.processCpuPercent).toFixed(1) + '%';
}
function idleText(millis) {
    if (!millis) return '-';
    const sec = Math.max(0, Math.floor((Date.now() - millis) / 1000));
    if (sec < 5) return '刚刚';
    if (sec < 60) return sec + 's';
    return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
}
/** 纵轴好看的上限：峰值 * 1.15 再取 1/2/5×10^n。 */
function niceCeil(value) {
    if (!value || value <= 0) return 1;
    const target = value * 1.15;
    const exp = Math.floor(Math.log10(target));
    const f = Math.pow(10, exp);
    const n = target / f;
    let nice = 10;
    if (n <= 1) nice = 1;
    else if (n <= 2) nice = 2;
    else if (n <= 5) nice = 5;
    return Math.ceil(nice * f);
}

/** HTML 转义：模型输出与后端文案一律先转义再拼标签，这是渲染层唯一的安全边界。 */
function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/** 行内语法：先代码再强调，避免代码里的 * 被当成强调标记；链接只认 http(s)。 */
function mdInline(text) {
    return text
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
        .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
            '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
}

/**
 * 轻量 Markdown → 安全 HTML：只渲染模型回复真正会出现的语法。
 *
 * 为什么手写而不是引 markdown-it / DOMPurify：项目约定前端只用仓库内的轻量资源，
 * 而「整段先转义、再按行匹配白名单语法」本身就堵住了注入面——模型输出里的 <script>
 * 只会以文字出现。支持围栏代码块、标题、有序/无序列表、引用、分隔线与行内语法，
 * 不支持表格与内嵌 HTML（渲染不了就如实当文本显示）。
 *
 * {@code streaming} 为真时把光标插进最后一个块级元素的末尾，让光标贴在上一个字符后面，
 * 而不是另起一行——这是流式输出的手感来源。
 */
function mdHtml(text, streaming) {
    const lines = escapeHtml(text === null || text === undefined ? '' : text).split(/\r?\n/);
    const blocks = [];
    let list = null;
    let inCode = false;
    const codeLines = [];
    const paragraph = [];

    const closeList = () => {
        if (list) {
            blocks.push('</' + list + '>');
            list = null;
        }
    };
    const closeCode = () => {
        if (!inCode) return;
        blocks.push('<pre class="wb-code"><code>' + codeLines.join('\n') + '</code></pre>');
        codeLines.length = 0;
        inCode = false;
    };
    // 段落是缓冲区：连续文本行合成一段，空行或块级语法才收口（与主流渲染器一致）
    const closeParagraph = () => {
        if (paragraph.length) {
            blocks.push('<p>' + paragraph.join('<br>') + '</p>');
            paragraph.length = 0;
        }
    };
    const closeAll = () => {
        closeParagraph();
        closeList();
        closeCode();
    };
    const openList = (kind) => {
        if (list === kind) return;
        closeList();
        blocks.push('<' + kind + '>');
        list = kind;
    };

    lines.forEach((raw) => {
        const line = raw.replace(/\s+$/, '');
        if (/^```/.test(line.trim())) {
            closeParagraph();
            closeList();
            if (inCode) closeCode();
            else inCode = true;
            return;
        }
        if (inCode) {
            codeLines.push(line);
            return;
        }
        if (!line.trim()) {
            closeAll();
            return;
        }
        if (list) {
            // 列表项内部允许折行：缩进且不构成新列表项的行并入上一条
            const last = blocks.length - 1;
            const item = /^\s*([-*•]|\d+[.)])\s+/.test(line);
            if (!item && /^\s{2,}\S/.test(line)) {
                blocks[last] = blocks[last].replace(/<\/li>$/, '<br>' + mdInline(line.trim()) + '</li>');
                return;
            }
        }
        const heading = /^(#{1,4})\s+(.*)$/.exec(line);
        if (heading) {
            closeAll();
            const level = Math.min(heading[1].length + 2, 6);
            blocks.push('<' + 'h' + level + '>' + mdInline(heading[2]) + '</h' + level + '>');
            return;
        }
        const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);
        if (bullet) {
            closeParagraph();
            closeCode();
            openList('ul');
            blocks.push('<li>' + mdInline(bullet[1]) + '</li>');
            return;
        }
        const ordered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
        if (ordered) {
            closeParagraph();
            closeCode();
            openList('ol');
            blocks.push('<li>' + mdInline(ordered[1]) + '</li>');
            return;
        }
        const quote = /^\s*&gt;\s?(.*)$/.exec(line);
        if (quote) {
            closeAll();
            blocks.push('<blockquote>' + mdInline(quote[1]) + '</blockquote>');
            return;
        }
        if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
            closeAll();
            blocks.push('<hr>');
            return;
        }
        closeList();
        closeCode();
        paragraph.push(mdInline(line));
    });
    closeAll();

    let html = blocks.join('');
    if (streaming) {
        const caret = '<span class="wb-caret"></span>';
        const tail = ['</p>', '</li>', '</pre>', '</h6>', '</h5>', '</h4>', '</h3>']
            .map(tag => ({ tag, at: html.lastIndexOf(tag) }))
            .filter(item => item.at >= 0)
            .sort((a, b) => b.at - a.at)[0];
        html = tail ? html.slice(0, tail.at) + caret + html.slice(tail.at) : html + caret;
    }
    return html;
}

window.RoverAdminFormatters = {
    num, fmtTime, fmtSecond, fmtFullTime, fmtUptime, fmtHeap, fmtCpu, idleText, niceCeil, escapeHtml, mdHtml,
};
