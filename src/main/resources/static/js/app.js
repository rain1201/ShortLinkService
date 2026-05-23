(function () {
    const tabs = document.querySelectorAll('.tab');
    const tabContents = document.querySelectorAll('.tab-content');

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            tabs.forEach(t => t.classList.remove('active'));
            tabContents.forEach(tc => tc.classList.remove('active'));
            tab.classList.add('active');
            const target = document.getElementById('tab-' + tab.dataset.tab);
            if (target) target.classList.add('active');
        });
    });

    function showResult(el, type, html) {
        el.className = 'result-box ' + type;
        el.innerHTML = html;
        el.classList.remove('hidden');
    }

    function hideResult(el) {
        el.classList.add('hidden');
    }

    function setLoading(btn, loading) {
        if (loading) {
            btn.classList.add('loading');
            btn.disabled = true;
        } else {
            btn.classList.remove('loading');
            btn.disabled = false;
        }
    }

    function getShortUrl(id) {
        const loc = window.location;
        return loc.protocol + '//' + loc.host + '/' + id;
    }

    function parseInfo(text) {
        const m = text.match(/Shortlink \[idx=(\d+), originalUrl=(.+), viewCount=(\d+), createdAt=(\d+), expireAfter=(\d+)\]/);
        if (m) {
            return { idx: m[1], originalUrl: m[2], viewCount: m[3], createdAt: m[4], expireAfter: m[5], raw: text };
        }
        return null;
    }

    function formatDate(ts) {
        const d = new Date(ts * 1000);
        return d.toLocaleString();
    }

    function copyToClipboard(text, btn) {
        navigator.clipboard.writeText(text).then(() => {
            const orig = btn.textContent;
            btn.textContent = '已复制';
            setTimeout(() => btn.textContent = orig, 1500);
        });
    }

    const createForm = document.getElementById('createForm');
    const createResult = document.getElementById('createResult');
    const createBtn = document.getElementById('createBtn');
    const captchaStatus = document.getElementById('captchaStatus');
    const captchaText = document.getElementById('captchaText');
    const captchaDot = captchaStatus.querySelector('.dot');

    createForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        hideResult(createResult);

        const url = document.getElementById('createUrl').value.trim();
        const expireAfter = parseInt(document.getElementById('createExpire').value) || 1000000000;
        const updateCode = document.getElementById('createCode').value.trim();

        captchaDot.className = 'dot running';
        captchaText.textContent = '正在计算 PoW 验证... 请稍候';
        setLoading(createBtn, true);

        try {
            const { captcha, time } = await Captcha.generate(
                ['url', 'expireAfter'], [url, String(expireAfter)]
            );

            captchaDot.className = 'dot done';
            captchaText.textContent = 'PoW 验证完成，正在提交请求';

            const id = await Api.shorten(url, expireAfter, updateCode, captcha, time);
            const shortUrl = getShortUrl(id);

            captchaText.textContent = '已成功创建短链接';
            showResult(createResult, 'success', [
                '<strong>创建成功!</strong>',
                '<a class="short-url" href="' + shortUrl + '" target="_blank" rel="noopener noreferrer">' + shortUrl + '</a>',
                '<div class="result-row">',
                '<button class="copy-btn" id="copyBtn">复制短链接</button>',
                '</div>'
            ].join(''));
            document.getElementById('copyBtn').addEventListener('click', function () {
                copyToClipboard(shortUrl, this);
            });
        } catch (err) {
            captchaDot.className = 'dot idle';
            captchaText.textContent = '就绪';
            showResult(createResult, 'error', '创建失败: ' + err.message);
        } finally {
            setLoading(createBtn, false);
        }
    });

    const infoForm = document.getElementById('infoForm');
    const infoResult = document.getElementById('infoResult');

    infoForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        hideResult(infoResult);

        const id = document.getElementById('infoId').value.trim();
        if (!id) return;

        try {
            const text = await Api.getInfo(id);
            const parsed = parseInfo(text);

            if (parsed) {
                showResult(infoResult, 'info', [
                    '<div class="result-row"><span class="label">原始 URL:</span><span class="value"><a href="' + parsed.originalUrl + '" target="_blank" rel="noopener noreferrer">' + parsed.originalUrl + '</a></span></div>',
                    '<div class="result-row"><span class="label">短链接 ID:</span><span class="value">' + id + '</span></div>',
                    '<div class="result-row"><span class="label">访问次数:</span><span class="value">' + parsed.viewCount + '</span></div>',
                    '<div class="result-row"><span class="label">创建时间:</span><span class="value">' + formatDate(parsed.createdAt) + '</span></div>',
                    '<div class="result-row"><span class="label">过期时间:</span><span class="value">' + (parsed.expireAfter !== '0' ? formatDate(parsed.createdAt) + ' + ' + parsed.expireAfter + '秒' : '永不过期') + '</span></div>',
                    '<hr>',
                    '<div class="result-row"><span class="label">短链接:</span><span class="value"><a href="' + getShortUrl(id) + '" target="_blank" rel="noopener noreferrer">' + getShortUrl(id) + '</a></span></div>'
                ].join(''));
            } else {
                showResult(infoResult, 'info', '<div class="result-row"><span class="label">原始数据:</span><span class="value">' + text + '</span></div>');
            }
        } catch (err) {
            showResult(infoResult, 'error', '查询失败: ' + err.message);
        }
    });

    const updateForm = document.getElementById('updateForm');
    const updateResult = document.getElementById('updateResult');

    updateForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        hideResult(updateResult);

        const id = document.getElementById('updateId').value.trim();
        const updateCode = document.getElementById('updateCode').value.trim();
        const url = document.getElementById('updateUrl').value.trim();
        const expireEl = document.getElementById('updateExpire');
        const expireAfter = expireEl.value.trim() !== '' ? parseInt(expireEl.value) : undefined;

        if (!id || !updateCode) return;

        try {
            const msg = await Api.update(id, url, expireAfter, updateCode);
            showResult(updateResult, 'info', msg);
        } catch (err) {
            showResult(updateResult, 'error', '修改失败: ' + err.message);
        }
    });

    const deleteForm = document.getElementById('deleteForm');
    const deleteResult = document.getElementById('deleteResult');

    deleteForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        hideResult(deleteResult);

        const id = document.getElementById('deleteId').value.trim();
        const updateCode = document.getElementById('deleteCode').value.trim();

        if (!id || !updateCode) return;

        try {
            const msg = await Api.delete(id, updateCode);
            showResult(deleteResult, 'info', msg);
        } catch (err) {
            showResult(deleteResult, 'error', '删除失败: ' + err.message);
        }
    });
})();
