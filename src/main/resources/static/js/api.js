const Api = {
    baseUrl: '',

    async shorten(url, expireAfter, updateCode, captcha, time) {
        const params = new URLSearchParams();
        params.set('url', url);
        params.set('expireAfter', String(expireAfter));
        params.set('updateCode', updateCode);
        params.set('captcha', captcha);
        params.set('time', String(time));

        const resp = await fetch(this.baseUrl + '/shorten', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        });

        const text = await resp.text();
        if (!resp.ok) {
            throw new Error(extractError(text));
        }
        return text;
    },

    async getInfo(id) {
        const resp = await fetch(this.baseUrl + '/getInfo/' + encodeURIComponent(id));
        const text = await resp.text();
        if (!resp.ok) {
            throw new Error(extractError(text));
        }
        return text;
    },

    async update(id, url, expireAfter, updateCode) {
        const params = new URLSearchParams();
        if (url) params.set('url', url);
        if (expireAfter !== undefined && expireAfter !== null && expireAfter !== '' && expireAfter !== -1) {
            params.set('expireAfter', String(expireAfter));
        }
        params.set('updateCode', updateCode);

        const resp = await fetch(this.baseUrl + '/update/' + encodeURIComponent(id), {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        });

        const text = await resp.text();
        if (!resp.ok) {
            throw new Error(extractError(text));
        }
        return text;
    },

    async delete(id, updateCode) {
        const params = new URLSearchParams();
        params.set('updateCode', updateCode);

        const resp = await fetch(this.baseUrl + '/delete/' + encodeURIComponent(id), {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: params
        });

        const text = await resp.text();
        if (!resp.ok) {
            throw new Error(extractError(text));
        }
        return text;
    }
};

function extractError(text) {
    if (text.startsWith('Internal Server Error')) {
        const match = text.match(/Internal Server Error:\s*(.+)/);
        return match ? match[1].trim() : 'Server error';
    }
    return text || 'Unknown error';
}
